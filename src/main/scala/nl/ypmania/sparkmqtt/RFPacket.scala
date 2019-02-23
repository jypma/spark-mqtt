package nl.ypmania.sparkmqtt

import akka.util.ByteString
import com.github.os72.protobuf.dynamic.{ DynamicSchema, MessageDefinition }
import com.google.protobuf.{ DynamicMessage }
import scala.util.Try
import nl.ypmania.spark.data.Messages.Packet
import scalapb.{ GeneratedMessage, GeneratedMessageCompanion, Message }

object RFPacket {
  import Protobuf._
  val MaybePacket = new MaybeProtobuf(Packet)
}

object Protobuf {
  case class MaybeProtobuf[A <: GeneratedMessage with Message[A]](t: GeneratedMessageCompanion[A]) {
    def unapply(bytes: ByteString): Option[A] = Try(t.parseFrom(bytes.toArray)).toOption
  }

  implicit class ProtoExt[A <: GeneratedMessage with Message[A]](a: A) {
    def toByteString = ByteString(a.toByteArray)
  }
}
