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

object HyDagTranslator {
  def translate(workflow: WorkflowDag): HyDag = {
    val computeStages = workflow.stages.map { stage =>
      HyStage(
        id = stage.id,
        kind = HyStageKind.Compute,
        action = Some(stage.action),
        source = None,
        target = None,
        inputBytes = incomingBytes(workflow, stage.id),
        baselineParallelism = stage.parallelism,
        baselineCpus = stage.cpus)
    }

    val ioStages = workflow.edges.zipWithIndex.map {
      case (edge, idx) =>
        HyStage(
          id = s"hyio-${edge.from}-to-${edge.to}-$idx",
          kind = HyStageKind.Io,
          action = Some("hyfaas/proxy"),
          source = Some(edge.from),
          target = Some(edge.to),
          inputBytes = edge.bytes,
          baselineParallelism = math.max(1, workflow.stageById(edge.to).parallelism),
          baselineCpus = 1.0)
    }

    val start = HyStage(
      id = HyDag.StartNode,
      kind = HyStageKind.Start,
      action = None,
      source = None,
      target = None,
      inputBytes = 0L,
      baselineParallelism = 1,
      baselineCpus = 1.0)
    val end = start.copy(id = HyDag.EndNode, kind = HyStageKind.End)

    val sourceIds = workflow.edges.map(_.to).toSet
    val sinkIds = workflow.edges.map(_.from).toSet
    val rootEdges = workflow.stages.filterNot(s => sourceIds.contains(s.id)).map(s => HyEdge(start.id, s.id))
    val terminalEdges = workflow.stages.filterNot(s => sinkIds.contains(s.id)).map(s => HyEdge(s.id, end.id))

    val translatedEdges = workflow.edges.zipWithIndex.flatMap {
      case (edge, idx) =>
        val io = s"hyio-${edge.from}-to-${edge.to}-$idx"
        Vector(HyEdge(edge.from, io, edge.bytes), HyEdge(io, edge.to, edge.bytes))
    }

    HyDag(Vector(start) ++ computeStages ++ ioStages ++ Vector(end), rootEdges ++ translatedEdges ++ terminalEdges)
  }

  private def incomingBytes(workflow: WorkflowDag, stageId: String): Long = {
    val total = workflow.edges.filter(_.to == stageId).map(_.bytes).sum
    if (total > 0) total else workflow.edges.filter(_.from == stageId).map(_.bytes).sum
  }
}
