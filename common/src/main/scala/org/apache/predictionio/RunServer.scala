/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.predictionio

import java.io.File
import java.net.URI

import grizzled.slf4j.Logging
import org.apache.predictionio.JsonExtractorOption.JsonExtractorOption
import org.apache.predictionio.ReturnTypes._

import scala.sys.process._

case class DeployArgs(
  ip: String = "0.0.0.0",
  port: Int = 8000,
  logUrl: Option[String] = None,
  logPrefix: Option[String] = None)

case class EventServerArgs(
  enabled: Boolean = false,
  ip: String = "0.0.0.0",
  port: Int = 7070,
  stats: Boolean = false)

case class TaskServerArgs(
                            enabled: Boolean = false,
                            ip: String = "0.0.0.0",
                            port: Int = 7770
                           )
case class ServerArgs(
                       deploy: DeployArgs = DeployArgs(),
                       eventServer: EventServerArgs = EventServerArgs(),
                       batch: String = "",
                       accessKey: String = "",
                       variantJson: File = new File("engine.json"),
                       jsonExtractor: JsonExtractorOption = JsonExtractorOption.Both)

object RunServer extends Logging {

  def runServer(
    engineInstanceId: String,
    serverArgs: ServerArgs,
    sparkArgs: SparkArgs,
    em: EngineManifest,
    pioHome: String,
    verbose: Boolean = false): Expected[(Process, () => Unit)] = {

    val jarFiles = em.files.map(new URI(_)) ++
      Option(new File(pioHome, "plugins").listFiles())
        .getOrElse(Array.empty[File]).map(_.toURI)
    val args = Seq(
      "--engineInstanceId",
      engineInstanceId,
      "--engine-variant",
      serverArgs.variantJson.toURI.toString,
      "--ip",
      serverArgs.deploy.ip,
      "--port",
      serverArgs.deploy.port.toString,
      "--event-server-ip",
      serverArgs.eventServer.ip,
      "--event-server-port",
      serverArgs.eventServer.port.toString) ++
      (if (serverArgs.accessKey != "") {
        Seq("--accesskey", serverArgs.accessKey)
      } else {
        Nil
      }) ++
      (if (serverArgs.eventServer.enabled) Seq("--feedback") else Nil) ++
      (if (serverArgs.batch != "") Seq("--batch", serverArgs.batch) else Nil) ++
      (if (verbose) Seq("--verbose") else Nil) ++
      serverArgs.deploy.logUrl.map(x => Seq("--log-url", x)).getOrElse(Nil) ++
      serverArgs.deploy.logPrefix.map(x => Seq("--log-prefix", x)).getOrElse(Nil) ++
      Seq("--json-extractor", serverArgs.jsonExtractor.toString)

    Runner.runOnSpark(
      "org.apache.predictionio.workflow.CreateServer",
      args, sparkArgs, jarFiles, pioHome, verbose)
  }
}
