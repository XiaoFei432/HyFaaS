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

import spray.json.DefaultJsonProtocol._
import spray.json._

case class HyfaasActivationContext(workflow: String,
                                   activation: String,
                                   stage: String,
                                   partition: Int,
                                   sharedMemoryRoot: String,
                                   inputKey: String,
                                   outputKey: String)

object HyfaasActivationContext {
  val DefaultSharedMemoryRoot = "/mnt/hyfaas"

  def fromParameters(parameters: JsValue): Option[HyfaasActivationContext] = {
    parameters.asJsObject.fields.get("hyfaas").map { value =>
      val fields = value.asJsObject.fields
      def string(name: String) = fields(name).convertTo[String]
      def int(name: String) = fields(name).convertTo[Int]
      HyfaasActivationContext(
        workflow = string("workflow"),
        activation = string("activation"),
        stage = string("stage"),
        partition = int("partition"),
        sharedMemoryRoot = fields.get("sharedMemoryRoot").map(_.convertTo[String]).getOrElse(DefaultSharedMemoryRoot),
        inputKey = fields.get("inputKey").map(_.convertTo[String]).getOrElse("input"),
        outputKey = fields.get("outputKey").map(_.convertTo[String]).getOrElse("output"))
    }
  }

  def inject(environment: JsObject, context: HyfaasActivationContext): JsObject = {
    val hyfaas = JsObject(
      "HYFAAS_WORKFLOW" -> JsString(context.workflow),
      "HYFAAS_ACTIVATION" -> JsString(context.activation),
      "HYFAAS_STAGE" -> JsString(context.stage),
      "HYFAAS_PARTITION" -> JsString(context.partition.toString),
      "HYFAAS_SHARED_MEMORY" -> JsString(context.sharedMemoryRoot),
      "HYFAAS_INPUT_KEY" -> JsString(context.inputKey),
      "HYFAAS_OUTPUT_KEY" -> JsString(context.outputKey))
    JsObject(environment.fields ++ hyfaas.fields)
  }
}
