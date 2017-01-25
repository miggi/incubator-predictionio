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


package org.apache.predictionio.data.storage

import org.apache.predictionio.annotation.DeveloperApi
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

case class Task(
                 entityType: String,
                 arguments: DataMap = DataMap(), // default empty
                 directory: String, // default empty
                 creationTime: DateTime = DateTime.now
               ) {
}

@DeveloperApi
object TaskValidation {
  val defaultTimeZone = DateTimeZone.UTC

  def validate(t: Task): Unit = {

    require(!t.entityType.isEmpty, "entityType must not be empty.")
    require(!t.directory.isEmpty, "directory must not be empty.")
  }

  val entityTypes: Set[String] = Set("build", "train", "deploy", "eval")

  // TODO: validate
  def isBuiltinEntityTypes(name: String): Boolean = entityTypes.contains(name)

}
