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

// import org.apache.predictionio.tools.BuildInfo
import grizzled.slf4j.Logging
import org.apache.predictionio.ReturnTypes.Expected


object ReturnTypes {
  sealed case class Ok()

  type MaybeError = Either[String, Ok]
  type Expected[T] = Either[String, T]

  val Success: MaybeError = Right(Ok())
}

trait EitherLogging extends Logging {
  import ReturnTypes._

  protected def logAndFail[T](msg: => String): Expected[T] = {
    error(msg)
    Left(msg)
  }

  protected def logOnFail[T](msg: => String, t: => Throwable): Expected[T] = {
    error(msg, t)
    Left(msg)
  }

  protected def logAndReturn[T](value: T, msg: => Any): Expected[T] = {
    info(msg)
    Right(value)
  }

  protected def logAndSucceed(msg: => Any): MaybeError = {
    info(msg)
    Success
  }
}

object Common extends EitherLogging {

  def getSparkHome(sparkHome: Option[String]): String = {
    sparkHome getOrElse {
      sys.env.getOrElse("SPARK_HOME", ".")
    }
  }

  def versionNoPatch(fullVersion: String): String = {
    val v = """^(\d+\.\d+)""".r
    val versionNoPatch = for {
      v(np) <- v findFirstIn fullVersion
    } yield np
    versionNoPatch.getOrElse(fullVersion)
  }

  def jarFilesForScala: Array[File] = {
    def recursiveListFiles(f: File): Array[File] = {
      Option(f.listFiles) map { these =>
        these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles)
      } getOrElse Array[File]()
    }
    def jarFilesForScalaFilter(jars: Array[File]): Array[File] =
      jars.filterNot { f =>
        f.getName.toLowerCase.endsWith("-javadoc.jar") ||
        f.getName.toLowerCase.endsWith("-sources.jar")
      }
    def jarFilesAt(path: File): Array[File] = recursiveListFiles(path) filter {
      _.getName.toLowerCase.endsWith(".jar")
    }
    val libFiles = jarFilesForScalaFilter(jarFilesAt(new File("lib")))
//    val scalaVersionNoPatch = Common.versionNoPatch(BuildInfo.scalaVersion)
    val scalaVersionNoPatch = Common.versionNoPatch("2.11.5")
    val targetSbtFiles = jarFilesForScalaFilter(jarFilesAt(new File("target" +
      File.separator + s"scala-$scalaVersionNoPatch")))

    val targetMvnFiles = jarFilesForScalaFilter(jarFilesAt(new File("target")))

    // Use libFiles is target is empty.
    if (targetSbtFiles.length > 0) {
      targetSbtFiles
    }
    else if (targetMvnFiles.length > 0) {
      targetMvnFiles
    }
    else libFiles
  }

  def coreAssembly(pioHome: String): Expected[File] = {
//    val core = s"pio-assembly-${BuildInfo.version}.jar"
    val core = s"pio-assembly-2.0.0.jar"
    val coreDir =
      if (new File(pioHome + File.separator + "RELEASE").exists) {
        new File(pioHome + File.separator + "lib")
      } else {
        new File(pioHome + File.separator + "assembly")
      }
    val coreFile = new File(coreDir, core)
    if (coreFile.exists) {
      Right(coreFile)
    } else {
      logAndFail(s"PredictionIO Core Assembly (${coreFile.getCanonicalPath}) does " +
        "not exist. Aborting.")
    }
  }
}
