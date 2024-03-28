package frankenpaxos.craq

case class Config[Transport <: frankenpaxos.Transport[Transport]](
    f: Int,
    chainNodeAddresses: Seq[Transport#Address]
) {
  val numChainNodes = chainNodeAddresses.size

  def checkValid(): Unit = {
    require(numChainNodes >= f + 1,
            s"Number of chain nodes must be >= f + 1 ($f + 1). " +
              s"It's $numChainNodes.")
  }
}
