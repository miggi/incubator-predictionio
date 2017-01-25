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

package org.apache.predictionio.tools.commands

import org.apache.predictionio.data.storage
import org.apache.predictionio.{EitherLogging, Storage, tools}
import org.apache.predictionio.ReturnTypes._
import grizzled.slf4j.Logging
import org.apache.predictionio
import org.apache.predictionio.storage.AccessKey

import scala.util.Either

object AccessKey extends EitherLogging {

  def create(
    appName: String,
    key: String,
    events: Seq[String]): Expected[AccessKey] = {

    val apps = Storage.getMetaDataApps
    apps.getByName(appName) map { app =>
      val accessKeys = predictionio.Storage.getMetaDataAccessKeys
      val newKey = AccessKey(
        key = key,
        appid = app.id,
        events = events)
      accessKeys.insert(newKey) map { k =>
        info(s"Created new access key: ${k}")
        Right(newKey.copy(key = k))
      } getOrElse {
        logAndFail(s"Unable to create new access key.")
      }
    } getOrElse {
      logAndFail(s"App ${appName} does not exist. Aborting.")
    }
  }

  def list(app: Option[String]): Expected[Seq[AccessKey]] =
    app map { appName =>
      App.show(appName).right map { appChansPair => appChansPair._1.keys }
    } getOrElse {
      Right(predictionio.Storage.getMetaDataAccessKeys.getAll)
    }

  def delete(key: String): MaybeError = {
    try {
      predictionio.Storage.getMetaDataAccessKeys.delete(key)
      logAndSucceed(s"Deleted access key ${key}.")
    } catch {
      case e: Exception =>
        error(s"Error deleting access key ${key}.", e)
        Left(s"Error deleting access key ${key}.")
    }
  }
}
