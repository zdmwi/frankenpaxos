package frankenpaxos.epaxos

import frankenpaxos.compact.CompactSet
import frankenpaxos.compact.IntPrefixSet
import frankenpaxos.util
import scala.collection.mutable
import scala.scalajs.js.annotation._

@JSExportAll
object InstancePrefixSet {
  @JSExport("apply")
  def apply(numReplicas: Int): InstancePrefixSet = {
    new InstancePrefixSet(numReplicas,
                          mutable.Buffer.fill(numReplicas)(IntPrefixSet()))
  }
}

@JSExportAll
class InstancePrefixSet private (
    numReplicas: Int,
    val intPrefixSets: mutable.Buffer[IntPrefixSet]
) extends CompactSet[InstancePrefixSet] {
  override type T = Instance

  override def toString(): String = intPrefixSets.toString()

  override def add(instance: Instance): Boolean =
    intPrefixSets(instance.replicaIndex).add(instance.instanceNumber)

  override def contains(instance: Instance): Boolean =
    intPrefixSets(instance.replicaIndex).contains(instance.instanceNumber)

  override def union(other: InstancePrefixSet): InstancePrefixSet = {
    new InstancePrefixSet(
      numReplicas,
      for ((lhs, rhs) <- intPrefixSets.zip(other.intPrefixSets))
        yield lhs.union(rhs)
    )
  }

  override def diff(other: InstancePrefixSet): InstancePrefixSet = {
    new InstancePrefixSet(
      numReplicas,
      for ((lhs, rhs) <- intPrefixSets.zip(other.intPrefixSets))
        yield lhs.diff(rhs)
    )
  }

  override def addAll(other: InstancePrefixSet): this.type = {
    for ((lhs, rhs) <- intPrefixSets.zip(other.intPrefixSets)) {
      lhs.addAll(rhs)
    }
    this
  }

  override def subtractAll(other: InstancePrefixSet): this.type = {
    for ((lhs, rhs) <- intPrefixSets.zip(other.intPrefixSets)) {
      lhs.subtractAll(rhs)
    }
    this
  }

  override def size: Int = intPrefixSets.map(_.size).sum

  override def uncompactedSize: Int = intPrefixSets.map(_.uncompactedSize).sum

  override def subset(): InstancePrefixSet =
    new InstancePrefixSet(numReplicas, intPrefixSets.map(_.subset()))

  override def materialize(): Set[Instance] = {
    {
      for {
        (intPrefixSet, replicaIndex) <- intPrefixSets.zipWithIndex
        instanceNumber <- intPrefixSet.materialize
      } yield
        Instance(replicaIndex = replicaIndex, instanceNumber = instanceNumber)
    }.toSet
  }

  def getWatermark(): Seq[Int] =
    intPrefixSets.map(_.getWatermark)
}
