package frankenpaxos.batchedunreplicated

import collection.mutable
import frankenpaxos.Actor
import frankenpaxos.Chan
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.PrometheusCollectors
import frankenpaxos.monitoring.Summary
import frankenpaxos.roundsystem.RoundSystem
 
 object BatcherInboundSerializer extends ProtoSerializer[BatcherInbound] {
  type A = BatcherInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

 object Batcher {
  val serializer = BatcherInboundSerializer
}

 case class BatcherOptions(
    batchSize: Int,
    measureLatencies: Boolean
)

 object BatcherOptions {
  val default = BatcherOptions(
    batchSize = 100,
    measureLatencies = true
  )
}

 class BatcherMetrics(collectors: Collectors) {
  val requestsTotal: Counter = collectors.counter
    .build()
    .name("batchedunreplicated_batcher_requests_total")
    .labelNames("type")
    .help("Total number of processed requests.")
    .register()

  val requestsLatency: Summary = collectors.summary
    .build()
    .name("batchedunreplicated_batcher_requests_latency")
    .labelNames("type")
    .help("Latency (in milliseconds) of a request.")
    .register()

  val batchesSent: Counter = collectors.counter
    .build()
    .name("batchedunreplicated_batcher_batches_sent")
    .help("Total number of batches sent.")
    .register()
}

 class Batcher[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport],
    options: BatcherOptions = BatcherOptions.default,
    metrics: BatcherMetrics = new BatcherMetrics(PrometheusCollectors),
    seed: Long = System.currentTimeMillis()
) extends Actor(address, transport, logger) {
  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = BatcherInbound
  override val serializer = BatcherInboundSerializer

  // Fields ////////////////////////////////////////////////////////////////////
  // Server channel.
  private val server: Chan[Server[Transport]] =
    chan[Server[Transport]](config.serverAddress, Server.serializer)

     protected var growingBatch = mutable.Buffer[Command]()

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

  // Handlers //////////////////////////////////////////////////////////////////
  override def receive(src: Transport#Address, inbound: InboundMessage) = {
    import BatcherInbound.Request

    val label =
      inbound.request match {
        case Request.ClientRequest(_) => "ClientRequest"
        case Request.Empty =>
          logger.fatal("Empty BatcherInbound encountered.")
      }
    metrics.requestsTotal.labels(label).inc()

    timed(label) {
      inbound.request match {
        case Request.ClientRequest(r) => handleClientRequest(src, r)
        case Request.Empty =>
          logger.fatal("Empty BatcherInbound encountered.")
      }
    }
  }

  private def handleClientRequest(
      src: Transport#Address,
      clientRequest: ClientRequest
  ): Unit = {
    growingBatch += clientRequest.command
    if (growingBatch.size >= options.batchSize) {
      server.send(
        ServerInbound().withClientRequestBatch(
          ClientRequestBatch(command = growingBatch.toSeq)
        )
      )
      growingBatch.clear()
      metrics.batchesSent.inc()
    }
  }
}
