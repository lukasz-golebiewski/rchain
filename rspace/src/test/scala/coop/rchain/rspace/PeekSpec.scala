package coop.rchain.rspace

import scala.collection.immutable.Seq

import coop.rchain.rspace.examples.AddressBookExample.{Entry => AddressEntry, _}
import coop.rchain.rspace.examples.AddressBookExample.implicits._

import monix.eval.Task
import org.scalatest._

class PeekSpec
    extends InMemoryStoreStorageExamplesTestsBase[Task]
    with TaskTests[Channel, Pattern, Nothing, AddressEntry, EntriesCaptor] {

  "RSpace" should "return peek information with produce result" ignore withTestSpace { space =>
    val c1   = Channel("C1")
    val c2   = Channel("C2")
    val p1   = NameMatch(last = "V1")
    val p2   = NameMatch(last = "V2")
    val bob1 = bob.copy(name = Name("", "V1"))
    val bob2 = bob.copy(name = Name("", "V2"))
    for {
      r1 <- space
             .consume(
               List(c1, c2),
               List(p1, p2),
               new EntriesCaptor,
               persist = false,
               sequenceNumber = 0,
               Map(c1 -> true, c2 -> true)
             )
      _  = r1 shouldBe Right(None)
      r2 <- space.produce(c2, bob2, persist = false)
      _  = r2 shouldBe Right(None)
      r3 <- space.produce(c1, bob1, persist = false)
      _ = {
        val result = r3.right.get.get._1
        result.channels shouldBe Seq(c1, c2)
        result.patterns shouldBe Seq(p1, p2)
        result.peek shouldBe true
      }
    } yield ()
  }

}
