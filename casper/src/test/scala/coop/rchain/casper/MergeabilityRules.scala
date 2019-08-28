package coop.rchain.casper

import scala.util.Random._

import cats.{Applicative, Functor, Monad}
import cats.implicits._
import coop.rchain.casper.helper.HashSetCasperTestNode
import coop.rchain.casper.helper.HashSetCasperTestNode._
import coop.rchain.casper.protocol.DeployData
import coop.rchain.shared.scalatestcontrib._
import coop.rchain.casper.util.ConstructDeploy
import coop.rchain.casper.util.GenesisBuilder.GenesisContext
import coop.rchain.p2p.EffectsTestInstances.LogicalTime
import monix.execution.Scheduler.Implicits.global
import org.scalatest.exceptions.TestFailedException
import org.scalatest.Assertion
import org.scalactic._

trait MergeabilityRules {

  implicit def timeEff: LogicalTime[Effect]
  def genesis: GenesisContext

  trait TestCase {

    def precondition(left: Rho*)(right: Rho*)(base: Rho*)(
        implicit pos: source.Position
    ): Effect[_]

  }

  def volatileEventPrecondition(left: Rho*)(right: Rho*)(base: Rho*)(
      implicit pos: source.Position
  ): Effect[_] = {
    if (left.size == 1 && right.size == 1) fail("No volatile COMM event")
    differentPolarities(left: _*)
    differentPolarities(right: _*)
    ifTwo(left) {
      case (rho1, rho2) =>
        assert(matches(rho1, rho2))
    }
    ifTwo(right) {
      case (rho1, rho2) =>
        assert(matches(rho1, rho2))
    }
  }.pure[Effect]

  trait MergeableCase extends TestCase {
    def apply(left: Rho*)(right: Rho*)(
        base: Rho*
    )(implicit file: sourcecode.File, line: sourcecode.Line, pos: source.Position): Effect[Unit] =
      precondition(left: _*)(right: _*)(base: _*) >>
        merges(left.reduce(_ | _), right.reduce(_ | _), base.reduce(_ | _))
  }

  trait ConflictingCase extends TestCase {
    def apply(left: Rho*)(right: Rho*)(
        base: Rho*
    )(implicit file: sourcecode.File, line: sourcecode.Line, pos: source.Position): Effect[Unit] =
      precondition(left: _*)(right: _*)(base: _*) >>
        conflicts(left.reduce(_ | _), right.reduce(_ | _), base.reduce(_ | _))
  }

  def samePolarities(left: Seq[Rho])(right: Seq[Rho])(
      implicit
      pos: source.Position
  ): Seq[Assertion] =
    for {
      leftPolarity  <- left.flatMap(_.maybePolarity)
      rightPolarity <- right.flatMap(_.maybePolarity)
    } yield {
      assert(leftPolarity == rightPolarity)
    }

  def differentPolarities(rho: Rho*)(
      implicit
      pos: source.Position
  ): Assertion = {
    val polarities = rho.flatMap(_.maybePolarity)
    polarities should contain theSameElementsAs (polarities.toSet)
  }

  def differentPolarities(left: Rho)(right: Rho)(
      implicit
      pos: source.Position
  ): Seq[Assertion] = differentPolarities(Seq(left))(Seq(right))

  def differentPolarities(left: Seq[Rho])(right: Seq[Rho])(
      implicit
      pos: source.Position
  ): Seq[Assertion] =
    for {
      leftPolarity  <- left.flatMap(_.maybePolarity)
      rightPolarity <- right.flatMap(_.maybePolarity)
    } yield {
      assert(leftPolarity !== rightPolarity)
    }

  def allLinear(rho: Seq[Rho])(
      implicit
      pos: source.Position
  ): Assertion = assert(rho.forall(_.maybeCardinality == Some(Linear)))

  def noPersistentWhenTwo(rho: Seq[Rho])(
      implicit
      pos: source.Position
  ) = {
    assert(rho.forall(_.maybeCardinality.isDefined))
    if (rho.size == 2)
      assert(rho.forall(_.maybeCardinality.get != NonLinear))
  }

  def atLeastOnePersistent(rho: Seq[Rho]): Boolean =
    rho.flatMap(_.maybeCardinality).exists(_ == NonLinear)

  def atLeastOnePeek(rho: Seq[Rho]): Boolean =
    rho.flatMap(_.maybeCardinality).exists(_ == Peek)

  def findMatch(x: Rho, base: Seq[Rho]): Option[Rho] =
    base.find(matches(x, _)).headOption

  def hasMatch(x: Rho, base: Seq[Rho]): Boolean =
    findMatch(x, base).isDefined

  def matches(x: Rho, y: Rho): Boolean = {
    val (a, b)    = if (x.maybePolarity == Some(Send)) (x, y) else (y, x)
    val wildcards = List(F_, C_)
    val knownMatches =
      List(S0, R0).flatMap(s => (F0 :: C0 :: wildcards).map(r => s   -> r)) ++
        List(S1, R1).flatMap(s => (F1 :: C1 :: wildcards).map(r => s -> r))

    knownMatches.contains(a -> b)
  }

  def allMatch(left: Seq[Rho], right: Seq[Rho]) =
    (for {
      l <- left
      r <- right
    } yield matches(l, r)).fold(true)(_ && _)

  def someMatch(left: Seq[Rho], right: Seq[Rho]) =
    (for {
      l <- left
      r <- right
    } yield matches(l, r)).fold(false)(_ || _)

  def expectOne[A](rhos: Seq[Rho])(f: (Rho) => A): A =
    if (rhos.size == 1) {
      val rho1 :: scala.collection.immutable.Nil = rhos.toList
      f(rho1)
    } else fail(s"Expected a single Rho but got $rhos")

  def ifTwo(rhos: Seq[Rho])(f: (Rho, Rho) => Assertion) =
    if (rhos.size == 2) {
      val rho1 :: rho2 :: scala.collection.immutable.Nil = rhos.toList
      f(rho1, rho2)
    }

  /***
   Two incoming sends/receives, at most one had a matching dual in TS.
   The incoming events won't cause more COMMs together (same polarity).
   They couldn't be competing for the same linear receive/send (at most one had a match).
   Notice this includes "two unsatisfied" and "must be looking for different data" cases.
    */
  object SamePolarityMerge extends MergeableCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] = {
      samePolarities(left)(right)
      differentPolarities(left)(base)
      differentPolarities(right)(base)

      (expectOne(left) { findMatch(_, base) }, expectOne(right) { findMatch(_, base) }) match {
        case (Some(m1), Some(m2)) => fail(s"Expected at most one match but got $m1 and $m2")
        case _                    => succeed
      }
    }.pure[Effect]

  }

  /***
   Two incoming sends/receives each matched a receive/send that was in TS.
   The incoming events won't cause more COMMs together (same polarity).
   They could've matched the same linear event.
   Mergeable if different events, or at least one matched event is non-linear.
   This is the case where both incoming events could have matched what was in the other TS.
    */
  object CouldMatchSameConflicts extends ConflictingCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] = {
      samePolarities(left)(right)
      differentPolarities(left)(base)
      differentPolarities(right)(base)

      (expectOne(left) { findMatch(_, base) }, expectOne(right) { findMatch(_, base) }) match {
        case (Some(m1), Some(m2)) =>
          assert(
            m1 == m2 && m1.maybeCardinality != Some(NonLinear) || m2.maybeCardinality != Some(
              NonLinear
            )
          )
        case (m1, m2) => fail(s"Expected two matches but got $m1 and $m2")
      }
    }.pure[Effect]

  }

  /***
   Two incoming sends/receives each matched a receive/send that was in TS.
   The incoming events won't cause more COMMs together (same polarity).
   They could've matched the same linear event.
   Mergeable if different events, or at least one matched event is non-linear.
   This is the case where the incoming events match differently
    */
  object CouldMatchSameMerges extends MergeableCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] = {
      samePolarities(left)(right)
      differentPolarities(left)(base)
      differentPolarities(right)(base)

      (expectOne(left) { findMatch(_, base) }, expectOne(right) { findMatch(_, base) }) match {
        case (Some(m1), Some(m2)) =>
          assert(
            m1 != m2 || m1.maybeCardinality == Some(NonLinear) || m2.maybeCardinality == Some(
              NonLinear
            )
          )
        case (m1, m2) => fail(s"Expected two matches but got $m1 and $m2")
      }
    }.pure[Effect]

  }

  /***
   A send and a receive were incoming, at least one had a match, either:
   - both were linear
   - one was non-linear, the other had a match
   They couldn't match the same linear event (they have different polarity)
   They couldn't spawn more work, because either:
   - both were linear, one of them had a match in TS
   - one was non-linear, but the other chose to go with its match
    */
  object HadItsMatch extends MergeableCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] = {
      differentPolarities(left)(right)

      expectOne(left) { left =>
        expectOne(right) { right =>
          val (a, b) = if (left.maybeCardinality == Some(Linear)) (left, right) else (right, left)
          assert(
            a.maybeCardinality == Some(Linear) && (b.maybeCardinality == Some(Linear) || hasMatch(
              a,
              base
            ))
          )
        }
      }

    }.pure[Effect]

  }

  /***
   An incoming send and an incoming receive could match each other,
   leading to more COMMs needing to happen.
   Mergeable if we use spatial matcher to prove they don't match.
    */
  object IncomingCouldMatch extends ConflictingCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] = {
      differentPolarities(left)(right)
      assert(someMatch(left, right))
    }.pure[Effect]

  }

  /***
   There was a COMM within one of the deploys.
   The other deploy saw none of it.
    */
  object VolatileEvent extends MergeableCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] =
      volatileEventPrecondition(left: _*)(right: _*)(base: _*) >> {
        noPersistentWhenTwo(left)
        noPersistentWhenTwo(right)
      }.pure[Effect]

  }

  /***
   There was a COMM within one of the deploys, with one side non-linear.
   The other deploy had an event without a match in TS, dual to the non-linear.
   These could spawn more work.
   Mergeable if we use spatial matcher to prove they don't match.
    */
  object PersistentCouldMatch extends ConflictingCase {
    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] =
      volatileEventPrecondition(left: _*)(right: _*)(base: _*) >> {
        assert(atLeastOnePersistent(left ++ right))
      }.pure[Effect]

  }

  /***
   There was a COMM within one of the deploys, with one side non-linear.
   The other deploy had an event without a match in TS, of same polarity to the non-linear.
   These could not spawn more work.
    */
  object PersistentNoMatch extends MergeableCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] =
      volatileEventPrecondition(left: _*)(right: _*)(base: _*) >> {
        assert(atLeastOnePersistent(left ++ right))
      }.pure[Effect]

  }

  /***
   There was a COMM within one of the deploys caused on one side by a peek.
   The other deploy had an event without a match in TS, of same polarity to the data matched by peek.
   These could not spawn more work.
    */
  object PeekedNoMatch extends MergeableCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] =
      volatileEventPrecondition(left: _*)(right: _*)(base: _*) >> {
        assert(atLeastOnePeek(left ++ right))
      }.pure[Effect]

  }

  /***
   There was a COMM within one of the deploys, with a peek on one side.
   The other deploy had an event without a match in TS, dual to the non-linear.
   These could spawn more work.
   Mergeable if we use spatial matcher to prove they don't match.
    */
  object PeekedCouldMatch extends ConflictingCase {

    override def precondition(
        left: Rho*
    )(right: Rho*)(base: Rho*)(implicit pos: source.Position): Effect[_] =
      volatileEventPrecondition(left: _*)(right: _*)(base: _*) >> {
        assert(atLeastOnePeek(left ++ right))
      }.pure[Effect]

  }

  case class Rho(
      value: String,
      maybePolarity: Option[Polarity] = None,
      maybeCardinality: Option[Cardinality] = None
  ) {
    def |(other: Rho): Rho = Rho(s"$value | ${other.value}")
  }
  object Nil extends Rho("Nil")

  // Sends (linear sends)
  val S0 = Rho("@0!(0)", Some(Send), Some(Linear))
  val S1 = Rho("@0!(1)", Some(Send), Some(Linear))
  // Repeats (persistent sends)
  val R0 = Rho("@0!!(0)", Some(Send), Some(NonLinear))
  val R1 = Rho("@0!!(1)", Some(Send), Some(NonLinear))
  // For-s (linear receives)
  val F_ = Rho("for (_ <- @0) { 0 }", Some(Receive), Some(Linear))
  val F0 = Rho("for (@0 <- @0) { 0 }", Some(Receive), Some(Linear))
  val F1 = Rho("for (@1 <- @0) { 0 }", Some(Receive), Some(Linear))
  // Contracts (persistent receives)
  val C_ = Rho("contract @0(id) = { 0 }", Some(Receive), Some(NonLinear))
  val C0 = Rho("contract @0(@0) = { 0 }", Some(Receive), Some(NonLinear))
  val C1 = Rho("contract @0(@1) = { 0 }", Some(Receive), Some(NonLinear))

  // TODO: Peek rows/column
  // Note this skips pairs that lead to infinite loops
  val mergeabilityCases = List(
    "!X !X"     -> SamePolarityMerge(S0)(S0)(Nil),
    "!X !4"     -> SamePolarityMerge(S0)(S1)(F1),
    "!X (!4)"   -> VolatileEvent(S0)(S0, F_)(Nil),
    "!X !C"     -> SamePolarityMerge(S0)(S1)(C1),
    "!X (!C)"   -> PersistentCouldMatch(S0)(S0, C_)(Nil),
    "!X 4X"     -> IncomingCouldMatch(S0)(F_)(Nil),
    "!X 4!"     -> HadItsMatch(S0)(F_)(S0),
    "!X (4!)"   -> coveredBy("!X (!4)"),
    "!X 4!!"    -> HadItsMatch(S0)(F_)(R0),
    "!X (4!!)"  -> PersistentNoMatch(S0)(F_, R0)(Nil),
    "!X !!X"    -> SamePolarityMerge(S0)(R0)(Nil),
    "!X !!4"    -> SamePolarityMerge(S0)(R1)(F1),
    "!X (!!4)"  -> coveredBy("!X (4!!)"),
    "!X CX"     -> IncomingCouldMatch(S0)(C_)(Nil),
    "!X C!"     -> IncomingCouldMatch(S0)(C_)(S0),
    "!X (C!)"   -> PersistentCouldMatch(S0)(C_, S0)(Nil),
    "!4 !4"     -> CouldMatchSameConflicts(S0)(S1)(F_),
    "!4 !4"     -> CouldMatchSameMerges(S0)(S1)(F0, F1),
    "!4 (!4)"   -> VolatileEvent(S0)(S1, F_)(F0),
    "(!4) (!4)" -> VolatileEvent(S0, F_)(S0, F_)(Nil),
    "!4 !C"     -> CouldMatchSameMerges(S0)(S1)(F0, C1),
    "!4 4X"     -> HadItsMatch(S0)(F_)(F_),
    "!4 4!"     -> HadItsMatch(S0)(F_)(F0, S1),
    "!4 (4!)"   -> VolatileEvent(S0)(S1, F_)(F0),
    "!4 4!!"    -> HadItsMatch(S0)(F_)(F0, R1),
    "!4 !!X"    -> SamePolarityMerge(S0)(R1)(F0),
    "!4 !!4"    -> CouldMatchSameConflicts(S0)(R1)(F_),
    "!4 !!4"    -> CouldMatchSameMerges(S0)(R1)(F0, F1),
    "!4 CX"     -> HadItsMatch(S0)(C_)(F_),
    "!4 C!"     -> HadItsMatch(S0)(C_)(F0, S1),
    "!4 (C!)"   -> PersistentNoMatch(S0)(C_, S1)(F0),
    "!4 (!C)"   -> PersistentNoMatch(S0)(S1, C_)(F0),
    "!C !C"     -> CouldMatchSameMerges(S0)(S0)(C_),
    "!C (!C)"   -> PersistentNoMatch(S0)(C_, S1)(C0),
    "(!C) !C"   -> coveredBy("!C (!C)"),
    "!C 4X"     -> HadItsMatch(S0)(F_)(C_),
    "!C 4!"     -> HadItsMatch(S0)(F_)(C0, S1),
    "!C (4!)"   -> VolatileEvent(S0)(F1, S1)(C0),
    "!C 4!!"    -> HadItsMatch(S0)(F_)(C0, R1),
    "!C !!X"    -> SamePolarityMerge(S0)(R1)(C0),
    "!C !!4"    -> CouldMatchSameMerges(S0)(R1)(C0, F1),
    "!C CX"     -> HadItsMatch(S0)(C_)(C_),
    "!C C!"     -> HadItsMatch(S0)(C_)(C0, S1),
    "!C (C!)"   -> coveredBy("!C (!C)"),
    "4X 4X"     -> SamePolarityMerge(F_)(F_)(Nil),
    "4X 4!"     -> SamePolarityMerge(F0)(F_)(S1),
    "4X CX"     -> SamePolarityMerge(F_)(C_)(Nil),
    "4X C!"     -> SamePolarityMerge(F0)(C1)(S1),
    "4X (!!4)"  -> PersistentCouldMatch(F_)(R0, F_)(Nil),
    "4! 4!"     -> CouldMatchSameConflicts(F_)(F_)(S0),
    "4! 4!"     -> CouldMatchSameMerges(F0)(F1)(S0, S1),
    "4! CX"     -> SamePolarityMerge(F_)(C1)(S0),
    "4! C!"     -> CouldMatchSameConflicts(F_)(C_)(S0),
    "4! C!"     -> CouldMatchSameMerges(F0)(C1)(S0, S1),
    "CX CX"     -> SamePolarityMerge(C_)(C_)(Nil),
    "CX C!"     -> CouldMatchSameConflicts(C0)(C0)(S0),
    "CX C!"     -> SamePolarityMerge(C1)(C0)(S0),
    "C! C!"     -> CouldMatchSameConflicts(C_)(C_)(S0),
    "C! C!"     -> CouldMatchSameMerges(C0)(C1)(S0, S1),
    "CX !!X"    -> IncomingCouldMatch(R0)(C_)(Nil),
    "(!4) !4"   -> coveredBy("!4 (!4)"),
    "(!4) (!C)" -> coveredBy("(!4) !C"),
    "(!4) (4!)" -> VolatileEvent(S0, F_)(S0, F_)(Nil),
    "(!4) (C!)" -> PersistentNoMatch(S0, F_)(C_, S0)(Nil),
    "(!4) 4X"   -> VolatileEvent(S0, F_)(F_)(Nil),
    "(!4) CX"   -> VolatileEvent(S0, F_)(C_)(Nil),
    "(!C) (!C)" -> PersistentNoMatch(S0, C_)(S0, C_)(Nil),
    "(!C) (4!)" -> PersistentNoMatch(S0, C_)(F_, S0)(Nil),
    "(!C) (C!)" -> PersistentNoMatch(S0, C_)(C_, S0)(Nil),
    "(!C) 4!"   -> PersistentCouldMatch(S0, C_)(F_)(S0),
    "(!C) 4X"   -> PersistentNoMatch(S0, C_)(F_)(Nil),
    "(!C) C!"   -> PersistentCouldMatch(S0, C_)(C_)(S0),
    "(!C) CX"   -> PersistentNoMatch(S0, C_)(C_)(Nil),
    "(4!) (4!)" -> VolatileEvent(F_, S0)(F_, S0)(Nil),
    "(4!) (C!)" -> PersistentNoMatch(F_, S0)(C_, S0)(Nil),
    "(4!) 4!"   -> VolatileEvent(F1, S1)(F_)(S0),
    "(4!) C!"   -> VolatileEvent(F1, S1)(C_)(S0),
    "(C!) C!"   -> PersistentCouldMatch(C_, S0)(C_)(S0),
    "4! (4!)"   -> VolatileEvent(F_)(F1, S1)(S0),
    "4! (C!)"   -> PersistentCouldMatch(F_)(C_, S0)(S0),
    "4X (4!)"   -> VolatileEvent(F_)(F_, S0)(Nil),
    "4X (C!)"   -> PersistentNoMatch(F_)(C_, S0)(Nil),
    "C! (C!)"   -> PersistentNoMatch(C_)(C1, S1)(S0),
    "CX (C!)"   -> PersistentNoMatch(C_)(C_, S0)(Nil),
    "(!4) 4!"   -> VolatileEvent(S1, F1)(F_)(S0),
    "(!4) !C"   -> VolatileEvent(S1, F1)(S0)(C0),
    "(!4) C!"   -> VolatileEvent(S0, F0)(C_)(S1),
    "(4!) CX"   -> VolatileEvent(F_, S0)(C_)(Nil),
    "(C!) (C!)" -> PersistentNoMatch(C_, S0)(C_, S0)(Nil)
  )

  private[this] def conflicts(b1: Rho, b2: Rho, base: Rho)(
      implicit file: sourcecode.File,
      line: sourcecode.Line
  ) =
    randomDiamondConflictCheck(base, b1, b2, numberOfParentsForDiamondTip = 1)

  def merges(b1: Rho, b2: Rho, base: Rho)(
      implicit file: sourcecode.File,
      line: sourcecode.Line
  ) =
    randomDiamondConflictCheck(base, b1, b2, numberOfParentsForDiamondTip = 2)

  private[this] def randomDiamondConflictCheck(
      base: Rho,
      b1: Rho,
      b2: Rho,
      numberOfParentsForDiamondTip: Int
  )(implicit file: sourcecode.File, line: sourcecode.Line): Effect[Unit] = {
    val shuffledBlocks = shuffle(Seq(b1, b2))
    diamondConflictCheck(base, shuffledBlocks(0), shuffledBlocks(1), numberOfParentsForDiamondTip)
  }

  private[this] def coveredBy(equivalent: String) = ().pure[Effect]

  private[this] def diamondConflictCheck(
      base: Rho,
      b1: Rho,
      b2: Rho,
      numberOfParentsForDiamondTip: Int
  )(implicit file: sourcecode.File, line: sourcecode.Line): Effect[Unit] =
    Vector(
      ConstructDeploy.sourceDeployNowF[Effect](base.value),
      ConstructDeploy.sourceDeployNowF[Effect](b1.value),
      ConstructDeploy.sourceDeployNowF[Effect](b2.value),
      ConstructDeploy.sourceDeployNowF[Effect]("Nil")
    ).sequence[Effect, DeployData]
      .flatMap { deploys =>
        HashSetCasperTestNode.networkEff(genesis, networkSize = 2).use { nodes =>
          for {
            _ <- nodes(0).addBlock(deploys(0))
            _ <- nodes(1).receive()
            _ <- nodes(0).addBlock(deploys(1))
            _ <- nodes(1).addBlock(deploys(2))
            _ <- nodes(0).receive()

            multiParentBlock <- nodes(0).addBlock(deploys(3))

            _ = nodes(0).logEff.warns.isEmpty shouldBe true
            _ = multiParentBlock.header.get.parentsHashList.size shouldBe numberOfParentsForDiamondTip
            _ = nodes(0).casperEff.contains(multiParentBlock.blockHash) shouldBeF true
          } yield ()
        }
      }
      .adaptError {
        case e: Throwable =>
          new TestFailedException(s"""Expected
               | base = ${base.value}
               | b1   = ${b1.value}
               | b2   = ${b2.value}
               |
               | to produce a merge block with $numberOfParentsForDiamondTip parents, but it didn't
               |
               | go see it at ${file.value}:${line.value}
               | """.stripMargin, e, 5).severedAtStackDepth
      }

}
