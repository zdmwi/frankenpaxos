package frankenpaxos.batchedunreplicated

case class Config[Transport <: frankenpaxos.Transport[Transport]](
    batcherAddresses: Seq[Transport#Address],
    serverAddress: Transport#Address,
    proxyServerAddresses: Seq[Transport#Address]
)
