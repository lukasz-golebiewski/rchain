package coop.rchain.rspace

import scala.collection.immutable.Seq

import coop.rchain.rspace.examples.AddressBookExample.{Entry => AddressEntry, _}
import coop.rchain.rspace.examples.AddressBookExample.implicits._

import monix.eval.Task
import org.scalatest._

class PeekSpec
    extends InMemoryStoreStorageExamplesTestsBase[Task]
    with TaskTests[Channel, Pattern, Nothing, AddressEntry, EntriesCaptor] {

  behavior of "an empty RSpace"

  it should "accept a peek consume" in withTestSpace { space =>
    for {
      r1 <- space.consumeOneChannelWithPeek
      _  = r1 shouldBe Right(None)
    } yield ()
  }

  it should "not accept mismatched peek designations" ignore withTestSpace { space => ??? }

  behavior of "RSpace produce"

  it should "find a peek match for an existing non-linear consume" ignore withTestSpace { space =>
    for {
      r1 <- space.consumeOneChannelWithPeek
      _  = r1 shouldBe Right(None)
      r3 <- space.produceOne
      _ = matchOneContResult(r3.right.get.get._1)
      _ = space.dataShouldNotExist(c1)
      r4 <- space.produceOne
      _ = r4 shouldBe Right(None)
      _ = space.dataShouldExist(c1)
    } yield ()
  }

  it should "find a peek match for all existing non-linear consumes" ignore withTestSpace { space =>
    for {
      r1 <- space.consumeOneChannelWithPeek
      _  = r1 shouldBe Right(None)
      r2 <- space.consumeOneChannelWithPeek
      _  = r2 shouldBe Right(None)
      r3 <- space.produceOne
      _ = matchOneContResult(r3.right.get.get._1)
      _ = space.dataShouldExist(c1)
      r4 <- space.produceOne
      _ = r4 shouldBe matchOneContResult(r3.right.get.get._1)
      _ = space.dataShouldNotExist(c1)
      r5 <- space.produceOne
      _ = r5 shouldBe Right(None)
      _ = space.dataShouldExist(c1)
    } yield ()
  }

  it should "find a peek match for existing non-linear consume or a regular match for existing non-linear consume" ignore withTestSpace { space =>
    def chooseMatch(r: Either[Nothing, Option[(ContResult[Channel, Pattern, EntriesCaptor], Seq[Result[AddressEntry]])]]) = {
      val result = r.right.get.get._1
      if (result.peek) {
        matchOneContResult(result)
        space.produceOne
      } else {
        Task { r }
      }
    }
    for {
      r1 <- space.consumeOneChannelWithPeek
      _  = r1 shouldBe Right(None)
      r2 <- space.consumeOneChannelNoPeek
      _  = r2 shouldBe Right(None)
      r3 <- space.produceOne
      v <- chooseMatch(r3)
      _ = matchOneContResult(v.right.get.get._1, isPeek = false)
      _ = space.dataShouldNotExist(c1)
    } yield ()
  }

  it should "find a peek match for an exiting partially matched (not peek match) non-linear consume" ignore withTestSpace { space =>
    for {
      r1 <- space.consumeTwoChannelsMixedPeek
      _  = r1 shouldBe Right(None)
      r2 <- space.produce(c2, bob2, persist = false)
      _  = r2 shouldBe Right(None)
      r3 <- space.produceOne
      _ = matchTwoContResult(r3.right.get.get._1)
      r4 <- space.produceOne
      _ = r4 shouldBe Right(None)
      _ = space.dataShouldNotExist(c1)
      _ = space.dataShouldExist(c2)
    } yield ()
  }

  it should "find a peek match for an exiting partially matched (peek match) non-linear consume" ignore withTestSpace { space =>
    for {
      r1 <- space.consumeTwoChannelsWithPeek
      _  = r1 shouldBe Right(None)
      r2 <- space.produce(c2, bob2, persist = false)
      _  = r2 shouldBe Right(None)
      r3 <- space.produceOne
      _ = matchTwoContResult(r3.right.get.get._1)
      r4 <- space.produceOne
      _ = r4 shouldBe Right(None)
      _ = space.dataShouldExist(c1)
      _ = space.dataShouldExist(c2)
    } yield ()
  }

  val c1   = Channel("C1")
  val c2   = Channel("C2")
  val p1   = NameMatch(last = "V1")
  val p2   = NameMatch(last = "V2")
  val bob1 = bob.copy(name = Name("", "V1"))
  val bob2 = bob.copy(name = Name("", "V2"))

  implicit class RichSpace(space: T) {
    def consumeOneChannelWithPeek = {
      space.consume(
        List(c1),
        List(p1),
        new EntriesCaptor,
        persist = false,
        sequenceNumber = 0,
        Map(c1 -> true))
    }
    def consumeOneChannelNoPeek = {
      space.consume(
        List(c1),
        List(p1),
        new EntriesCaptor,
        persist = false,
        sequenceNumber = 0)
    }

    def produceOne = {
      space.produce(c1, bob1, persist = false)
    }
    def consumeTwoChannelsWithPeek = {
      space
        .consume(
          List(c1, c2),
          List(p1, p2),
          new EntriesCaptor,
          persist = false,
          sequenceNumber = 0,
          Map(c1 -> true, c2 -> true)
        )
    }

    def consumeTwoChannelsMixedPeek = {
      space
        .consume(
          List(c1, c2),
          List(p1, p2),
          new EntriesCaptor,
          persist = false,
          sequenceNumber = 0,
          Map(c1 -> true, c2 -> false)
        )
    }

    def dataShouldExist(c: Channel) = {
      space.store.withTxn(space.store.createTxnRead()) { txn =>
        space.store.getData(txn, Seq(c)) should not be empty
      }
    }

    def dataShouldNotExist(c: Channel) = {
      space.store.withTxn(space.store.createTxnRead()) { txn =>
        space.store.getData(txn, Seq(c)) shouldBe empty
      }
    }
  }

  def matchOneContResult(result: ContResult[Channel, Pattern, EntriesCaptor], isPeek: Boolean = true) = {
    result.channels shouldBe Seq(c1)
    result.patterns shouldBe Seq(p1)
    result.peek shouldBe isPeek
  }

  def matchTwoContResult(result: ContResult[Channel, Pattern, EntriesCaptor]) = {
    result.channels shouldBe Seq(c1, c2)
    result.patterns shouldBe Seq(p1, p2)
    result.peek shouldBe true
  }
}
