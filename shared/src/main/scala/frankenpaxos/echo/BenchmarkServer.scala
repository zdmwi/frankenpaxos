package frankenpaxos.echo

import frankenpaxos.Actor
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.PrometheusCollectors
import java.net.InetAddress
import java.net.InetSocketAddress
 
 object BenchmarkServerInboundSerializer
    extends ProtoSerializer[BenchmarkServerInbound] {
  type A = BenchmarkServerInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

 object BenchmarkServer {
  val serializer = BenchmarkServerInboundSerializer
}

 class BenchmarkServerMetrics(collectors: Collectors) {
  val echoRequestsTotal: Counter = collectors.counter
    .build()
    .name("echo_requests_total")
    .help("Total echo requests.")
    .register()
}

 class BenchmarkServer[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    metrics: BenchmarkServerMetrics = new BenchmarkServerMetrics(
      PrometheusCollectors
    )
) extends Actor(address, transport, logger) {
  override type InboundMessage = BenchmarkServerInbound
  override def serializer = BenchmarkServer.serializer

  override def receive(
      src: Transport#Address,
      request: BenchmarkServerInbound
  ): Unit = {
    val client =
      chan[BenchmarkClient[Transport]](src, BenchmarkClient.serializer)
    metrics.echoRequestsTotal.inc()
    client.send(BenchmarkClientInbound(id = request.id))
  }
}
