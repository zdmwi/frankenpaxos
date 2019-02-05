package zeno.examples

import org.scalatest._
import zeno.BadHistory
import zeno.Simulator

class DieHardSpec extends FlatSpec {
  "Die Hard jugs" should "always satisfy their type invariants" in {
    val sim = new SimulatedDieHard()
    Simulator
      .simulate(sim, runLength = 10, numRuns = 1000)
      .flatMap(b => Simulator.minimize(sim, b.history)) match {
      case Some(BadHistory(history, error)) =>
        fail(s"Error: $error\n$history")
      case None => {}
    }
  }
}
