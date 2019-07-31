package frankenpaxos.util

import scala.collection.mutable
import scala.scalajs.js.annotation._

// A FakeCompactSet[A] is really just a Set[A]. It doesn't do any form of
// compaction at all. FakeCompactSet is useful for testing.
@JSExportAll
class FakeCompactSet[A](initialValues: Set[A] = Set[A]())
    extends CompactSet[FakeCompactSet[A]] {
  override type T = A
  private val values: mutable.Set[A] = mutable.Set[A]() ++ initialValues
  override def add(value: A): Boolean = values.add(value)
  override def contains(value: A): Boolean = values.contains(value)
  override def union(other: FakeCompactSet[A]): FakeCompactSet[A] =
    new FakeCompactSet[A](values.union(other.values).toSet)
  override def diff(other: FakeCompactSet[A]): FakeCompactSet[A] =
    new FakeCompactSet[A](values.diff(other.values).toSet)
  override def size: Int = values.size
  override def uncompactedSize: Int = values.size
  override def materialize(): Set[A] = values.toSet
}
