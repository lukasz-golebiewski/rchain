package coop.rchain.casper.helper

import cats._
import cats.data.StateT
import cats.effect.{Bracket, ExitCase}
import cats.implicits._
import cats.mtl.MonadState
import coop.rchain.catscontrib._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.{BlockStore, InMemBlockStore}
import coop.rchain.casper.BlockDag
import coop.rchain.casper.Estimator.{BlockHash, Validator}
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.crypto.hash.Blake2b256
import coop.rchain.p2p.EffectsTestInstances.LogicalTime
import coop.rchain.shared.Time

import scala.collection.immutable.{HashMap, HashSet}
import scala.language.higherKinds

object BlockGenerator {
  implicit val timeEff = new LogicalTime[Id]

  type StateWithChain[A] = StateT[Id, BlockDag, A]

  type BlockDagState[F[_]] = MonadState[F, BlockDag]
  def blockDagState[F[_]: Monad: BlockDagState]: BlockDagState[F] = MonadState[F, BlockDag]

  def storeForStateWithChain(idBs: BlockStore[Id]): BlockStore[StateWithChain] = {
    implicit val bracket: Bracket[StateWithChain, Exception] =
      new Bracket[StateWithChain, Exception] {
        override def bracketCase[A, B](acquire: StateWithChain[A])(use: A => StateWithChain[B])(
            release: (A, ExitCase[Exception]) => StateWithChain[Unit]): StateWithChain[B] = ???

        override def flatMap[A, B](fa: StateWithChain[A])(
            f: A => StateWithChain[B]): StateWithChain[B] = ???

        override def tailRecM[A, B](a: A)(f: A => StateWithChain[Either[A, B]]): StateWithChain[B] =
          ???

        override def raiseError[A](e: Exception): StateWithChain[A] = ???

        override def handleErrorWith[A](fa: StateWithChain[A])(
            f: Exception => StateWithChain[A]): StateWithChain[A] = ???

        override def pure[A](x: A): StateWithChain[A] = ???
      }

    InMemBlockStore.create[StateWithChain]
  }
}

trait BlockGenerator {
  import BlockGenerator._

  def createBlock[F[_]: Monad: BlockDagState: Time: BlockStore](
      parentsHashList: Seq[BlockHash],
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      deploys: Seq[Deploy] = Seq.empty[Deploy],
      tsHash: ByteString = ByteString.EMPTY,
      tsLog: Seq[Event] = Seq.empty[Event]): F[BlockMessage] =
    for {
      chain             <- blockDagState[F].get
      now               <- Time[F].currentMillis
      nextId            = chain.currentId + 1
      nextCreatorSeqNum = chain.currentSeqNum.getOrElse(creator, -1) + 1
      postState = RChainState()
        .withTuplespace(tsHash)
        .withBonds(bonds)
        .withBlockNumber(nextId.toLong)
      postStateHash = Blake2b256.hash(postState.toByteArray)
      header = Header()
        .withPostStateHash(ByteString.copyFrom(postStateHash))
        .withParentsHashList(parentsHashList)
        .withTimestamp(now)
      blockHash = Blake2b256.hash(header.toByteArray)
      body      = Body().withPostState(postState).withNewCode(deploys).withCommReductions(tsLog)
      serializedJustifications = justifications.toList.map {
        case (creator: Validator, latestBlockHash: BlockHash) =>
          Justification(creator, latestBlockHash)
      }
      serializedBlockHash = ByteString.copyFrom(blockHash)
      block = BlockMessage(serializedBlockHash,
                           Some(header),
                           Some(body),
                           serializedJustifications,
                           creator,
                           nextCreatorSeqNum)
      idToBlocks     = chain.idToBlocks + (nextId -> block)
      _              <- BlockStore[F].put(serializedBlockHash, block)
      latestMessages = chain.latestMessages + (block.sender -> serializedBlockHash)
      latestMessagesOfLatestMessages = chain.latestMessagesOfLatestMessages + (block.sender -> ProtoUtil
        .toLatestMessages(serializedJustifications))
      updatedChildren = HashMap[BlockHash, Set[BlockHash]](parentsHashList.map {
        parentHash: BlockHash =>
          val currentChildrenHashes = chain.childMap.getOrElse(parentHash, HashSet.empty[BlockHash])
          val updatedChildrenHashes = currentChildrenHashes + serializedBlockHash
          parentHash -> updatedChildrenHashes
      }: _*)
      childMap = chain.childMap
        .++[(BlockHash, Set[BlockHash]), Map[BlockHash, Set[BlockHash]]](updatedChildren)
      updatedSeqNumbers = chain.currentSeqNum.updated(creator, nextCreatorSeqNum)
      newChain: BlockDag = BlockDag(idToBlocks,
                                    childMap,
                                    latestMessages,
                                    latestMessagesOfLatestMessages,
                                    nextId,
                                    updatedSeqNumbers)
      _ <- blockDagState[F].set(newChain)
    } yield block
}
