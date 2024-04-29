package frankenpaxos.rt3multipaxos

import java.util.*

trait MonitorRequestStrategy {
  def monitor(
    leaders: Seq[Chan[Leader[Transport]]],
    acceptors: Seq[Chan[Acceptor[Transport]]],
    matchmakers: Seq[Chan[Matchmaker[Transport]]],
    reconfigurer: Seq[Chan[Reconfigurer[Transport]]],
    replica: Seq[Chan[Replica[Transport]]],
  ): Unit
}

class IntervalRequestStrategy(intervalInMs: Int) extends MonitorRequestStrategy {
  override def monitor(
    leaders: Seq[Chan[Leader[Transport]]],
    acceptors: Seq[Chan[Acceptor[Transport]]],
    matchmakers: Seq[Chan[Matchmaker[Transport]]],
    reconfigurer: Seq[Chan[Reconfigurer[Transport]]],
    replica: Seq[Chan[Replica[Transport]]],
  ): Unit = {
    // send a MetricRequest message to all actors every `intervalInMs` milliseconds.
    val timer = new Timer()
    val msgNodesTask = new TimerTask {
      override def run(): Unit = {
        val request = MetricsRequest()

        leaders.foreach(l => l.send(LeaderInbound().withMetricsRequest(request)))
        acceptors.foreach(a => a.send(AcceptorInbound().withMetricsRequest(request)))
        matchmakers.foreach(m => m.send(MatchmakerInbound().withMetricsRequest(request)))
        reconfigurers.foreach(r => r.send(ReconfigurerInbound().withMetricsRequest(request)))
        replicas.foreach(r => r.send(ReplicaInbound().withMetricsRequest(request)))
      }
    }

    timer.scheduleAtFixedRate(msgNodesTask, 0, intervalInMs)
  }
}
