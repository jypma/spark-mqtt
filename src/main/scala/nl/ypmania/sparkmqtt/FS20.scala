package nl.ypmania.sparkmqtt

import akka.util.ByteString

object FS20 {
  case class Packet(address: Address, command: Command, timerSeconds: Int = 0) {
    def toByteString: ByteString = {
      val checksum = ((6 + address.houseHigh + address.houseLow + address.device + command.value) & 0xFF).toByte
      ByteString.fromInts(address.houseHigh, address.houseLow, address.device, command.value, checksum)
    }
  }

  case class Address(houseHigh: Byte, houseLow: Byte, device: Byte) {
    def houseCode: String = s"${code(houseHigh)}${code(houseLow)}"
    def deviceCode: String = s"${code(device)}"
    override def toString = s"${deviceCode}@${houseCode}"
  }

  case class Command(value: Byte) extends AnyVal {
    import Command._
    override def toString = names.getOrElse(value, value.toString)
  }
  object Command {
    val names = Map(
      0 -> "OFF",
      1 -> "DIM_1",
      2 -> "DIM_2",
      3 -> "DIM_3",
      4 -> "DIM_4",
      5 -> "DIM_5",
      6 -> "DIM_6",
      7 -> "DIM_7",
      8 -> "DIM_8",
      9 -> "DIM_9",
      10 -> "DIM_10",
      11 -> "DIM_11",
      12 -> "DIM_12",
      13 -> "DIM_13",
      14 -> "DIM_14",
      15 -> "DIM_15",
      16 -> "ON_FULL",
      17 -> "ON_PREVIOUS",
      18 -> "TOGGLE",
      19 -> "DIM_UP",
      20 -> "DIM_DOWN",
      21 -> "DIM_UP_DOWN",
      22 -> "TIMER_SET",
      23 -> "SEND_STATUS",
      24 -> "TIMED_OFF",
      25 -> "TIMED_ON_FULL",
      26 -> "TIMED_ON_PREVIOUS",
      27 -> "RESET").map { case (k,v) => (k.toByte, v) }
    val values = names.map(_.swap)
    object AsByteString {
      def unapply(s: ByteString): Option[Command] = values.get(s.utf8String.toUpperCase).map(i => Command(i))
    }
  }

  object Packet {
    def unapply(data: ByteString): Option[Packet] = data match {
      case d if d.length == 5 =>
        Some(Packet(Address(data(0), data(1), data(2)), Command(data(3)), 0))
      case d if d.length == 6 =>
        val t = data(4)
        val timerSeconds = (Math.pow(2, ((t >>> 4) & 0x0F)) * (t & 0x0F) * 0.25f).toInt
        Some(Packet(Address(data(0), data(1), data(2)), Command(data(3)), timerSeconds))
      case _ =>
        None
    }
  }

  private def code(i: Byte): String = {
    var result = 0;
    result += ((i & 3) + 1);
    result += (((i >> 2) & 3) + 1) * 10;
    result += (((i >> 4) & 3) + 1) * 100;
    result += (((i >> 6) & 3) + 1) * 1000;
    result.toString()
  }

  def fromCode(s: String): Byte = {
    var i = s.toInt
    var result = 0;
    result += ((i / 1000) - 1) * 64;
    i %= 1000;
    result += ((i / 100) - 1) * 16;
    i %= 100;
    result += ((i / 10) - 1) * 4;
    i %= 10;
    result += (i - 1);
    result.toByte
  }
}
