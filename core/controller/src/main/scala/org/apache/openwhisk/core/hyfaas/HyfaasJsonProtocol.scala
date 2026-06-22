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

import spray.json.deserializationError
import spray.json.{JsString, JsValue, RootJsonFormat}

object HyfaasJsonProtocol extends spray.json.DefaultJsonProtocol {
  implicit object StageKindFormat extends RootJsonFormat[HyStageKind] {
    override def write(obj: HyStageKind): JsValue = JsString(obj.name)

    override def read(json: JsValue): HyStageKind = json match {
      case JsString(value) => HyStageKind.fromString(value)
      case other           => deserializationError(s"expected HyStageKind string, got $other")
    }
  }

  implicit val dagStageFormat: RootJsonFormat[DagStage] =
    jsonFormat(DagStage.apply, "id", "action", "parallelism", "cpus")
  implicit val dagEdgeFormat: RootJsonFormat[DagEdge] =
    jsonFormat(DagEdge.apply, "from", "to", "bytes")
  implicit val workflowDagFormat: RootJsonFormat[WorkflowDag] =
    jsonFormat(WorkflowDag.apply, "stages", "edges")

  implicit val hyStageFormat: RootJsonFormat[HyStage] =
    jsonFormat(HyStage.apply, "id", "kind", "action", "source", "target", "inputBytes", "baselineParallelism", "baselineCpus")
  implicit val hyEdgeFormat: RootJsonFormat[HyEdge] =
    jsonFormat(HyEdge.apply, "from", "to", "bytes")
  implicit val hyDagFormat: RootJsonFormat[HyDag] =
    jsonFormat(HyDag.apply, "stages", "edges", "start", "end")

  implicit val clusterFormat: RootJsonFormat[HyfaasCluster] =
    jsonFormat(HyfaasCluster.apply, "servers", "cpuPerServer", "bandwidthPerCpuMbps", "bandwidthCapMbps")
  implicit val pricingFormat: RootJsonFormat[HyfaasPricing] =
    jsonFormat(HyfaasPricing.apply, "cpuSecond", "request")
  implicit val stageConfigFormat: RootJsonFormat[StageConfiguration] =
    jsonFormat(StageConfiguration.apply, "parallelism", "cpus", "distribution", "latencyMs", "cost", "saturated")
  implicit val stageProfileFormat: RootJsonFormat[StageProfile] =
    jsonFormat(StageProfile.apply, "stageId", "kind", "pareto", "saturation")
  implicit val deploymentPlanFormat: RootJsonFormat[DeploymentPlan] =
    jsonFormat(DeploymentPlan.apply, "stageConfigs")
  implicit val invocationSampleFormat: RootJsonFormat[InvocationSample] =
    jsonFormat(InvocationSample.apply, "stageId", "inputBytes", "contentFeatures", "initMs", "computeMs", "startedCold")
  implicit val profilingKnobsFormat: RootJsonFormat[ProfilingKnobs] =
    jsonFormat(
      ProfilingKnobs.apply,
      "parallelismCandidates",
      "cpuCandidates",
      "blockMs",
      "startMs",
      "storageRequestMs",
      "coldStartMs",
      "scaleMsPerInstance",
      "ioRemoteShare",
      "costWeight")
  implicit val optimizerKnobsFormat: RootJsonFormat[OptimizerKnobs] =
    jsonFormat(OptimizerKnobs.apply, "maxLhpIterations", "minGainMs", "branchSlackFraction")
  implicit val branchBoundFormat: RootJsonFormat[BranchBound] =
    jsonFormat(BranchBound.apply, "branchId", "latencyBoundMs", "saturatedLatencyMs")
  implicit val runtimeEventFormat: RootJsonFormat[HyfaasRuntimeEvent] =
    jsonFormat(HyfaasRuntimeEvent.apply, "workflow", "stageId", "latencyMs", "finishedAtEpochMs")
  implicit val requestFormat: RootJsonFormat[HyfaasRequest] =
    jsonFormat(HyfaasRequest.apply, "workflow", "cluster", "pricing", "samples", "profiling", "optimizer")
  implicit val planResultFormat: RootJsonFormat[HyfaasPlanResult] =
    jsonFormat(
      HyfaasPlanResult.apply,
      "hyDag",
      "profiles",
      "saturatedPlan",
      "lhpPlan",
      "optimizedPlan",
      "workflowLatencyMs",
      "workflowCost",
      "branchBounds")
}
