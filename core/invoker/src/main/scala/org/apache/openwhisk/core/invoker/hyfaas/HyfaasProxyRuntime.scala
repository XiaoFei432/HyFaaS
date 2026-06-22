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

import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}

case class HyfaasProxyResult(bytes: Long, storagePath: Path, localKey: HyfaasMemoryKey)

class HyfaasProxyRuntime(memory: HyfaasSharedMemory, storageRoot: Path) {
  Files.createDirectories(storageRoot)

  def send(descriptor: HyfaasProxyDescriptor): HyfaasProxyResult = {
    val sourceKey =
      HyfaasMemoryKey(descriptor.workflow, descriptor.activation, descriptor.sourceStage, descriptor.partition)
    val payload = memory
      .read(sourceKey)
      .getOrElse(throw new IllegalStateException(s"missing HyFaaS local payload: ${sourceKey.pathSegment}"))
    val target = objectPath(descriptor)
    Files.createDirectories(target.getParent)
    val tmp = target.resolveSibling(target.getFileName.toString + ".tmp")
    Files.write(tmp, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    HyfaasProxyResult(payload.length.toLong, target, sourceKey)
  }

  def receive(descriptor: HyfaasProxyDescriptor): HyfaasProxyResult = {
    val source = objectPath(descriptor)
    val payload =
      if (Files.exists(source)) Files.readAllBytes(source)
      else throw new IllegalStateException(s"missing HyFaaS remote payload: $source")
    val targetKey =
      HyfaasMemoryKey(descriptor.workflow, descriptor.activation, descriptor.targetStage, descriptor.partition)
    memory.write(targetKey, payload)
    HyfaasProxyResult(payload.length.toLong, source, targetKey)
  }

  def run(descriptor: HyfaasProxyDescriptor): HyfaasProxyResult =
    if (descriptor.sender) send(descriptor) else receive(descriptor)

  private def objectPath(descriptor: HyfaasProxyDescriptor): Path =
    storageRoot
      .resolve(HyfaasSharedMemory.safe(descriptor.workflow))
      .resolve(HyfaasSharedMemory.safe(descriptor.activation))
      .resolve(HyfaasSharedMemory.safe(descriptor.objectName))
      .resolve(s"${descriptor.partition}.bin")
}

object HyfaasProxyRuntime {
  val DefaultStorageRoot: Path = Paths.get(sys.env.getOrElse("HYFAAS_OBJECT_STORE", "/mnt/hyfaas-object-store"))

  def default: HyfaasProxyRuntime = new HyfaasProxyRuntime(HyfaasSharedMemory.default, DefaultStorageRoot)
}
