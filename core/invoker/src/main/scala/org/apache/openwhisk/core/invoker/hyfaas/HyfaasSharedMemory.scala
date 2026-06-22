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

package org.apache.openwhisk.core.invoker.hyfaas

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.Base64

case class HyfaasMemoryKey(workflow: String, activation: String, stage: String, partition: Int) {
  def pathSegment: String = Vector(workflow, activation, stage, partition.toString).map(HyfaasSharedMemory.safe).mkString("/")
}

case class HyfaasProxyDescriptor(workflow: String,
                                 activation: String,
                                 sourceStage: String,
                                 targetStage: String,
                                 partition: Int,
                                 sender: Boolean,
                                 objectName: String)

class HyfaasSharedMemory(root: Path) {
  Files.createDirectories(root)

  def write(key: HyfaasMemoryKey, payload: Array[Byte]): Path = {
    val target = root.resolve(key.pathSegment).resolve("payload.bin")
    Files.createDirectories(target.getParent)
    val tmp = target.resolveSibling("payload.bin.tmp")
    Files.write(tmp, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    target
  }

  def read(key: HyfaasMemoryKey): Option[Array[Byte]] = {
    val target = root.resolve(key.pathSegment).resolve("payload.bin")
    if (Files.exists(target)) Some(Files.readAllBytes(target)) else None
  }

  def delete(key: HyfaasMemoryKey): Unit = {
    val target = root.resolve(key.pathSegment).resolve("payload.bin")
    Files.deleteIfExists(target)
  }

  def putString(key: HyfaasMemoryKey, value: String): Path = write(key, value.getBytes(StandardCharsets.UTF_8))

  def getString(key: HyfaasMemoryKey): Option[String] = read(key).map(bytes => new String(bytes, StandardCharsets.UTF_8))
}

object HyfaasSharedMemory {
  val DefaultRoot: Path = Paths.get(sys.env.getOrElse("HYFAAS_SHARED_MEMORY", "/mnt/hyfaas"))

  def default: HyfaasSharedMemory = new HyfaasSharedMemory(DefaultRoot)

  def safe(value: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))
}
