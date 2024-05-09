package frankenpaxos.rt3multipaxos

import collection.mutable
import frankenpaxos.Actor
import frankenpaxos.Chan
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.Gauge
import frankenpaxos.monitoring.PrometheusCollectors
import frankenpaxos.monitoring.Summary
import frankenpaxos.roundsystem.RoundSystem
import scala.util.Random

 object MonitorInboundSerializer extends ProtoSerializer[MonitorInbound] {
  type A = MonitorInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

 object Monitor {
  val serializer = MonitorInboundSerializer
  val metricsTable = mutable.Map()
}

 case class MonitorOptions(
    measureLatencies: Boolean,
    requestStrategy: MonitorRequestStrategy
)

 object MonitorOptions {
  val default = MonitorOptions(
    measureLatencies = true,
    requestStrategy = new FixedIntervalRequestStrategy(200),
  )
}

 class MonitorMetrics(collectors: Collectors) {
  val requestsTotal: Counter = collectors.counter
    .build()
    .name("matchmakermultipaxos_monitor_requests_total")
    .labelNames("type")
    .help("Total number of processed requests.")
    .register()

  val requestsLatency: Summary = collectors.summary
    .build()
    .name("matchmakermultipaxos_monitor_requests_latency")
    .labelNames("type")
    .help("Latency (in milliseconds) of a request.")
    .register()
}

 class Monitor[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport],
    options: MonitorOptions = MonitorOptions.default,
    metrics: MonitorMetrics = new MonitorMetrics(PrometheusCollectors)
) extends Actor(address, transport, logger) {
  config.checkValid()
  logger.check(config.monitorAddresses.contains(address))

  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = MonitorInbound
  override val serializer = MonitorInboundSerializer

  sealed trait State

  case class Inactive() extends State
  case class WaitingForMetrics() extends State
  case class RequestingMetrics() extends State
  case class AnalyzingMetrics() extends State

  // Fields ////////////////////////////////////////////////////////////////////
  // It's important that the monitor knows where all the nodes are
  
  // Leader channels.
  private val leaders: Seq[Chan[Leader[Transport]]] =
    for (a <- config.leaderAddresses)
      yield chan[Leader[Transport]](a, Leader.serializer)

  private val acceptors: Seq[Chan[Acceptor[Transport]]] = 
    for (a <- config.acceptorAddresses)
      yield chan[Acceptor[Transport]](a, Acceptor.serializer)

  private val matchmakers: Seq[Chan[Matchmaker[Transport]]] =
    for (a <- config.matchmakerAddresses)
      yield chan[Matchmaker[Transport]](a, Matchmaker.serializer)

  private val reconfigurers: Seq[Chan[Reconfigurer[Transport]]] =
    for (a <- config.reconfigurerAddresses)
      yield chan[Reconfigurer[Transport]](a, Reconfigurer.serializer)

  private val replicas: Seq[Chan[Replica[Transport]]] =
    for (a <- config.replicaAddresses)
      yield chan[Replica[Transport]](a, Replica.serializer)

  private val index = config.monitorAddresses.indexOf(address)

  becomeActive()

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
    import MonitorInbound.Request

    val label =
      inbound.request match {
        case Request.MetricsReply(_)   => "MetricsReply"
        case Request.Die(_)       => "Die"
        case Request.Empty =>
          logger.fatal("Empty MonitorInbound encountered.")
      }
    metrics.requestsTotal.labels(label).inc()

    timed(label) {
      inbound.request match {
        case Request.MetricsReply(r)   => handleMetricsResponse(src, r)
        case Request.Die(r)       => handleDie(src, r)
        case Request.Empty =>
          logger.fatal("Empty MonitorInbound encountered.")
      }
    }
  }

  private def becomeActive(): Unit = {
    // request the metrics from each node type by sending them a message 
    logger.info("Setting up monitoring strategy")

    options.requestStrategy.run(() => {
      val request = MetricsRequest()
      acceptors.foreach(a => a.send(AcceptorInbound().withMetricsRequest(request)))
    })
  }

  private def handleMetricsResponse(src: Transport#Address, metricsResponse: MetricsReply): Unit = {
    logger.info(s"Received metrics from ${src}")
    logger.info(s"Requests Latency: ${metricsResponse.requestsLatency}, Total Requests: ${metricsResponse.requestsTotal} ")
  }

  private def handleDie(src: Transport#Address, die: Die): Unit = {
    logger.fatal("Die!")
  }
}
