package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging }
import akka.http.scaladsl.model.Uri.Path
import java.nio.charset.StandardCharsets

import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MQTTActor extends Actor with ActorLogging {
  import MQTTActor._

  val client = {
    val host = context.system.settings.config.getString("mqtt.host")
    val port = context.system.settings.config.getInt("mqtt.port")
    val publisherId = context.system.settings.config.getString("mqtt.publisher-id")

    val c = new MqttClient(s"tcp://${host}:${port}", publisherId, new MemoryPersistence())
    val options = new MqttConnectOptions()
    options.setAutomaticReconnect(true)
    options.setCleanSession(true)
    options.setConnectionTimeout(10)
    c.connect(options)
    c
  }

  def receive = {
    case Message(topic, data, retained) =>
      val msg = new MqttMessage(data.getBytes(StandardCharsets.UTF_8))
      msg.setQos(0)
      msg.setRetained(retained)
      try {
        log.debug("Publishing to {}", topic.toString())
        client.publish(topic.toString(), msg)
      } catch {
        case x: MqttException =>
          log.error(x, "Could not send MQTT message. Message dropped.")
      }
  }
}

object MQTTActor {
  case class Message(topic: Path, data: String, retained: Boolean = false)
}
