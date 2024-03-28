package frankenpaxos.craq

import collection.mutable
import com.google.protobuf.ByteString
import frankenpaxos.Actor
import frankenpaxos.Chan
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.PrometheusCollectors
import frankenpaxos.monitoring.Summary
import frankenpaxos.roundsystem.RoundSystem
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Random

object ClientInboundSerializer extends ProtoSerializer[ClientInbound] {
  type A = ClientInbound
  override def toBytes(x: A): Array[Byte]       = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String     = super.toPrettyString(x)
}

object Client {
  val serializer = ClientInboundSerializer
}

case class ClientOptions(
    // Resend periods.
    resendClientRequestPeriod: java.time.Duration,
    resendReadRequestPeriod: java.time.Duration,
    // Clients flush write channels every flushWritesEveryN messages sent and
    // flush read channels every flushReadsEveryN messages sent.
    flushWritesEveryN: Int,
    flushReadsEveryN: Int,
    batchSize: Int,
    measureLatencies: Boolean
)

object ClientOptions {
  val default = ClientOptions(
    resendClientRequestPeriod = java.time.Duration.ofSeconds(10),
    resendReadRequestPeriod = java.time.Duration.ofSeconds(10),
    flushWritesEveryN = 1,
    flushReadsEveryN = 1,
    batchSize = 1,
    measureLatencies = true
  )
}

class ClientMetrics(collectors: Collectors) {
  val requestsTotal: Counter = collectors.counter
    .build()
    .name("craq_client_requests_total")
    .labelNames("type")
    .help("Total number of processed requests.")
    .register()

  val requestsLatency: Summary = collectors.summary
    .build()
    .name("craq_client_requests_latency")
    .labelNames("type")
    .help("Latency (in milliseconds) of a request.")
    .register()

  val clientRequestsSentTotal: Counter = collectors.counter
    .build()
    .name("craq_client_client_requests_sent_total")
    .help("Total number of client requests sent.")
    .register()

  val clientRepliesReceivedTotal: Counter = collectors.counter
    .build()
    .name("craq_client_replies_received_total")
    .help("Total number of successful replies responses received.")
    .register()

  val staleClientRepliesReceivedTotal: Counter = collectors.counter
    .build()
    .name("craq_client_stale_client_replies_received_total")
    .help("Total number of stale client replies received.")
    .register()

  val resendClientRequestTotal: Counter = collectors.counter
    .build()
    .name("craq_client_resend_client_request_total")
    .help("Total number of times a client resends a ClientRequest.")
    .register()

  val resendReadRequestsTotal: Counter = collectors.counter
    .build()
    .name("craq_client_resend_read_requests_total")
    .help("Total number of times a client resends a ReadRequest.")
    .register()

  val writeChannelsFlushedTotal: Counter = collectors.counter
    .build()
    .name("craq_client_write_channels_flushed_total")
    .help("Total number of times a client flushes its write channels.")
    .register()

  val readChannelsFlushedTotal: Counter = collectors.counter
    .build()
    .name("craq_client_read_channels_flushed_total")
    .help("Total number of times a client flushes its read channels.")
    .register()
}

class Client[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport],
    options: ClientOptions = ClientOptions.default,
    metrics: ClientMetrics = new ClientMetrics(PrometheusCollectors),
    seed: Long = System.currentTimeMillis()
) extends Actor(address, transport, logger) {
  config.checkValid()

  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = ClientInbound
  override val serializer = ClientInboundSerializer

  type Pseudonym     = Int
  type Id            = Int
  type AcceptorIndex = Int

  sealed trait State

  case class PendingWrite(
      id: Id,
      result: Promise[Unit],
      resendClientRequest: Transport#Timer
  ) extends State

  case class PendingRead(
      id: Id,
      result: Promise[String],
      resendReadRequest: Transport#Timer
  ) extends State

  class Ticker(fireEveryN: Int, thunk: () => Unit) {
    logger.checkGe(fireEveryN, 1)

    protected var x: Int = 0

    def tick() {
      x = x + 1
      if (x >= fireEveryN) {
        thunk()
        x = 0
      }
    }
  }

  // Fields ////////////////////////////////////////////////////////////////////
  // A random number generator instantiated from `seed`. This allows us to
  // perform deterministic randomized tests.
  private val rand = new Random(seed)

  // The client's address. A client includes its address in its commands so
  // that replicas know where to send back the reply.
  private val addressAsBytes: ByteString =
    ByteString.copyFrom(transport.addressSerializer.toBytes(address))

  private val chainNodes: Seq[Chan[ChainNode[Transport]]] =
    for (a <- config.chainNodeAddresses)
      yield chan[ChainNode[Transport]](a, ChainNode.serializer)

  private val headNode = chainNodes.head
  private val tailNode = chainNodes.last

  protected var growingBatch = mutable.Buffer[Write]()

  protected var growingReadBatch = mutable.Buffer[Read]()

  // Every request that a client sends is annotated with a monotonically
  // increasing client id. Here, we assume that if a client fails, it does not
  // recover, so we are safe to intialize the id to 0. If clients can recover
  // from failure, we would have to implement some mechanism to ensure that
  // client ids increase over time, even after crashes and restarts.
  protected var ids = mutable.Map[Pseudonym, Id]()

  // Clients can only propose one request at a time (per pseudonym), so if
  // there is a pending command, no other command can be proposed. This
  // restriction hurts performance a bit---a single client cannot pipeline
  // requests---but it simplifies the design of the protocol.
  protected var states = mutable.Map[Pseudonym, State]()

  protected val writeTicker: Option[Ticker] =
    if (options.flushWritesEveryN == 1) {
      None
    } else {
      Some(
        new Ticker(
          options.flushWritesEveryN,
          () => {
            headNode.flush()
            metrics.writeChannelsFlushedTotal.inc()
          }
        )
      )
    }

  protected val readTicker: Option[Ticker] =
    if (options.flushReadsEveryN == 1) {
      None
    } else {
      Some(
        new Ticker(
          options.flushReadsEveryN,
          () => {
            chainNodes.foreach(_.flush())
            metrics.readChannelsFlushedTotal.inc()
          }
        )
      )
    }

  // Helpers ///////////////////////////////////////////////////////////////////
  private def makeResendClientRequestTimer(
      clientRequest: Write
  ): Transport#Timer = {
    lazy val t: Transport#Timer = timer(
      s"resendClientRequest " +
        s"[pseudonym=${clientRequest.commandId.clientPseudonym}; " +
        s"id=${clientRequest.commandId.clientId}]",
      options.resendClientRequestPeriod,
      () => {
        sendClientRequest(clientRequest, forceFlush = true)
        metrics.resendClientRequestTotal.inc()
        t.start()
      }
    )
    t.start()
    t
  }

  private def makeResendReadRequestTimer(
      pseudonym: Pseudonym,
      id: Id,
      readRequest: Read
  ): Transport#Timer = {
    lazy val t: Transport#Timer = timer(
      s"resendReadRequest [pseudonym=${pseudonym}; id=${id}]",
      options.resendReadRequestPeriod,
      () => {
        if (options.batchSize == 1) {
          val replica = chainNodes(rand.nextInt(chainNodes.size))
          replica.send(ChainNodeInbound().withRead(readRequest))
        } else {
          batchRead(readRequest)
        }
        metrics.resendReadRequestsTotal.inc()
        t.start()
      }
    )
    t.start()
    t
  }

  // Helpers ///////////////////////////////////////////////////////////////////
  private def timed[T](label: String)(e: => T): T = {
    if (options.measureLatencies) {
      val startNanos = System.nanoTime
      val x          = e
      val stopNanos  = System.nanoTime
      metrics.requestsLatency
        .labels(label)
        .observe((stopNanos - startNanos).toDouble / 1000000)
      x
    } else {
      e
    }
  }

  private def sendClientRequest(
      clientRequest: Write,
      forceFlush: Boolean
  ): Unit = {
    if (options.batchSize == 1) {
      // If there are no batchers, then we send to who we think the leader is.
      val inbound = ChainNodeInbound().withWrite(clientRequest)

      if (options.flushWritesEveryN == 1 || forceFlush) {
        headNode.send(inbound)
      } else {
        headNode.sendNoFlush(inbound)
        writeTicker.foreach(_.tick())
      }
    } else {
      batchWrite(clientRequest)
    }
  }

  private def batchWrite(
      clientRequest: Write
  ): Unit = {
    growingBatch += clientRequest
    if (growingBatch.size >= options.batchSize) {
      headNode.send(
        ChainNodeInbound().withWriteBatch(
          WriteBatch(growingBatch.toSeq)
        )
      )
      growingBatch.clear()
    }
  }

  private def batchRead(
      readRequest: Read
  ): Unit = {
    growingReadBatch += readRequest
    if (growingReadBatch.size >= options.batchSize) {
      val randNode = chainNodes(rand.nextInt(chainNodes.size))
      randNode.send(
        ChainNodeInbound().withReadBatch(
          ReadBatch(growingReadBatch.toSeq)
        )
      )
      growingReadBatch.clear()
    }
  }

  private def writeImpl(
      pseudonym: Pseudonym,
      key: String,
      value: String,
      promise: Promise[Unit]
  ): Unit = {
    states.get(pseudonym) match {
      case Some(_) =>
        promise.failure(
          new IllegalStateException(
            s"You attempted to issue a write with pseudonym $pseudonym, " +
              s"but this pseudonym already has a request pending. A client " +
              s"can only have one pending request at a time. Try waiting or " +
              s"use a different pseudonym."
          )
        )

      case None =>
        // Send the command.
        val id = ids.getOrElse(pseudonym, 0)
        val clientRequest = Write(
          commandId = CommandId(
            clientAddress = addressAsBytes,
            clientPseudonym = pseudonym,
            clientId = id
          ),
          key = key,
          value = value
        )

        sendClientRequest(clientRequest, forceFlush = false)

        // Update our state.
        states(pseudonym) = PendingWrite(
          id = id,
          result = promise,
          resendClientRequest = makeResendClientRequestTimer(clientRequest)
        )
        ids(pseudonym) = id + 1
        metrics.clientRequestsSentTotal.inc()
    }
  }

  private def readImpl(
      pseudonym: Pseudonym,
      key: String,
      promise: Promise[String]
  ): Unit = {
    states.get(pseudonym) match {
      case Some(_) =>
        promise.failure(
          new IllegalStateException(
            s"You attempted to issue a read with pseudonym $pseudonym, " +
              s"but this pseudonym already has a request pending. A client " +
              s"can only have one pending request at a time. Try waiting or " +
              s"use a different pseudonym."
          )
        )

      case None =>
        // If there are no batchers, then we compute a max slot directly with
        // the acceptors ourselves. Otherwise, we send the message to a batcher
        // and let it do it for us.
        val id = ids.getOrElse(pseudonym, 0)
        val readRequest = Read(
          commandId = CommandId(
            clientAddress = addressAsBytes,
            clientPseudonym = pseudonym,
            clientId = id
          ),
          key = key
        )

        if (options.batchSize == 1) {
          val inbound = ChainNodeInbound().withRead(readRequest)
          val node    = chainNodes(rand.nextInt(chainNodes.size))

          if (options.flushWritesEveryN == 1) {
            node.send(inbound)
          } else {
            node.sendNoFlush(inbound)
            readTicker.foreach(_.tick())
          }
        } else {
          batchRead(readRequest)
        }
        // Update our state.
        states(pseudonym) = PendingRead(
          id = id,
          result = promise,
          resendReadRequest =
            makeResendReadRequestTimer(pseudonym, id, readRequest)
        )
        metrics.clientRequestsSentTotal.inc()
        ids(pseudonym) = id + 1
    }
  }

  // Handlers //////////////////////////////////////////////////////////////////
  override def receive(src: Transport#Address, inbound: InboundMessage) = {
    import ClientInbound.Request

    val label = inbound.request match {
      case Request.ClientReply(_) => "ClientReply"
      case Request.ReadReply(_)   => "ReadReply"
      case Request.Empty =>
        logger.fatal("Empty ClientInbound encountered.")
    }
    metrics.requestsTotal.labels(label).inc()

    timed(label) {
      inbound.request match {
        case Request.ClientReply(r) =>
          handleClientReply(src, r)
        case Request.ReadReply(r) =>
          handleReadReply(src, r)
        case Request.Empty =>
          logger.fatal("Empty ClientInbound encountered.")
      }
    }
  }

  private def handleClientReply(
      src: Transport#Address,
      clientReply: ClientReply
  ): Unit = {
    val pseudonym = clientReply.commandId.clientPseudonym
    val state     = states.get(pseudonym)
    state match {
      case None | Some(_: PendingRead) =>
        logger.debug(
          s"A client received a ClientReply, but the state is $state. The " +
            s"ClientReply is being ignored."
        )
        metrics.staleClientRepliesReceivedTotal.inc()

      case Some(pendingWrite: PendingWrite) =>
        if (clientReply.commandId.clientId != pendingWrite.id) {
          logger.debug(
            s"A client received a ClientReply for pseudonym ${pseudonym}, " +
              s"but the client id ${clientReply.commandId.clientId} doesn't " +
              s"match the expected client id ${pendingWrite.id}. The " +
              s"ClientReply is being ignored."
          )
          metrics.staleClientRepliesReceivedTotal.inc()
          return
        }

        pendingWrite.resendClientRequest.stop()
        pendingWrite.result.success(())
        states -= pseudonym
        metrics.clientRepliesReceivedTotal.inc()
    }
  }

  private def handleReadReply(
      src: Transport#Address,
      readReply: ReadReply
  ): Unit = {
    val pseudonym = readReply.commandId.clientPseudonym
    val state     = states.get(pseudonym)
    state match {
      case None | Some(_: PendingWrite) =>
        logger.debug(
          s"A client received a ReadReply, but the state is $state. The " +
            s"ReadReply is being ignored."
        )

      case Some(pendingRead: PendingRead) =>
        if (readReply.commandId.clientId != pendingRead.id) {
          logger.debug(
            s"A client received a ReadReply for pseudonym ${pseudonym}, " +
              s"but the client id ${readReply.commandId.clientId} doesn't " +
              s"match the expected client id ${pendingRead.id}. The " +
              s"ReadReply is being ignored."
          )
          return
        }

        pendingRead.resendReadRequest.stop()
        pendingRead.result.success(readReply.value)
        states -= pseudonym
    }
  }

  // Interface /////////////////////////////////////////////////////////////////
  def write(
      pseudonym: Pseudonym,
      key: String,
      value: String
  ): Future[Unit] = {
    val promise = Promise[Unit]()
    transport.executionContext.execute(() =>
      writeImpl(pseudonym, key, value, promise)
    )
    promise.future
  }

  def read(pseudonym: Pseudonym, key: String): Future[String] = {
    val promise = Promise[String]()
    transport.executionContext.execute(() => readImpl(pseudonym, key, promise))
    promise.future
  }
}
