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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import spray.json._
import HyfaasJsonProtocol._

object HyfaasPlannerCli {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      System.err.println("usage: HyfaasPlannerCli <workflow-request.json>")
      System.exit(2)
    }
    val json = new String(Files.readAllBytes(Paths.get(args(0))), StandardCharsets.UTF_8)
      .parseJson
      .convertTo[HyfaasRequest]
    val result = HyfaasService.plan(json)
    println(result.toJson.prettyPrint)
  }
}
