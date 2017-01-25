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

package org.apache.predictionio.data.api

import org.apache.predictionio.data.storage.{DataMap, Task}
import akka.actor.Actor
import akka.event.Logging
import org.apache.predictionio.tools.{ServerArgs, SparkArgs, WorkflowArgs}
import org.apache.predictionio.tools.commands.{Engine, EngineArgs}
import org.joda.time.DateTime

import scala.sys.process.Process

class TaskActor extends Actor {
  implicit val system = context.system
  val log = Logging(system, this)

  sealed case class Ok()

  type MaybeError = Either[String, Ok]
  type Expected[T] = Either[String, T]

  val Success: MaybeError = Right(Ok())

  def process(entityType: String, args: DataMap, directory: String, creationTime: DateTime) {
    // If the current hour is different from the stats start time, we create
    // another stats instance, and move the current to prev.

    //    hourlyStats.update(appId, statusCode, event)
    //    longLiveStats.update(appId, statusCode, event)

    entityType match {
      case "deploy" =>
        Pio.deploy(
          ca.engine,
          ca.engineInstanceId,
          ServerArgs(
            ca.deploy,
            ca.eventServer,
            ca.workflow.batch,
            ca.accessKey.accessKey,
            ca.workflow.variantJson,
            ca.workflow.jsonExtractor),
          ca.spark,
          ca.pioHome.get,
          ca.verbose)

    }
  }

  def train(
             ea: EngineArgs,
             wa: WorkflowArgs,
             sa: SparkArgs,
             pioHome: String,
             verbose: Boolean = false): Int =
    processAwaitAndClean(Engine.train(ea, wa, sa, pioHome, verbose))

  def eval(
            ea: EngineArgs,
            wa: WorkflowArgs,
            sa: SparkArgs,
            pioHome: String,
            verbose: Boolean = false): Int =
    processAwaitAndClean(Engine.train(ea, wa, sa, pioHome, verbose))

  def deploy(
              ea: EngineArgs,
              engineInstanceId: Option[String],
              serverArgs: ServerArgs,
              sparkArgs: SparkArgs,
              pioHome: String,
              verbose: Boolean = false): Int =
    processAwaitAndClean(Engine.deploy(
      ea, engineInstanceId, serverArgs, sparkArgs, pioHome, verbose))


  private def processAwaitAndClean(maybeProc: Expected[(Process, () => Unit)]) = {
    maybeProc match {
      case Left(_) => 1

      case Right((proc, cleanup)) =>
        Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
          def run(): Unit = {
            cleanup()
            proc.destroy()
          }
        }))
        val returnVal = proc.exitValue()
        cleanup()
        returnVal
    }
  }


  def receive: Actor.Receive = {
    case Task(entityType, args, directory, time) =>
      process(entityType, args, directory, time)
    case _ => log.error("Unsupported message.")
  }
}
