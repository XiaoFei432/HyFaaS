/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.hyfaas

case class OptimizerKnobs(maxLhpIterations: Int = 64, minGainMs: Double = 0.5, branchSlackFraction: Double = 1.0)

class LhpOptimizer(cluster: HyfaasCluster, knobs: OptimizerKnobs = OptimizerKnobs()) {
  def saturatedPlan(hyDag: HyDag, profiles: Map[String, StageProfile]): DeploymentPlan = {
    val synthetic = hyDag.stages.filter(_.isSynthetic).map { stage =>
      stage.id -> StageConfiguration(1, 1.0, Vector.fill(cluster.servers)(0).updated(0, 1), 0.0, 0.0, saturated = true)
    }
    DeploymentPlan(synthetic.toMap ++ profiles.map { case (stageId, profile) => stageId -> profile.saturation })
  }

  def optimize(hyDag: HyDag, profiles: Map[String, StageProfile]): DeploymentPlan = {
    val initial = saturatedPlan(hyDag, profiles)
    val lhp = optimizePlacement(hyDag, initial)
    coordinateBranches(hyDag, profiles, lhp)
  }

  def optimizePlacement(hyDag: HyDag, initial: DeploymentPlan): DeploymentPlan = {
    var plan = initial
    var previousLatency = plan.latencyMs(hyDag)
    var iter = 0
    var improved = true
    while (iter < knobs.maxLhpIterations && improved) {
      iter += 1
      val next = bestSingleMove(hyDag, plan).getOrElse(plan)
      val nextLatency = next.latencyMs(hyDag)
      improved = previousLatency - nextLatency > knobs.minGainMs
      if (improved) {
        plan = next
        previousLatency = nextLatency
      }
    }
    plan
  }

  private def bestSingleMove(hyDag: HyDag, plan: DeploymentPlan): Option[DeploymentPlan] = {
    val candidates = for {
      stage <- hyDag.stages if !stage.isSynthetic
      config <- plan.stageConfigs.get(stage.id).toVector
      from <- config.distribution.indices if config.distribution(from) > 0
      to <- config.distribution.indices if to != from
    } yield {
      val dist = config.distribution
        .updated(from, config.distribution(from) - 1)
        .updated(to, config.distribution(to) + 1)
      val latency = updateLatency(stage, config, dist, hyDag, plan)
      plan.copy(stageConfigs = plan.stageConfigs.updated(stage.id, config.withDistribution(dist, latency)))
    }

    val current = plan.latencyMs(hyDag)
    candidates.sortBy(_.latencyMs(hyDag)).find(_.latencyMs(hyDag) < current)
  }

  private def updateLatency(stage: HyStage,
                            config: StageConfiguration,
                            distribution: Vector[Int],
                            hyDag: HyDag,
                            plan: DeploymentPlan): Double = {
    stage.kind match {
      case HyStageKind.Compute =>
        val busiest = distribution.max
        val oldBusiest = math.max(1, config.distribution.max)
        val scaleShare = busiest.toDouble / oldBusiest.toDouble
        math.max(1.0, config.latencyMs * (0.85 + 0.15 * scaleShare))
      case HyStageKind.Io =>
        val remoteShare = remoteShareForIo(stage, distribution, hyDag, plan)
        math.max(1.0, config.latencyMs * (0.25 + 0.75 * remoteShare))
      case _ => 0.0
    }
  }

  private def remoteShareForIo(stage: HyStage,
                               distribution: Vector[Int],
                               hyDag: HyDag,
                               plan: DeploymentPlan): Double = {
    val maybeSrc = stage.source.flatMap(plan.stageConfigs.get).map(_.distribution)
    val maybeDst = stage.target.flatMap(plan.stageConfigs.get).map(_.distribution)
    (maybeSrc, maybeDst) match {
      case (Some(src), Some(dst)) =>
        val local = src.zip(dst).map { case (a, b) => math.min(a, b) }.sum.toDouble
        val total = math.max(1.0, math.max(src.sum, dst.sum).toDouble)
        val proxySkew = distribution.max.toDouble / math.max(1.0, distribution.sum.toDouble)
        math.min(1.0, math.max(0.0, 1.0 - local / total + 0.1 * proxySkew))
      case _ => 1.0
    }
  }

  def coordinateBranches(hyDag: HyDag, profiles: Map[String, StageProfile], plan: DeploymentPlan): DeploymentPlan = {
    val critical = hyDag.criticalPath(plan.stageConfigs.map { case (stage, config) => stage -> config.latencyMs }).toSet
    val workflowBound = plan.latencyMs(hyDag)
    var current = plan

    hyDag.topologicalOrder.foreach {
      case stageId if critical.contains(stageId) || !profiles.contains(stageId) =>
      case stageId =>
        val config = current.stageConfigs(stageId)
        val relaxedBound =
          math.max(config.latencyMs, config.latencyMs + (workflowBound - config.latencyMs) * knobs.branchSlackFraction)
        val cheaper = profiles(stageId).pareto
          .filter(candidate => candidate.cost <= config.cost && candidate.latencyMs <= relaxedBound)
          .sortBy(c => (c.cost, c.latencyMs))
          .map(c => c.copy(distribution = balanced(c.parallelism)))
          .find { candidate =>
            val candidatePlan = current.copy(stageConfigs = current.stageConfigs.updated(stageId, candidate))
            candidatePlan.latencyMs(hyDag) <= workflowBound + 1e-6
          }
        cheaper.foreach { candidate =>
          current = current.copy(stageConfigs = current.stageConfigs.updated(stageId, candidate))
        }
    }
    current
  }

  private def balanced(parallelism: Int): Vector[Int] = {
    val base = parallelism / cluster.servers
    val rem = parallelism % cluster.servers
    (0 until cluster.servers).map(i => base + (if (i < rem) 1 else 0)).toVector
  }

  def toBranchWiseDag(hyDag: HyDag): BranchWiseDag = {
    val nonSynthetic = hyDag.stages.filterNot(_.isSynthetic).map(_.id).toSet
    val branchIds = nonSynthetic.toVector.sorted
    val branches = branchIds.map { id =>
      val preds = hyDag.incoming(id).map(_.from).filter(nonSynthetic).toSet
      val succs = hyDag.outgoing(id).map(_.to).filter(nonSynthetic).toSet
      Branch(s"branch-$id", Vector(id), preds.map(p => s"branch-$p"), succs.map(s => s"branch-$s"))
    }
    val roots = branches.filter(_.predecessors.isEmpty).map(_.id).toSet
    val sinks = branches.filter(_.successors.isEmpty).map(_.id).toSet
    val start = "branch-start"
    val end = "branch-end"
    BranchWiseDag(
      Branch(start, Vector.empty, Set.empty, roots) +:
        branches :+
        Branch(end, Vector.empty, sinks, Set.empty),
      start,
      end)
  }

  def assignLatencyBounds(branchWiseDag: BranchWiseDag,
                          latencies: Map[String, Double],
                          workflowBoundMs: Double): Vector[BranchBound] = {
    val est = collection.mutable.Map[String, Double]().withDefaultValue(0.0)
    val ordered = branchWiseDag.branches.map(_.id)
    ordered.foreach { id =>
      val branch = branchWiseDag.branchById(id)
      val start = branch.predecessors
        .map(pred => est(pred) + branchWiseDag.branchById(pred).latency(latencies))
        .foldLeft(0.0)(math.max)
      est(id) = start
    }
    val lft = collection.mutable.Map[String, Double]().withDefaultValue(workflowBoundMs)
    ordered.reverse.foreach { id =>
      val branch = branchWiseDag.branchById(id)
      if (branch.successors.nonEmpty) {
        lft(id) = branch.successors.map(succ => lft(succ) - branchWiseDag.branchById(succ).latency(latencies)).min
      }
    }
    branchWiseDag.branches.filterNot(b => b.id == branchWiseDag.start || b.id == branchWiseDag.end).map { branch =>
      val saturated = branch.latency(latencies)
      val bound = math.max(saturated, lft(branch.id) - est(branch.id))
      BranchBound(branch.id, bound, saturated)
    }
  }
}
