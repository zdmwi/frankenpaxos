package frankenpaxos.util

import scala.collection.mutable
 
class TopOne[V](numLeaders: Int, like: VertexIdLike[V]) {
  type LeaderIndex = Int

     protected val topOnes = mutable.Buffer.fill[Int](numLeaders)(0)

  def put(x: V): Unit = {
    val i = like.leaderIndex(x)
    topOnes(i) = Math.max(topOnes(i), like.id(x) + 1)
  }

  def get(): mutable.Buffer[Int] = topOnes

  def mergeEquals(other: TopOne[V]): Unit = {
    for (i <- 0 until numLeaders) {
      topOnes(i) = Math.max(topOnes(i), other.topOnes(i))
    }
  }
}
