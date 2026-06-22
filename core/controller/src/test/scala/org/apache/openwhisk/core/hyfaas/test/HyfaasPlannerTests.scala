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

package org.apache.openwhisk.core.hyfaas.test

import org.apache.openwhisk.core.hyfaas._
import org.junit.Assert._
import org.junit.Test

class HyfaasPlannerTests {
  private val workflow = WorkflowDag(
    stages = Vector(
      DagStage("extract", "/guest/extract", parallelism = 4, cpus = 1.0),
      DagStage("classify", "/guest/classify", parallelism = 8, cpus = 2.0),
      DagStage("merge", "/guest/merge", parallelism = 2, cpus = 1.0)),
    edges = Vector(
      DagEdge("extract", "classify", bytes = 128L * 1024L * 1024L),
      DagEdge("classify", "merge", bytes = 32L * 1024L * 1024L)))

  private val cluster =
    HyfaasCluster(servers = 4, cpuPerServer = 8.0, bandwidthPerCpuMbps = 40.0, bandwidthCapMbps = 1000.0)
  private val pricing = HyfaasPricing(cpuSecond = 0.00001667, request = 0.0000002)

  @Test
  def translateDagEdgesIntoExplicitIoStages(): Unit = {
    val hyDag = HyDagTranslator.translate(workflow)

    assertEquals(3, hyDag.stages.count(_.kind == HyStageKind.Compute))
    assertEquals(2, hyDag.stages.count(_.kind == HyStageKind.Io))
    assertEquals(HyDag.StartNode, hyDag.topologicalOrder.head)
    assertEquals(HyDag.EndNode, hyDag.topologicalOrder.last)
  }

  @Test
  def produceParetoProfilesAndOptimizedDeploymentPlan(): Unit = {
    val result = HyfaasService.plan(HyfaasRequest(workflow, cluster, pricing))

    assertTrue(result.profiles.keySet.contains("extract"))
    assertTrue(result.profiles.keySet.contains("classify"))
    assertTrue(result.profiles.keySet.contains("merge"))
    assertTrue(result.profiles.values.forall(_.pareto.nonEmpty))
    assertTrue(result.optimizedPlan.stageConfigs.keySet.contains(HyDag.StartNode))
    assertTrue(result.workflowLatencyMs > 0.0)
    assertTrue(result.workflowCost > 0.0)
  }

  @Test
  def assignBranchBoundsNoLowerThanSaturatedLatency(): Unit = {
    val result = HyfaasService.plan(HyfaasRequest(workflow, cluster, pricing))

    assertTrue(result.branchBounds.forall(bound => bound.latencyBoundMs >= bound.saturatedLatencyMs))
  }
}
