package frankenpaxos

class ProtoSerializer[
    Proto <: scalapb.GeneratedMessage
](
    implicit companion: scalapb.GeneratedMessageCompanion[Proto]
) extends Serializer[Proto] {
  override def toBytes(x: Proto): Array[Byte] = x.toByteArray
  override def fromBytes(bytes: Array[Byte]): Proto = companion.parseFrom(bytes)
  override def toPrettyString(x: Proto): String = x.toProtoString
}
