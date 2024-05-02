package frankenpaxos.rt3multipaxos

import frankenpaxos.Chan


trait MonitorRequestStrategy {
  def run(
    f: () => Unit,
  ): Unit
}

class FixedIntervalRequestStrategy(intervalInMs: Int) extends MonitorRequestStrategy {
  override def run(
    f: () => Unit,
  ): Unit = {
    // send a MetricRequest message to all actors every `intervalInMs` milliseconds.
    val timer = new java.util.Timer()
    val task = new java.util.TimerTask {
      override def run(): Unit = {
        f()
      }
    }

    timer.scheduleAtFixedRate(task, 0, intervalInMs)
  }
}
