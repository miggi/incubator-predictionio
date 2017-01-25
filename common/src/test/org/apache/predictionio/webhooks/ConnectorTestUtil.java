package org.apache.predictionio.webhooks;

/** TestUtil for JsonConnector */
trait ConnectorTestUtil extends Specification {

  implicit val formats = DefaultFormats

  def check(connector: JsonConnector, original: String, event: String): Result = {
    val originalJson = parse(original).asInstanceOf[JObject]
    val eventJson = parse(event).asInstanceOf[JObject]
    // write and parse back to discard any JNothing field
    val result = parse(write(connector.toEventJson(originalJson))).asInstanceOf[JObject]
    result.obj must containTheSameElementsAs(eventJson.obj)
  }

  def check(connector: FormConnector, original: Map[String, String], event: String) = {

    val eventJson = parse(event).asInstanceOf[JObject]
    // write and parse back to discard any JNothing field
    val result = parse(write(connector.toEventJson(original))).asInstanceOf[JObject]

    result.obj must containTheSameElementsAs(eventJson.obj)
  }
}
