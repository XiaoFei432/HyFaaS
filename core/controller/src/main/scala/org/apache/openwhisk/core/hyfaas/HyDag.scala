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

import scala.collection.immutable.SortedMap

sealed trait HyStageKind {
  def name: String
}

object HyStageKind {
  case object Start extends HyStageKind {
    override val name: String = "start"
  }
  case object End extends HyStageKind {
    override val name: String = "end"
  }
  case object Compute extends HyStageKind {
    override val name: String = "compute"
  }
  case object Io extends HyStageKind {
    override val name: String = "io"
  }

  def fromString(value: String): HyStageKind = value.toLowerCase match {
    case Start.name   => Start
    case End.name     => End
    case Compute.name => Compute
    case "comp"       => Compute
    case Io.name      => Io
    case "proxy"      => Io
    case other        => throw new IllegalArgumentException(s"unknown HyFaaS stage kind: $other")
  }
}

case class DagStage(id: String, action: String, parallelism: Int = 1, cpus: Double = 1.0)

case class DagEdge(from: String, to: String, bytes: Long = 0L)

case class WorkflowDag(stages: Vector[DagStage], edges: Vector[DagEdge]) {
  require(stages.map(_.id).distinct.size == stages.size, "DAG stage ids must be unique")

  val stageById: Map[String, DagStage] = stages.map(s => s.id -> s).toMap

  require(edges.forall(e => stageById.contains(e.from) && stageById.contains(e.to)), "DAG edges must reference stages")
}

case class HyStage(id: String,
                   kind: HyStageKind,
                   action: Option[String],
                   source: Option[String],
                   target: Option[String],
                   inputBytes: Long,
                   baselineParallelism: Int,
                   baselineCpus: Double) {
  def isSynthetic: Boolean = kind == HyStageKind.Start || kind == HyStageKind.End
}

case class HyEdge(from: String, to: String, bytes: Long = 0L)

case class HyDag(stages: Vector[HyStage],
                 edges: Vector[HyEdge],
                 start: String = HyDag.StartNode,
                 end: String = HyDag.EndNode) {
  require(stages.map(_.id).distinct.size == stages.size, "HyDAG stage ids must be unique")

  val stageById: Map[String, HyStage] = stages.map(s => s.id -> s).toMap

  require(stageById.contains(start), "HyDAG must contain a start node")
  require(stageById.contains(end), "HyDAG must contain an end node")
  require(
    edges.forall(e => stageById.contains(e.from) && stageById.contains(e.to)),
    "HyDAG edges must reference stages")

  lazy val outgoing: Map[String, Vector[HyEdge]] = {
    val grouped = edges.groupBy(_.from).map { case (from, es) => from -> es.toVector }
    stages.map(s => s.id -> grouped.getOrElse(s.id, Vector.empty)).toMap
  }

  lazy val incoming: Map[String, Vector[HyEdge]] = {
    val grouped = edges.groupBy(_.to).map { case (to, es) => to -> es.toVector }
    stages.map(s => s.id -> grouped.getOrElse(s.id, Vector.empty)).toMap
  }

  lazy val topologicalOrder: Vector[String] = {
    val inDegree = collection.mutable.Map(stages.map(s => s.id -> incoming(s.id).size): _*)
    val ready = collection.mutable.PriorityQueue[String]()(Ordering.by[String, String](identity).reverse)
    stages.foreach { s =>
      if (inDegree(s.id) == 0) ready.enqueue(s.id)
    }
    val order = Vector.newBuilder[String]
    while (ready.nonEmpty) {
      val id = ready.dequeue()
      order += id
      outgoing(id).foreach { edge =>
        val next = edge.to
        inDegree(next) = inDegree(next) - 1
        if (inDegree(next) == 0) ready.enqueue(next)
      }
    }
    val result = order.result()
    require(result.size == stages.size, "HyDAG must be acyclic")
    result
  }

  def criticalPathLatency(latencies: Map[String, Double]): Double = {
    val dist = collection.mutable.Map[String, Double]().withDefaultValue(Double.NegativeInfinity)
    dist(start) = latencies.getOrElse(start, 0.0)
    topologicalOrder.foreach { id =>
      val base = dist(id)
      outgoing(id).foreach { edge =>
        val candidate = base + latencies.getOrElse(edge.to, 0.0)
        if (candidate > dist(edge.to)) dist(edge.to) = candidate
      }
    }
    math.max(0.0, dist(end))
  }

  def criticalPath(latencies: Map[String, Double]): Vector[String] = {
    val dist = collection.mutable.Map[String, Double]().withDefaultValue(Double.NegativeInfinity)
    val prev = collection.mutable.Map[String, String]()
    dist(start) = latencies.getOrElse(start, 0.0)
    topologicalOrder.foreach { id =>
      outgoing(id).foreach { edge =>
        val candidate = dist(id) + latencies.getOrElse(edge.to, 0.0)
        if (candidate > dist(edge.to)) {
          dist(edge.to) = candidate
          prev(edge.to) = id
        }
      }
    }
    val path = collection.mutable.ArrayBuffer[String](end)
    var cur = end
    while (prev.contains(cur)) {
      cur = prev(cur)
      path.prepend(cur)
    }
    path.toVector
  }
}

object HyDag {
  val StartNode = "hyfaas-start"
  val EndNode = "hyfaas-end"
}

case class HyfaasCluster(servers: Int, cpuPerServer: Double, bandwidthPerCpuMbps: Double, bandwidthCapMbps: Double) {
  require(servers > 0, "server count must be positive")
  require(cpuPerServer > 0, "CPU capacity must be positive")
  require(bandwidthPerCpuMbps > 0, "bandwidth per CPU must be positive")
  require(bandwidthCapMbps > 0, "bandwidth cap must be positive")
}

case class HyfaasPricing(cpuSecond: Double, request: Double) {
  require(cpuSecond >= 0, "CPU-second price must be non-negative")
  require(request >= 0, "request price must be non-negative")
}

case class StageConfiguration(parallelism: Int,
                              cpus: Double,
                              distribution: Vector[Int],
                              latencyMs: Double,
                              cost: Double,
                              saturated: Boolean = false) {
  require(parallelism > 0, "parallelism must be positive")
  require(cpus > 0, "cpus must be positive")
  require(distribution.nonEmpty, "distribution must not be empty")
  require(distribution.sum == parallelism, "distribution must sum to parallelism")

  def withDistribution(next: Vector[Int], nextLatencyMs: Double): StageConfiguration =
    copy(distribution = next, latencyMs = nextLatencyMs)
}

case class StageProfile(stageId: String,
                        kind: HyStageKind,
                        pareto: Vector[StageConfiguration],
                        saturation: StageConfiguration) {
  require(pareto.nonEmpty, s"stage $stageId must have at least one Pareto configuration")
}

case class DeploymentPlan(stageConfigs: Map[String, StageConfiguration]) {
  def latencyMs(hyDag: HyDag): Double =
    hyDag.criticalPathLatency(stageConfigs.map { case (stage, config) => stage -> config.latencyMs })

  def cost: Double = stageConfigs.values.map(_.cost).sum

  def placements: Map[String, Vector[Int]] = stageConfigs.map { case (stage, config) => stage -> config.distribution }

  def orderedPlacements: SortedMap[String, Vector[Int]] = SortedMap(placements.toSeq: _*)
}

case class Branch(id: String, stages: Vector[String], predecessors: Set[String], successors: Set[String]) {
  def latency(latencies: Map[String, Double]): Double = stages.map(stage => latencies.getOrElse(stage, 0.0)).sum
}

case class BranchWiseDag(branches: Vector[Branch], start: String, end: String) {
  val branchById: Map[String, Branch] = branches.map(b => b.id -> b).toMap
}

case class BranchBound(branchId: String, latencyBoundMs: Double, saturatedLatencyMs: Double)

case class HyfaasRuntimeEvent(workflow: String, stageId: String, latencyMs: Double, finishedAtEpochMs: Long)
