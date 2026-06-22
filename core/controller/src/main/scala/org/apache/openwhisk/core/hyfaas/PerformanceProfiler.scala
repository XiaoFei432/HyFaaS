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

case class InvocationSample(stageId: String,
                            inputBytes: Long,
                            contentFeatures: Map[String, Double],
                            initMs: Double,
                            computeMs: Double,
                            startedCold: Boolean)

case class ProfilingKnobs(parallelismCandidates: Vector[Int] = Vector(1, 2, 4, 8, 16),
                          cpuCandidates: Vector[Double] = Vector(0.5, 1.0, 2.0, 4.0),
                          blockMs: Double = 1.5,
                          startMs: Double = 2.0,
                          storageRequestMs: Double = 8.0,
                          coldStartMs: Double = 150.0,
                          scaleMsPerInstance: Double = 25.0,
                          ioRemoteShare: Double = 1.0,
                          costWeight: Double = 0.05)

class PerformanceProfiler(cluster: HyfaasCluster, pricing: HyfaasPricing, knobs: ProfilingKnobs = ProfilingKnobs()) {
  def profile(hyDag: HyDag, samples: Seq[InvocationSample] = Seq.empty): Map[String, StageProfile] = {
    hyDag.stages.filterNot(_.isSynthetic).map { stage =>
      val stageSamples = samples.filter(_.stageId == stage.id)
      val configs = stage.kind match {
        case HyStageKind.Compute => computeConfigs(stage, stageSamples)
        case HyStageKind.Io      => ioConfigs(stage)
        case other               => throw new IllegalArgumentException(s"cannot profile synthetic stage kind $other")
      }
      val front = paretoFront(configs)
      stage.id -> StageProfile(stage.id, stage.kind, front, chooseSaturation(front))
    }.toMap
  }

  private def computeConfigs(stage: HyStage, samples: Seq[InvocationSample]): Vector[StageConfiguration] = {
    val stats = ComputeStats.from(stage, samples, knobs)
    for {
      parallelism <- knobs.parallelismCandidates.filter(_ >= 1)
      cpus <- knobs.cpuCandidates.filter(_ > 0)
      if cpus <= cluster.cpuPerServer
    } yield {
      val distribution = balanced(parallelism, cluster.servers)
      val busiest = distribution.max
      val init = busiest * knobs.scaleMsPerInstance + stats.coldStartMs
      val perInstanceBytes = math.max(1.0, stage.inputBytes.toDouble / parallelism)
      val solo = stats.predictSoloRunMs(perInstanceBytes)
      val subtasks = math.max(1, math.ceil(cpus).toInt)
      val compute = (0 until subtasks).map { idx =>
        idx * knobs.blockMs + knobs.startMs + solo / cpus
      }.max
      val latency = init + compute
      val cost = pricing.cpuSecond * cpus * parallelism * latency / 1000.0 + pricing.request * parallelism
      StageConfiguration(parallelism, cpus, distribution, latency, cost)
    }
  }

  private def ioConfigs(stage: HyStage): Vector[StageConfiguration] = {
    val remoteBytes = math.max(1.0, stage.inputBytes.toDouble * knobs.ioRemoteShare)
    for {
      parallelism <- knobs.parallelismCandidates.filter(_ >= 1)
      cpus <- knobs.cpuCandidates.filter(_ > 0)
      if cpus <= cluster.cpuPerServer
    } yield {
      val distribution = balanced(parallelism, cluster.servers)
      val bandwidthMbps = math.min(cpus * cluster.bandwidthPerCpuMbps, cluster.bandwidthCapMbps)
      val bytesPerMs = bandwidthMbps * 1000.0 * 1000.0 / 8.0 / 1000.0
      val transfer = 2.0 * remoteBytes / parallelism / bytesPerMs + knobs.storageRequestMs
      val latency = distribution.max * knobs.scaleMsPerInstance * 0.1 + transfer
      val cost = pricing.cpuSecond * cpus * parallelism * latency / 1000.0 + pricing.request * parallelism
      StageConfiguration(parallelism, cpus, distribution, latency, cost)
    }
  }

  private def balanced(parallelism: Int, servers: Int): Vector[Int] = {
    val base = parallelism / servers
    val rem = parallelism % servers
    (0 until servers).map(i => base + (if (i < rem) 1 else 0)).toVector
  }

  private def paretoFront(configs: Vector[StageConfiguration]): Vector[StageConfiguration] = {
    configs
      .filterNot { candidate =>
        configs.exists { other =>
          other != candidate &&
          other.latencyMs <= candidate.latencyMs &&
          other.cost <= candidate.cost &&
          (other.latencyMs < candidate.latencyMs || other.cost < candidate.cost)
        }
      }
      .sortBy(c => (c.latencyMs, c.cost))
  }

  private def chooseSaturation(front: Vector[StageConfiguration]): StageConfiguration = {
    require(front.nonEmpty, "cannot choose saturation from an empty Pareto front")
    val minLatency = front.map(_.latencyMs).min
    val maxLatency = front.map(_.latencyMs).max
    val minCost = front.map(_.cost).min
    val maxCost = front.map(_.cost).max

    def normalize(value: Double, min: Double, max: Double): Double =
      if (math.abs(max - min) < 1e-9) 0.0 else (value - min) / (max - min)

    front
      .minBy { c =>
        normalize(c.latencyMs, minLatency, maxLatency) + knobs.costWeight * normalize(c.cost, minCost, maxCost)
      }
      .copy(saturated = true)
  }
}

case class ComputeStats(baseMs: Double, perMbMs: Double, coldStartMs: Double, featurePenalty: Double) {
  def predictSoloRunMs(inputBytes: Double): Double =
    math.max(1.0, baseMs + perMbMs * inputBytes / (1024.0 * 1024.0) + featurePenalty)
}

object ComputeStats {
  def from(stage: HyStage, samples: Seq[InvocationSample], knobs: ProfilingKnobs): ComputeStats = {
    if (samples.isEmpty) {
      val mb = math.max(1.0, stage.inputBytes.toDouble / (1024.0 * 1024.0))
      ComputeStats(
        baseMs = 5.0,
        perMbMs = 20.0 + 5.0 * math.log1p(mb),
        coldStartMs = knobs.coldStartMs,
        featurePenalty = 0.0)
    } else {
      val xs = samples.map(s => math.max(1.0, s.inputBytes.toDouble / (1024.0 * 1024.0)))
      val ys = samples.map(s => math.max(1.0, s.computeMs))
      val xMean = xs.sum / xs.size
      val yMean = ys.sum / ys.size
      val denom = xs.map(x => math.pow(x - xMean, 2)).sum
      val slope = if (denom <= 1e-9) 0.0 else xs.zip(ys).map { case (x, y) => (x - xMean) * (y - yMean) }.sum / denom
      val intercept = math.max(1.0, yMean - slope * xMean)
      val featurePenalty =
        samples.flatMap(_.contentFeatures.values).headOption
          .map(_ => samples.flatMap(_.contentFeatures.values).sum / samples.size)
          .getOrElse(0.0)
      val cold = samples.filter(_.startedCold).map(_.initMs).sorted match {
        case Nil => 0.0
        case seq => seq(seq.size / 2)
      }
      ComputeStats(intercept, math.max(0.0, slope), cold, featurePenalty)
    }
  }
}
