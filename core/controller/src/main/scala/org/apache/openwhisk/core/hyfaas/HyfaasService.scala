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

case class HyfaasRequest(workflow: WorkflowDag,
                         cluster: HyfaasCluster,
                         pricing: HyfaasPricing,
                         samples: Vector[InvocationSample] = Vector.empty,
                         profiling: ProfilingKnobs = ProfilingKnobs(),
                         optimizer: OptimizerKnobs = OptimizerKnobs())

case class HyfaasPlanResult(hyDag: HyDag,
                            profiles: Map[String, StageProfile],
                            saturatedPlan: DeploymentPlan,
                            lhpPlan: DeploymentPlan,
                            optimizedPlan: DeploymentPlan,
                            workflowLatencyMs: Double,
                            workflowCost: Double,
                            branchBounds: Vector[BranchBound])

object HyfaasService {
  def plan(request: HyfaasRequest): HyfaasPlanResult = {
    val hyDag = HyDagTranslator.translate(request.workflow)
    val profiler = new PerformanceProfiler(request.cluster, request.pricing, request.profiling)
    val profiles = profiler.profile(hyDag, request.samples)
    val optimizer = new LhpOptimizer(request.cluster, request.optimizer)
    val saturated = optimizer.saturatedPlan(hyDag, profiles)
    val lhp = optimizer.optimizePlacement(hyDag, saturated)
    val optimized = optimizer.coordinateBranches(hyDag, profiles, lhp)
    val branchDag = optimizer.toBranchWiseDag(hyDag)
    val branchBounds = optimizer.assignLatencyBounds(
      branchDag,
      optimized.stageConfigs.map { case (stage, config) => stage -> config.latencyMs },
      optimized.latencyMs(hyDag))
    HyfaasPlanResult(
      hyDag = hyDag,
      profiles = profiles,
      saturatedPlan = saturated,
      lhpPlan = lhp,
      optimizedPlan = optimized,
      workflowLatencyMs = optimized.latencyMs(hyDag),
      workflowCost = optimized.cost,
      branchBounds = branchBounds)
  }
}
