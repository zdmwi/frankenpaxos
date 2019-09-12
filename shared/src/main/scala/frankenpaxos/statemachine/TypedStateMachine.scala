package frankenpaxos.statemachine

// A StateMachine takes in strings and outputs strings. A TypedStateMachine, on
// the other hand, is parameterized by an input type I and output type O.
// TypedStateMachines are useful when inputs and outputs are non-trivial (e.g.,
// when they are protobufs).
trait TypedStateMachine[I, O] extends StateMachine {
  // Abstract value members.
  def inputSerializer: frankenpaxos.Serializer[I]
  def outputSerializer: frankenpaxos.Serializer[O]
  def typedRun(input: I): O
  def typedConflicts(firstCommand: I, secondCommand: I): Boolean
  def typedConflictIndex[Key](): ConflictIndex[Key, I]
  def typedTopKConflictIndex[Key](
      k: Int,
      like: VertexIdLike[Key]
  ): ConflictIndex[Key, I]

  // Concrete value members.
  override def run(input: Array[Byte]): Array[Byte] = {
    val output = typedRun(inputSerializer.fromBytes(input))
    outputSerializer.toBytes(output)
  }

  // TODO(mwhittaker): Re-think whether this API is the one we want.
  override def conflicts(
      firstCommand: Array[Byte],
      secondCommand: Array[Byte]
  ): Boolean = {
    typedConflicts(inputSerializer.fromBytes(firstCommand),
                   inputSerializer.fromBytes(secondCommand))
  }

  private def erasedConflictIndex[Key](
      conflictIndex: ConflictIndex[Key, I]
  ): ConflictIndex[Key, Array[Byte]] =
    ???
  // new ConflictIndex[Key, Array[Byte]] {
  //   private val index = typedConflictIndex[Key]()
  //
  //   override def put(key: Key, command: Array[Byte]) =
  //     index.put(key, inputSerializer.fromBytes(command))
  //
  //   override def putSnapshot(key: Key) =
  //     index.putSnapshot(key)
  //
  //   override def remove(key: Key): Unit =
  //     index.remove(key)
  //
  //   override def getConflicts(command: Array[Byte]): Set[Key] = {
  //     index.getConflicts(inputSerializer.fromBytes(command))
  //   }
  // }

  override def conflictIndex[Key](): ConflictIndex[Key, Array[Byte]] =
    erasedConflictIndex(typedConflictIndex())

  override def topKConflictIndex[Key](
      k: Int,
      like: VertexIdLike[Key]
  ): ConflictIndex[Key, Array[Byte]] =
    erasedConflictIndex(typedTopKConflictIndex(k, like))
}
