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

package org.apache.predictionio.api

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.event.Logging
import akka.io.IO
import akka.util.Timeout
import org.apache.predictionio.Storage
import org.apache.predictionio.storage.{AccessKeys, Channels, LEvents}
import org.json4s.{DefaultFormats, Formats}
import spray.can.Http
import spray.http.MediaTypes
import spray.httpx.Json4sSupport
import spray.routing.authentication.Authentication
import spray.routing.{AuthenticationFailedRejection, HttpServiceActor, RequestContext, Route}
import sun.misc.BASE64Decoder

import scala.concurrent.{ExecutionContext, Future}

case class TaskServerConfig(
                               ip: String = "localhost",
                               port: Int = 7770
                             )

class DeployServiceActor(
                          val accessKeysClient: AccessKeys,
                          val channelsClient: Channels,
                          val config: TaskServerConfig) extends HttpServiceActor {

  object Json4sProtocol extends Json4sSupport {
    implicit def json4sFormats: Formats = DefaultFormats +
      new EventJson4sSupport.APISerializer +
      new BatchEventsJson4sSupport.APISerializer +
      // NOTE: don't use Json4s JodaTimeSerializers since it has issues,
      // some format not converted, or timezone not correct
      new DateTimeJson4sSupport.Serializer
  }

  val logger = Logging(context.system, this)

  implicit def executionContext: ExecutionContext = context.dispatcher

  implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  val rejectionHandler = Common.rejectionHandler

  val jsonPath = """(.+)\.json$""".r

  private lazy val base64Decoder = new BASE64Decoder

  case class AuthData(appId: Int, channelId: Option[Int], events: Seq[String])

  /* with accessKey in query/header, return appId if succeed */
  def withAccessKey: RequestContext => Future[Authentication[AuthData]] = {
    ctx: RequestContext =>
      val accessKeyParamOpt = ctx.request.uri.query.get("accessKey")
      val channelParamOpt = ctx.request.uri.query.get("channel")
      Future {
        // with accessKey in query, return appId if succeed
        accessKeyParamOpt.map { accessKeyParam =>
          accessKeysClient.get(accessKeyParam).map { k =>
            channelParamOpt.map { ch =>
              val channelMap =
                channelsClient.getByAppid(k.appid)
                  .map(c => (c.name, c.id)).toMap
              if (channelMap.contains(ch)) {
                Right(AuthData(k.appid, Some(channelMap(ch)), k.events))
              } else {
                Left(ChannelRejection(s"Invalid channel '$ch'."))
              }
            }.getOrElse{
              Right(AuthData(k.appid, None, k.events))
            }
          }.getOrElse(FailedAuth)
        }.getOrElse {
          // with accessKey in header, return appId if succeed
          ctx.request.headers.find(_.name == "Authorization").map { authHeader =>
            authHeader.value.split("Basic ") match {
              case Array(_, value) =>
                val appAccessKey =
                  new String(base64Decoder.decodeBuffer(value)).trim.split(":")(0)
                accessKeysClient.get(appAccessKey) match {
                  case Some(k) => Right(AuthData(k.appid, None, k.events))
                  case None => FailedAuth
                }

              case _ => FailedAuth
            }
          }.getOrElse(MissedAuth)
        }
      }
  }

  private val FailedAuth = Left(
    AuthenticationFailedRejection(
      AuthenticationFailedRejection.CredentialsRejected, List()
    )
  )

  private val MissedAuth = Left(
    AuthenticationFailedRejection(
      AuthenticationFailedRejection.CredentialsMissing, List()
    )
  )

  lazy val statsActorRef = actorRefFactory.actorSelection("/user/StatsActor")
  lazy val pluginsActorRef = actorRefFactory.actorSelection("/user/PluginsActor")

  val route: Route =
    pathSingleSlash {
      import Json4sProtocol._

      get {
        respondWithMediaType(MediaTypes.`application/json`) {
          complete(Map("status" -> "alive"))
        }
      }
    } ~
      path("build" / jsonPath) { web =>
        import Json4sProtocol._

//        post {
//          handleExceptions(Common.exceptionHandler) {
//            handleRejections(rejectionHandler) {
//              authenticate(withAccessKey) { authData =>
//                val appId = authData.appId
//                val channelId = authData.channelId
//                val events = authData.events
//                entity(as[Task]) { task =>
//                  complete {
//
//                      val data = eventClient.futureInsert(event, appId, channelId).map { id => }
////                        pluginsActorRef ! EventInfo(
////                          appId = appId,
////                          channelId = channelId,
////                          event = event)
////                        val result = (StatusCodes.Created, Map("eventId" -> s"${id}"))
////                        if (config.stats) {
////                          statsActorRef ! Bookkeeping(appId, result._1, event)
////                        }
////                        result
////                      }
//                      data
//
//                  }
//                }
//              }
//            }
//          }
//        } ~
          get {
            handleExceptions(Common.exceptionHandler) {
              handleRejections(rejectionHandler) {
                authenticate(withAccessKey) { authData =>
                  val appId = authData.appId
                  val channelId = authData.channelId
                  respondWithMediaType(MediaTypes.`application/json`) {
                    complete {
                      Webhooks.getJson(
                        appId = appId,
                        channelId = channelId,
                        web = web,
                        log = logger)
                    }
                  }
                }
              }
            }
          }
      }
}

object TaskServer {
  def createTaskServer(config: TaskServerConfig): ActorSystem = {
    implicit val system = ActorSystem("TaskServerSystem")

    val accessKeysClient = Storage.getMetaDataAccessKeys()
    val channelsClient = Storage.getMetaDataChannels()

    val serverActor = system.actorOf(
      Props(
        classOf[DeployServerActor],
        accessKeysClient,
        channelsClient,
        config),
      "DeployServerActor"
    )
    serverActor ! StartServer(config.ip, config.port)
    system
  }
}

class DeployServerActor(
                        val eventClient: LEvents,
                        val accessKeysClient: AccessKeys,
                        val channelsClient: Channels,
                        val config: TaskServerConfig) extends Actor with ActorLogging {
  val child = context.actorOf(
    Props(classOf[DeployServiceActor],
      eventClient,
      accessKeysClient,
      channelsClient,
      config),
    "DeployServiceActor")
  implicit val system = context.system

  def receive: Actor.Receive = {
    case StartServer(host, portNum) => {
      IO(Http) ! Http.Bind(child, interface = host, port = portNum)
    }
    case m: Http.Bound => log.info("Bound received. DeployServer is ready.")
    case m: Http.CommandFailed => log.error("Command failed.")
    case _ => log.error("Unknown message.")
  }
}