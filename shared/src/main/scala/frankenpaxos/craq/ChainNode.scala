package frankenpaxos.craq

import com.google.protobuf.ByteString
import collection.mutable
import frankenpaxos.Actor
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.PrometheusCollectors
import frankenpaxos.monitoring.Summary
import frankenpaxos.roundsystem.RoundSystem
import scala.util.Random

object ChainNodeInboundSerializer extends ProtoSerializer[ChainNodeInbound] {
  type A = ChainNodeInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

object ChainNode {
  val serializer = ChainNodeInboundSerializer
}

case class ChainNodeOptions(
    measureLatencies: Boolean
)

object ChainNodeOptions {
  val default = ChainNodeOptions(
    measureLatencies = true
  )
}

class ChainNodeMetrics(collectors: Collectors) {
  val requestsTotal: Counter = collectors.counter
    .build()
    .name("craq_chain_node_requests_total")
    .labelNames("type")
    .help("Total number of processed requests.")
    .register()

  val requestsLatency: Summary = collectors.summary
    .build()
    .name("craq_chain_node_requests_latency")
    .labelNames("type")
    .help("Latency (in milliseconds) of a request.")
    .register()
}

class ChainNode[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport],
    options: ChainNodeOptions = ChainNodeOptions.default,
    metrics: ChainNodeMetrics = new ChainNodeMetrics(PrometheusCollectors),
    seed: Long = System.currentTimeMillis()
) extends Actor(address, transport, logger) {
  config.checkValid()

  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = ChainNodeInbound
  override val serializer = ChainNodeInboundSerializer

  type ClientId = Int
  type ClientPseudonym = Int

  // Fields ////////////////////////////////////////////////////////////////////
  // ChainNode channels.
  private val chainNodes: Seq[Chan[ChainNode[Transport]]] =
    for (a <- config.chainNodeAddresses)
      yield chan[ChainNode[Transport]](a, ChainNode.serializer)

  // The client table used to ensure exactly once execution semantics. Every
  // entry in the client table is keyed by a clients address and its pseudonym
  // and maps to the largest executed id for the client and the result of
  // executing the command. Note that unlike with generalized protocols like
  // BPaxos and EPaxos, we don't need to use the more complex ClientTable
  // class. A simple map suffices.
  //
  // TODO(mwhittaker): Right now, the clientTable is unsused.
  @JSExport
  protected var clientTable =
    mutable.Map[(ByteString, ClientPseudonym), (ClientId, ByteString)]()

  @JSExport
  protected val index = config.chainNodeAddresses.indexOf(address)

  @JSExport
  protected val nextIndex = index + 1

  @JSExport
  protected val prevIndex = index - 1

  @JSExport
  protected val isHead = index == 0

  @JSExport
  protected val isTail = index == config.chainNodeAddresses.size - 1

  @JSExport
  protected val pendingWrites: mutable.Buffer[WriteBatch] = mutable.Buffer()

  val stateMachine: mutable.Map[String, String] = mutable.Map[String, String]()

  var versions: Int = 0

  // Timers ////////////////////////////////////////////////////////////////////

  // Helpers ///////////////////////////////////////////////////////////////////
  private def timed[T](label: String)(e: => T): T = {
    if (options.measureLatencies) {
      val startNanos = System.nanoTime
      val x = e
      val stopNanos = System.nanoTime
      metrics.requestsLatency
        .labels(label)
        .observe((stopNanos - startNanos).toDouble / 1000000)
      x
    } else {
      e
    }
  }

  private def processWriteBatch(writeBatch: WriteBatch): Unit = {
    pendingWrites.append(writeBatch)

    if (!isTail) {
      chainNodes(nextIndex).send(
        ChainNodeInbound().withWriteBatch(writeBatch)
      )
    } else {
      for (command <- writeBatch.write) {
        stateMachine.put(command.key, command.value)
        val reply = stateMachine.get(command.key)
        val clientAddress = transport.addressSerializer
          .fromBytes(
            command.commandId.clientAddress.toByteArray()
          )
        val client =
          chan[Client[Transport]](clientAddress, Client.serializer)
        client.send(
          ClientInbound().withClientReply(ClientReply(command.commandId))
        )
        versions += 1
      }
      pendingWrites.remove(pendingWrites.indexOf(writeBatch))
      if (!isHead) {
        chainNodes(prevIndex).send(ChainNodeInbound().withAck(Ack(writeBatch)))
      }
    }
  }

  private def processReadBatch(readBatch: ReadBatch): Unit = {
    val keys: mutable.Set[String] = mutable.Set[String]()
    for (pw <- pendingWrites) {
      for (w <- pw.write) {
        keys.add(w.key)
      }
    }

    val dirtyReads: mutable.Buffer[Read] = mutable.Buffer[Read]()
    for (read <- readBatch.read) {
      if (keys.contains(read.key)) {
        // The key is dirty; ask the tail.
        dirtyReads += read
      } else {
        // The key is clean; serve the latest value.
        val reply = stateMachine.getOrElse(read.key, "default")
        val clientAddress = transport.addressSerializer.fromBytes(
          read.commandId.clientAddress.toByteArray()
        )
        val client =
          chan[Client[Transport]](clientAddress, Client.serializer)
        client.send(
          ClientInbound().withReadReply(ReadReply(read.commandId, reply))
        )
        versions += 1
      }
    }
    // Send dirty reads to tail
    if (dirtyReads.nonEmpty) {
      chainNodes.last.send(
        ChainNodeInbound().withTailRead(TailRead(ReadBatch(dirtyReads.toSeq)))
      )
    }
  }

  // Handlers //////////////////////////////////////////////////////////////////
  override def receive(src: Transport#Address, inbound: InboundMessage) = {
    import ChainNodeInbound.Request

    val label =
      inbound.request match {
        case Request.Write(_)      => "Write"
        case Request.WriteBatch(_) => "WriteBatch"
        case Request.Read(_)       => "Read"
        case Request.ReadBatch(_)  => "ReadBatch"
        case Request.Ack(_)        => "Ack"
        case Request.TailRead(_)   => "TailRead"
        case Request.Empty =>
          logger.fatal("Empty ChainNodeInbound encountered.")
      }
    metrics.requestsTotal.labels(label).inc()

    timed(label) {
      inbound.request match {
        case Request.Write(r) =>
          handleWrite(src, r)
        case Request.WriteBatch(r) =>
          handleWriteBatch(src, r)
        case Request.Read(r) =>
          handleRead(src, r)
        case Request.ReadBatch(r) =>
          handleReadBatch(src, r)
        case Request.Ack(r) =>
          handleAck(src, r)
        case Request.TailRead(r) =>
          handleTailRead(src, r)
        case Request.Empty =>
          logger.fatal("Empty ChainNodeInbound encountered.")
      }
    }
  }

  private def handleWrite(
      src: Transport#Address,
      write: Write
  ): Unit = {
    processWriteBatch(
      WriteBatch(Seq(write))
    )
  }

  private def handleWriteBatch(
      src: Transport#Address,
      writeBatch: WriteBatch
  ): Unit = {
    processWriteBatch(writeBatch)
  }

  private def handleRead(
      src: Transport#Address,
      read: Read
  ): Unit = {
    processReadBatch(
      ReadBatch(Seq(read))
    )
  }

  private def handleReadBatch(
      src: Transport#Address,
      readBatch: ReadBatch
  ): Unit = {

    processReadBatch(readBatch)
  }

  private def handleTailRead(
      src: Transport#Address,
      tailRead: TailRead
  ): Unit = {
    for (command <- tailRead.readBatch.read) {
      val value = stateMachine.getOrElse(command.key, "default")
      val clientAddress = transport.addressSerializer.fromBytes(
        command.commandId.clientAddress.toByteArray()
      )
      val client =
        chan[Client[Transport]](clientAddress, Client.serializer)
      client.send(
        ClientInbound().withReadReply(ReadReply(command.commandId, value))
      )
      versions += 1
    }

  }

  private def handleAck(
      src: Transport#Address,
      ack: Ack
  ): Unit = {
    pendingWrites.remove(pendingWrites.indexOf(ack.writeBatch))
    for (write <- ack.writeBatch.write) {
      stateMachine.put(write.key, write.value)
    }
    if (!isHead) {
      chainNodes(prevIndex).send(ChainNodeInbound().withAck(ack))
    }
  }
}
