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


package org.apache.predictionio.tools.console

import java.io.File
import java.net.URI

import grizzled.slf4j.Logging
import org.apache.predictionio.controller.Utils
import org.apache.predictionio.core.BuildInfo
import org.apache.predictionio.data.api.EventServer
import org.apache.predictionio.data.api.EventServerConfig
import org.apache.predictionio.data.storage
import org.apache.predictionio.data.storage.EngineManifest
import org.apache.predictionio.data.storage.EngineManifestSerializer
import org.apache.predictionio.tools.RegisterEngine
import org.apache.predictionio.tools.RunServer
import org.apache.predictionio.tools.RunWorkflow
import org.apache.predictionio.tools.Common
import org.apache.predictionio.tools.commands.{
  DashboardArgs, AdminServerArgs, ImportArgs, ExportArgs,
  BuildArgs, EngineArgs}
import org.apache.predictionio.tools.{
  EventServerArgs, SparkArgs, WorkflowArgs, ServerArgs, DeployArgs}
import org.apache.predictionio.tools.EventServerArgs
import org.apache.predictionio.tools.admin.AdminServer
import org.apache.predictionio.tools.admin.AdminServerConfig
import org.apache.predictionio.tools.dashboard.Dashboard
import org.apache.predictionio.tools.dashboard.DashboardConfig
import org.apache.predictionio.workflow.JsonExtractorOption
import org.apache.predictionio.workflow.JsonExtractorOption.JsonExtractorOption
import org.apache.predictionio.workflow.WorkflowUtils
import org.apache.predictionio.tools.commands
import org.apache.commons.io.FileUtils
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization.read
import org.json4s.native.Serialization.write
import semverfi._

import scala.collection.JavaConversions._
import scala.io.Source
import scala.sys.process._
import scala.util.Random
import scalaj.http.Http

case class ConsoleArgs(
  build: BuildArgs = BuildArgs(),
  app: AppArgs = AppArgs(),
  spark: SparkArgs = SparkArgs(),
  engine: EngineArgs = EngineArgs(),
  workflow: WorkflowArgs = WorkflowArgs(),
  accessKey: AccessKeyArgs = AccessKeyArgs(),
  deploy: DeployArgs = DeployArgs(),
  eventServer: EventServerArgs = EventServerArgs(),
  adminServer: AdminServerArgs = AdminServerArgs(),
  dashboard: DashboardArgs = DashboardArgs(),
  export: ExportArgs = ExportArgs(),
  imprt: ImportArgs = ImportArgs(),
  commands: Seq[String] = Seq(),
  metricsParamsJsonPath: Option[String] = None,
  paramsPath: String = "params",
  engineInstanceId: Option[String] = None,
  mainClass: Option[String] = None,
  driverPassThrough: Seq[String] = Seq(),
  pioHome: Option[String] = None,
  verbose: Boolean = false)

case class AppArgs(
  id: Option[Int] = None,
  name: String = "",
  channel: String = "",
  dataDeleteChannel: Option[String] = None,
  all: Boolean = false,
  force: Boolean = false,
  description: Option[String] = None)

case class AccessKeyArgs(
  accessKey: String = "",
  events: Seq[String] = Seq())

object Console extends Logging {
  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[ConsoleArgs]("pio") {
      override def showUsageOnError: Boolean = false
      head("PredictionIO Command Line Interface Console", BuildInfo.version)
      help("")
      note("Note that it is possible to supply pass-through arguments at\n" +
        "the end of the command by using a '--' separator, e.g.\n\n" +
        "pio train --params-path params -- --master spark://mycluster:7077\n" +
        "\nIn the example above, the '--master' argument will be passed to\n" +
        "underlying spark-submit command. Please refer to the usage section\n" +
        "for each command for more information.\n\n" +
        "The following options are common to all commands:\n")
      opt[String]("pio-home") action { (x, c) =>
        c.copy(pioHome = Some(x))
      } text("Root directory of a PredictionIO installation.\n" +
        "        Specify this if automatic discovery fail.")
      opt[String]("spark-home") action { (x, c) =>
        c.copy(spark = c.spark.copy(sparkHome = Some(x)))
      } text("Root directory of an Apache Spark installation.\n" +
        "        If not specified, will try to use the SPARK_HOME\n" +
        "        environmental variable. If this fails as well, default to\n" +
        "        current directory.")
      opt[String]("engine-id") abbr("ei") action { (x, c) =>
        c.copy(engine = c.engine.copy(engineId = Some(x)))
      } text("Specify an engine ID. Usually used by distributed deployment.")
      opt[String]("engine-version") abbr("ev") action { (x, c) =>
        c.copy(engine = c.engine.copy(engineVersion = Some(x)))
      } text("Specify an engine version. Usually used by distributed " +
        "deployment.")
      opt[File]("variant") abbr("v") action { (x, c) =>
        c.copy(workflow = c.workflow.copy(variantJson = x))
      }
      opt[File]("manifest") abbr("m") action { (x, c) =>
        c.copy(engine = c.engine.copy(manifestJson = x))
      }
      opt[File]("sbt") action { (x, c) =>
        c.copy(build = c.build.copy(sbt = Some(x)))
      } validate { x =>
        if (x.exists) {
          success
        } else {
          failure(s"${x.getCanonicalPath} does not exist.")
        }
      } text("Path to sbt. Default: sbt")
      opt[Unit]("verbose") action { (x, c) =>
        c.copy(verbose = true)
      }
      opt[Unit]("spark-kryo") abbr("sk") action { (x, c) =>
        c.copy(spark = c.spark.copy(sparkKryo = true))
      }
      opt[String]("scratch-uri") action { (x, c) =>
        c.copy(spark = c.spark.copy(scratchUri = Some(new URI(x))))
      }
      note("")
      cmd("version").
        text("Displays the version of this command line console.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "version")
        }
      note("")
      cmd("help").action { (_, c) =>
        c.copy(commands = c.commands :+ "help")
      } children(
        arg[String]("<command>") optional()
          action { (x, c) =>
            c.copy(commands = c.commands :+ x)
          }
        )
      note("")
      cmd("build").
        text("Build an engine at the current directory.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "build")
        } children(
          opt[String]("sbt-extra") action { (x, c) =>
            c.copy(build = c.build.copy(sbtExtra = Some(x)))
          } text("Extra command to pass to SBT when it builds your engine."),
          opt[Unit]("clean") action { (x, c) =>
            c.copy(build = c.build.copy(sbtClean = true))
          } text("Clean build."),
          opt[Unit]("no-asm") action { (x, c) =>
            c.copy(build = c.build.copy(sbtAssemblyPackageDependency = false))
          } text("Skip building external dependencies assembly."),
          opt[Unit]("uber-jar") action { (x, c) =>
            c.copy(build = c.build.copy(uberJar = true))
          },
          opt[Unit]("maven") action { (x, c) =>
            c.copy(build = c.build.copy(maven = true))
          }text("Maven build"),
          opt[Unit]("generate-pio-sbt") action { (x, c) =>
            c.copy(build = c.build.copy(forceGeneratePIOSbt = true))
          }
        )
      note("")
      cmd("unregister").
        text("Unregister an engine at the current directory.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "unregister")
        }
      note("")
      cmd("train").
        text("Kick off a training using an engine. This will produce an\n" +
          "engine instance. This command will pass all pass-through\n" +
          "arguments to its underlying spark-submit command.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "train")
        } children(
          opt[String]("batch") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(batch = x))
          } text("Batch label of the run."),
          opt[String]("params-path") action { (x, c) =>
            c.copy(paramsPath = x)
          } text("Directory to lookup parameters JSON files. Default: params"),
          opt[String]("metrics-params") abbr("mp") action { (x, c) =>
            c.copy(metricsParamsJsonPath = Some(x))
          } text("Metrics parameters JSON file. Will try to use\n" +
            "        metrics.json in the base path."),
          opt[Unit]("skip-sanity-check") abbr("ssc") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(skipSanityCheck = true))
          },
          opt[Unit]("stop-after-read") abbr("sar") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(stopAfterRead = true))
          },
          opt[Unit]("stop-after-prepare") abbr("sap") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(stopAfterPrepare = true))
          },
          opt[Unit]("uber-jar") action { (x, c) =>
            c.copy(build = c.build.copy(uberJar = true))
          },
          opt[Int]("verbosity") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(verbosity = x))
          },
          opt[String]("engine-factory") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(engineFactory = Some(x)))
          },
          opt[String]("engine-params-key") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(engineParamsKey = Some(x)))
          },
          opt[String]("json-extractor") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(jsonExtractor = JsonExtractorOption.withName(x)))
          } validate { x =>
              if (JsonExtractorOption.values.map(_.toString).contains(x)) {
                success
              } else {
                val validOptions = JsonExtractorOption.values.mkString("|")
                failure(s"$x is not a valid json-extractor option [$validOptions]")
              }
          }
        )
      note("")
      cmd("eval").
        text("Kick off an evaluation using an engine. This will produce an\n" +
          "engine instance. This command will pass all pass-through\n" +
          "arguments to its underlying spark-submit command.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "eval")
        } children(
          arg[String]("<evaluation-class>") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(evaluation = Some(x)))
          },
          arg[String]("[<engine-parameters-generator-class>]") optional() action { (x, c) =>
            c.copy(workflow = c.workflow.copy(engineParamsGenerator = Some(x)))
          } text("Optional engine parameters generator class, overriding the first argument"),
          opt[String]("batch") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(batch = x))
          } text("Batch label of the run."),
          opt[String]("json-extractor") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(jsonExtractor = JsonExtractorOption.withName(x)))
          } validate { x =>
            if (JsonExtractorOption.values.map(_.toString).contains(x)) {
              success
            } else {
              val validOptions = JsonExtractorOption.values.mkString("|")
              failure(s"$x is not a valid json-extractor option [$validOptions]")
            }
          }
        )
      note("")
      cmd("deploy").
        text("Deploy an engine instance as a prediction server. This\n" +
          "command will pass all pass-through arguments to its underlying\n" +
          "spark-submit command.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "deploy")
        } children(
          opt[String]("batch") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(batch = x))
          } text("Batch label of the deployment."),
          opt[String]("engine-instance-id") action { (x, c) =>
            c.copy(engineInstanceId = Some(x))
          } text("Engine instance ID."),
          opt[String]("ip") action { (x, c) =>
            c.copy(deploy = c.deploy.copy(ip = x))
          },
          opt[Int]("port") action { (x, c) =>
            c.copy(deploy = c.deploy.copy(port = x))
          } text("Port to bind to. Default: 8000"),
          opt[Unit]("feedback") action { (_, c) =>
            c.copy(eventServer = c.eventServer.copy(enabled = true))
          } text("Enable feedback loop to event server."),
          opt[String]("event-server-ip") action { (x, c) =>
            c.copy(eventServer = c.eventServer.copy(ip = x))
          },
          opt[Int]("event-server-port") action { (x, c) =>
            c.copy(eventServer = c.eventServer.copy(port = x))
          } text("Event server port. Default: 7070"),
          opt[Int]("admin-server-port") action { (x, c) =>
            c.copy(adminServer = c.adminServer.copy(port = x))
          } text("Admin server port. Default: 7071"),
          opt[String]("admin-server-ip") action { (x, c) =>
          c.copy(adminServer = c.adminServer.copy(ip = x))
          } text("Admin server IP. Default: localhost"),
          opt[String]("accesskey") action { (x, c) =>
            c.copy(accessKey = c.accessKey.copy(accessKey = x))
          } text("Access key of the App where feedback data will be stored."),
          opt[Unit]("uber-jar") action { (x, c) =>
            c.copy(build = c.build.copy(uberJar = true))
          },
          opt[String]("log-url") action { (x, c) =>
            c.copy(deploy = c.deploy.copy(logUrl = Some(x)))
          },
          opt[String]("log-prefix") action { (x, c) =>
            c.copy(deploy = c.deploy.copy(logPrefix = Some(x)))
          },
          opt[String]("json-extractor") action { (x, c) =>
            c.copy(workflow = c.workflow.copy(jsonExtractor = JsonExtractorOption.withName(x)))
          } validate { x =>
            if (JsonExtractorOption.values.map(_.toString).contains(x)) {
              success
            } else {
              val validOptions = JsonExtractorOption.values.mkString("|")
              failure(s"$x is not a valid json-extractor option [$validOptions]")
            }
          }
        )
      note("")
      cmd("undeploy").
        text("Undeploy an engine instance as a prediction server.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "undeploy")
        } children(
          opt[String]("ip") action { (x, c) =>
            c.copy(deploy = c.deploy.copy(ip = x))
          },
          opt[Int]("port") action { (x, c) =>
            c.copy(deploy = c.deploy.copy(port = x))
          } text("Port to unbind from. Default: 8000")
        )
      note("")
      cmd("dashboard").
        text("Launch a dashboard at the specific IP and port.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "dashboard")
        } children(
          opt[String]("ip") action { (x, c) =>
            c.copy(dashboard = c.dashboard.copy(ip = x))
          },
          opt[Int]("port") action { (x, c) =>
            c.copy(dashboard = c.dashboard.copy(port = x))
          } text("Port to bind to. Default: 9000")
        )
      note("")
      cmd("eventserver").
        text("Launch an Event Server at the specific IP and port.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "eventserver")
        } children(
          opt[String]("ip") action { (x, c) =>
            c.copy(eventServer = c.eventServer.copy(ip = x))
          },
          opt[Int]("port") action { (x, c) =>
            c.copy(eventServer = c.eventServer.copy(port = x))
          } text("Port to bind to. Default: 7070"),
          opt[Unit]("stats") action { (x, c) =>
            c.copy(eventServer = c.eventServer.copy(stats = true))
          }
        )
      cmd("adminserver").
        text("Launch an Admin Server at the specific IP and port.").
        action { (_, c) =>
        c.copy(commands = c.commands :+ "adminserver")
      } children(
        opt[String]("ip") action { (x, c) =>
          c.copy(adminServer = c.adminServer.copy(ip = x))
        } text("IP to bind to. Default: localhost"),
        opt[Int]("port") action { (x, c) =>
          c.copy(adminServer = c.adminServer.copy(port = x))
        } text("Port to bind to. Default: 7071")
        )
      note("")
      cmd("run").
        text("Launch a driver program. This command will pass all\n" +
          "pass-through arguments to its underlying spark-submit command.\n" +
          "In addition, it also supports a second level of pass-through\n" +
          "arguments to the driver program, e.g.\n" +
          "pio run -- --master spark://localhost:7077 -- --driver-arg foo").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "run")
        } children(
          arg[String]("<main class>") action { (x, c) =>
            c.copy(mainClass = Some(x))
          } text("Main class name of the driver program."),
          opt[String]("sbt-extra") action { (x, c) =>
            c.copy(build = c.build.copy(sbtExtra = Some(x)))
          } text("Extra command to pass to SBT when it builds your engine."),
          opt[Unit]("clean") action { (x, c) =>
            c.copy(build = c.build.copy(sbtClean = true))
          } text("Clean build."),
           opt[Unit]("maven") action { (x, c) =>
            c.copy(build = c.build.copy(maven = true))
          } text ("Maven build."),
          opt[Unit]("no-asm") action { (x, c) =>
            c.copy(build = c.build.copy(sbtAssemblyPackageDependency = false))
          } text("Skip building external dependencies assembly.")
        )
      note("")
      cmd("status").
        text("Displays status information about the PredictionIO system.").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "status")
        }
      note("")
      cmd("upgrade").
        text("No longer supported!").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "upgrade")
        }
      note("")
      cmd("app").
        text("Manage apps.\n").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "app")
        } children(
          cmd("new").
            text("Create a new app key to app ID mapping.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "new")
            } children(
              opt[Int]("id") action { (x, c) =>
                c.copy(app = c.app.copy(id = Some(x)))
              },
              opt[String]("description") action { (x, c) =>
                c.copy(app = c.app.copy(description = Some(x)))
              },
              opt[String]("access-key") action { (x, c) =>
                c.copy(accessKey = c.accessKey.copy(accessKey = x))
              },
              arg[String]("<name>") action { (x, c) =>
                c.copy(app = c.app.copy(name = x))
              }
            ),
          note(""),
          cmd("list").
            text("List all apps.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "list")
            },
          note(""),
          cmd("show").
            text("Show details of an app.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "show")
            } children (
              arg[String]("<name>") action { (x, c) =>
                c.copy(app = c.app.copy(name = x))
              } text("Name of the app to be shown.")
            ),
          note(""),
          cmd("delete").
            text("Delete an app.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "delete")
            } children(
              arg[String]("<name>") action { (x, c) =>
                c.copy(app = c.app.copy(name = x))
              } text("Name of the app to be deleted."),
              opt[Unit]("force") abbr("f") action { (x, c) =>
                c.copy(app = c.app.copy(force = true))
              } text("Delete an app without prompting for confirmation")
            ),
          note(""),
          cmd("data-delete").
            text("Delete data of an app").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "data-delete")
            } children(
              arg[String]("<name>") action { (x, c) =>
                c.copy(app = c.app.copy(name = x))
              } text("Name of the app whose data to be deleted."),
              opt[String]("channel") action { (x, c) =>
                c.copy(app = c.app.copy(dataDeleteChannel = Some(x)))
              } text("Name of channel whose data to be deleted."),
              opt[Unit]("all") action { (x, c) =>
                c.copy(app = c.app.copy(all = true))
              } text("Delete data of all channels including default"),
              opt[Unit]("force") abbr("f") action { (x, c) =>
                c.copy(app = c.app.copy(force = true))
              } text("Delete data of an app without prompting for confirmation")
            ),
          note(""),
          cmd("channel-new").
            text("Create a new channel for the app.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "channel-new")
            } children (
              arg[String]("<name>") action { (x, c) =>
                c.copy(app = c.app.copy(name = x))
              } text("App name."),
              arg[String]("<channel>") action { (x, c) =>
                c.copy(app = c.app.copy(channel = x))
              } text ("Channel name to be created.")
            ),
          note(""),
          cmd("channel-delete").
            text("Delete a channel of the app.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "channel-delete")
            } children (
              arg[String]("<name>") action { (x, c) =>
                c.copy(app = c.app.copy(name = x))
              } text("App name."),
              arg[String]("<channel>") action { (x, c) =>
                c.copy(app = c.app.copy(channel = x))
              } text ("Channel name to be deleted."),
              opt[Unit]("force") abbr("f") action { (x, c) =>
                c.copy(app = c.app.copy(force = true))
              } text("Delete a channel of the app without prompting for confirmation")
            )
        )
      note("")
      cmd("accesskey").
        text("Manage app access keys.\n").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "accesskey")
        } children(
          cmd("new").
            text("Add allowed event(s) to an access key.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "new")
            } children(
              opt[String]("key") action { (x, c) =>
                c.copy(accessKey = c.accessKey.copy(accessKey = x))
              },
              arg[String]("<app name>") action { (x, c) =>
                c.copy(app = c.app.copy(name = x))
              },
              arg[String]("[<event1> <event2> ...]") unbounded() optional()
                action { (x, c) =>
                  c.copy(accessKey = c.accessKey.copy(
                    events = c.accessKey.events :+ x))
                }
            ),
          cmd("list").
            text("List all access keys of an app.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "list")
            } children(
              arg[String]("<app name>") optional() action { (x, c) =>
                c.copy(app = c.app.copy(name = x))
              } text("App name.")
            ),
          note(""),
          cmd("delete").
            text("Delete an access key.").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "delete")
            } children(
              arg[String]("<access key>") action { (x, c) =>
                c.copy(accessKey = c.accessKey.copy(accessKey = x))
              } text("The access key to be deleted.")
            )
        )
      cmd("template").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "template")
        } children(
          cmd("get").
            text("No longer supported! Use git clone to download a template").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "get")
            },
          cmd("list").
            text("No longer supported! Use git to manage your templates").
            action { (_, c) =>
              c.copy(commands = c.commands :+ "list")
            }
        )
      cmd("export").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "export")
        } children(
          opt[Int]("appid") required() action { (x, c) =>
            c.copy(export = c.export.copy(appId = x))
          },
          opt[String]("output") required() action { (x, c) =>
            c.copy(export = c.export.copy(outputPath = x))
          },
          opt[String]("format") action { (x, c) =>
            c.copy(export = c.export.copy(format = x))
          },
          opt[String]("channel") action { (x, c) =>
            c.copy(export = c.export.copy(channel = Some(x)))
          }
        )
      cmd("import").
        action { (_, c) =>
          c.copy(commands = c.commands :+ "import")
        } children(
          opt[Int]("appid") required() action { (x, c) =>
            c.copy(imprt = c.imprt.copy(appId = x))
          },
          opt[String]("input") required() action { (x, c) =>
            c.copy(imprt = c.imprt.copy(inputPath = x))
          },
          opt[String]("channel") action { (x, c) =>
            c.copy(imprt = c.imprt.copy(channel = Some(x)))
          }
        )
    }

    val separatorIndex = args.indexWhere(_ == "--")
    val (consoleArgs, theRest) =
      if (separatorIndex == -1) {
        (args, Array[String]())
      } else {
        args.splitAt(separatorIndex)
      }
    val allPassThroughArgs = theRest.drop(1)
    val secondSepIdx = allPassThroughArgs.indexWhere(_ == "--")
    val (sparkPassThroughArgs, driverPassThroughArgs) =
      if (secondSepIdx == -1) {
        (allPassThroughArgs, Array[String]())
      } else {
        val t = allPassThroughArgs.splitAt(secondSepIdx)
        (t._1, t._2.drop(1))
      }

    parser.parse(consoleArgs, ConsoleArgs()) map { pca =>
      val ca = pca.copy(
        spark = pca.spark.copy(sparkPassThrough = sparkPassThroughArgs),
        driverPassThrough = driverPassThroughArgs)
      WorkflowUtils.modifyLogging(ca.verbose)
      val rv: Int = ca.commands match {
        case Seq("") =>
          System.err.println(help())
          1
        case Seq("version") =>
          Pio.version()
        case Seq("build") =>
          Pio.build(
            ca.build, ca.pioHome.get, ca.engine.manifestJson, ca.verbose)
        case Seq("unregister") =>
          Pio.unregister(ca.engine.manifestJson)
        case Seq("train") =>
          Pio.train(
            ca.engine, ca.workflow, ca.spark, ca.pioHome.get, ca.verbose)
        case Seq("eval") =>
          Pio.eval(
            ca.engine, ca.workflow, ca.spark, ca.pioHome.get, ca.verbose)
        case Seq("deploy") =>
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
        case Seq("undeploy") =>
          Pio.undeploy(ca.deploy)
        case Seq("dashboard") =>
          Pio.dashboard(ca.dashboard)
        case Seq("eventserver") =>
          Pio.eventserver(ca.eventServer)
        case Seq("adminserver") =>
          Pio.adminserver(ca.adminServer)
        case Seq("run") =>
          Pio.run(
            ca.mainClass.get,
            ca.driverPassThrough,
            ca.engine.manifestJson,
            ca.build,
            ca.spark,
            ca.pioHome.get,
            ca.verbose)
        case Seq("status") =>
          Pio.status(ca.pioHome, ca.spark.sparkHome)
        case Seq("upgrade") =>
          error("Upgrade is no longer supported")
          1
        case Seq("app", "new") =>
          Pio.App.create(
            ca.app.name, ca.app.id, ca.app.description, ca.accessKey.accessKey)
        case Seq("app", "list") =>
          Pio.App.list()
        case Seq("app", "show") =>
          Pio.App.show(ca.app.name)
        case Seq("app", "delete") =>
          Pio.App.delete(ca.app.name, ca.app.force)
        case Seq("app", "data-delete") =>
          Pio.App.dataDelete(
            ca.app.name, ca.app.dataDeleteChannel, ca.app.all, ca.app.force)
        case Seq("app", "channel-new") =>
          Pio.App.channelNew(ca.app.name, ca.app.channel)
        case Seq("app", "channel-delete") =>
          Pio.App.channelDelete(ca.app.name, ca.app.channel, ca.app.force)
        case Seq("accesskey", "new") =>
          Pio.AccessKey.create(
            ca.app.name, ca.accessKey.accessKey, ca.accessKey.events)
        case Seq("accesskey", "list") =>
         Pio.AccessKey.list(
           if (ca.app.name == "") None else Some(ca.app.name))
        case Seq("accesskey", "delete") =>
          Pio.AccessKey.delete(ca.accessKey.accessKey)
        case Seq("template", _) =>
          error("template commands are no longer supported.")
          error("Please use git to get and manage your templates.")
          1
        case Seq("export") =>
          Pio.export(ca.export, ca.spark, ca.pioHome.get)
        case Seq("import") =>
          Pio.imprt(ca.imprt, ca.spark, ca.pioHome.get)
        case _ =>
          System.err.println(help(ca.commands))
          1
      }
      sys.exit(rv)
    } getOrElse {
      val command = args.toSeq.filterNot(_.startsWith("--")).head
      System.err.println(help(Seq(command)))
      sys.exit(1)
    }
  }

  def help(commands: Seq[String] = Seq()): String = {
    if (commands.isEmpty) {
      mainHelp
    } else {
      val stripped =
        (if (commands.head == "help") commands.drop(1) else commands).
          mkString("-")
      helpText.getOrElse(stripped, s"Help is unavailable for ${stripped}.")
    }
  }

  val mainHelp = txt.main().toString

  val helpText = Map(
    "" -> mainHelp,
    "status" -> txt.status().toString,
    "upgrade" -> txt.upgrade().toString,
    "version" -> txt.version().toString,
    "template" -> txt.template().toString,
    "build" -> txt.build().toString,
    "train" -> txt.train().toString,
    "deploy" -> txt.deploy().toString,
    "eventserver" -> txt.eventserver().toString,
    "adminserver" -> txt.adminserver().toString,
    "app" -> txt.app().toString,
    "accesskey" -> txt.accesskey().toString,
    "import" -> txt.imprt().toString,
    "export" -> txt.export().toString,
    "run" -> txt.run().toString,
    "eval" -> txt.eval().toString,
    "dashboard" -> txt.dashboard().toString)
}
