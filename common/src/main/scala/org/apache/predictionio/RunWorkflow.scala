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

case class WorkflowArgs(
  batch: String = "",
  variantJson: File = new File("engine.json"),
  verbosity: Int = 0,
  engineParamsKey: Option[String] = None,
  engineFactory: Option[String] = None,
  evaluation: Option[String] = None,
  engineParamsGenerator: Option[String] = None,
  stopAfterRead: Boolean = false,
  stopAfterPrepare: Boolean = false,
  skipSanityCheck: Boolean = false,
  jsonExtractor: JsonExtractorOption = JsonExtractorOption.Both)

object RunWorkflow extends Logging {

  def runWorkflow(
    wa: WorkflowArgs,
    sa: SparkArgs,
    em: EngineManifest,
    pioHome: String,
    verbose: Boolean = false): Expected[(Process, () => Unit)] = {

    val jarFiles = em.files.map(new URI(_))
    val args = Seq(
      "--engine-id",
      em.id,
      "--engine-version",
      em.version,
      "--engine-variant",
      wa.variantJson.toURI.toString,
      "--verbosity",
      wa.verbosity.toString) ++
      wa.engineFactory.map(
        x => Seq("--engine-factory", x)).getOrElse(Seq()) ++
      wa.engineParamsKey.map(
        x => Seq("--engine-params-key", x)).getOrElse(Seq()) ++
      (if (wa.batch != "") Seq("--batch", wa.batch) else Seq()) ++
      (if (verbose) Seq("--verbose") else Seq()) ++
      (if (wa.skipSanityCheck) Seq("--skip-sanity-check") else Seq()) ++
      (if (wa.stopAfterRead) Seq("--stop-after-read") else Seq()) ++
      (if (wa.stopAfterPrepare) {
        Seq("--stop-after-prepare")
      } else {
        Seq()
      }) ++
      wa.evaluation.map(x => Seq("--evaluation-class", x)).
        getOrElse(Seq()) ++
      // If engineParamsGenerator is specified, it overrides the evaluation.
      wa.engineParamsGenerator.orElse(wa.evaluation)
        .map(x => Seq("--engine-params-generator-class", x))
        .getOrElse(Seq()) ++
      (if (wa.batch != "") Seq("--batch", wa.batch) else Seq()) ++
      Seq("--json-extractor", wa.jsonExtractor.toString)

    Runner.runOnSpark(
      "org.apache.predictionio.workflow.CreateWorkflow",
      args,
      sa,
      jarFiles,
      pioHome,
      verbose)
  }
}
