package frankenpaxos.unreplicated

import collection.mutable
import com.google.protobuf.ByteString
import frankenpaxos.Actor
import frankenpaxos.Chan
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.PrometheusCollectors
import frankenpaxos.roundsystem.RoundSystem
import scala.concurrent.Future
import scala.concurrent.Promise
 import scala.util.Random

 object ClientInboundSerializer extends ProtoSerializer[ClientInbound] {
  type A = ClientInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

 object Client {
  val serializer = ClientInboundSerializer
}

 case class ClientOptions()

 object ClientOptions {
  val default = ClientOptions()
}

 class ClientMetrics(collectors: Collectors) {
  val requestsTotal: Counter = collectors.counter
    .build()
    .name("unreplicated_client_requests_total")
    .help("Total number of client requests sent.")
    .register()

  val responsesTotal: Counter = collectors.counter
    .build()
    .name("unreplicated_client_responses_total")
    .help("Total number of successful client responses received.")
    .register()

  val unpendingResponsesTotal: Counter = collectors.counter
    .build()
    .name("unreplicated_client_unpending_responses_total")
    .help("Total number of unpending client responses received.")
    .register()
}

 class Client[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    serverAddress: Transport#Address,
    options: ClientOptions = ClientOptions.default,
    metrics: ClientMetrics = new ClientMetrics(PrometheusCollectors)
) extends Actor(address, transport, logger) {

  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = ClientInbound
  override val serializer = ClientInboundSerializer

  type CommandId = Int

  // Clients can only propose one request at a time (per pseudonym), so if
  // there is a pending command, no other command can be proposed. This
  // restriction hurts performance a bit---a single client cannot pipeline
  // requests---but it simplifies the design of the protocol.
    case class PendingCommand(
      commandId: CommandId,
      command: Array[Byte],
      result: Promise[Array[Byte]]
  )

  // Fields ////////////////////////////////////////////////////////////////////
  // Server channel.
  private val server: Chan[Server[Transport]] =
    chan[Server[Transport]](serverAddress, Server.serializer)

     protected var nextId: Int = 0

     protected var pendingCommands = mutable.Map[CommandId, PendingCommand]()

  // Helpers ///////////////////////////////////////////////////////////////////
  private def proposeImpl(
      command: Array[Byte],
      promise: Promise[Array[Byte]]
  ): Unit = {
    val id = nextId
    nextId += 1
    pendingCommands(id) =
      PendingCommand(commandId = id, command = command, result = promise)
    server.send(
      ServerInbound().withClientRequest(
        ClientRequest(commandId = id, command = ByteString.copyFrom(command))
      )
    )
    metrics.requestsTotal.inc()
  }

  // Handlers //////////////////////////////////////////////////////////////////
  override def receive(src: Transport#Address, inbound: InboundMessage) = {
    import ClientInbound.Request
    inbound.request match {
      case Request.ClientReply(r) => handleClientReply(src, r)
      case Request.Empty =>
        logger.fatal("Empty ClientInbound encountered.")
    }
  }

  private def handleClientReply(
      src: Transport#Address,
      clientReply: ClientReply
  ): Unit = {
    pendingCommands.get(clientReply.commandId) match {
      case Some(pendingCommand: PendingCommand) =>
        pendingCommand.result.success(clientReply.result.toByteArray())
        pendingCommands -= clientReply.commandId
        metrics.responsesTotal.inc()

      case None =>
        logger.debug(
          s"A client received a ClientReply for an unpending command with " +
            s"id ${clientReply.commandId}."
        )
        metrics.unpendingResponsesTotal.inc()
    }
  }

  // Interface /////////////////////////////////////////////////////////////////
  def propose(command: Array[Byte]): Future[Array[Byte]] = {
    val promise = Promise[Array[Byte]]()
    transport.executionContext.execute(
      () => proposeImpl(command, promise)
    )
    promise.future
  }

  def propose(command: String): Future[String] = {
    val promise = Promise[Array[Byte]]()
    transport.executionContext.execute(
      () => proposeImpl(command.getBytes(), promise)
    )
    promise.future.map(new String(_))(
      concurrent.ExecutionContext.Implicits.global
    )
  }
}
