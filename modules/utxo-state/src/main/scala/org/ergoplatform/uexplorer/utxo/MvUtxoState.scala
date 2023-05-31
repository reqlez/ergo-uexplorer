package org.ergoplatform.uexplorer.utxo

import org.ergoplatform.uexplorer.node.ApiTransaction
import org.ergoplatform.uexplorer.utxo.MvUtxoState.*
import org.ergoplatform.uexplorer.{Address, BlockId, BlockMetadata, BoxId, Height, Timestamp, Value}
import org.h2.mvstore.MVStore

import scala.collection.mutable
import java.io.File
import java.nio.file.Paths
import scala.collection.compat.immutable.ArraySeq
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Random, Success, Try}
import org.ergoplatform.uexplorer.Tx
import org.ergoplatform.uexplorer.Const
import org.ergoplatform.uexplorer.*
import org.h2.mvstore.MVMap

import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentSkipListMap
import scala.collection.immutable.{TreeMap, TreeSet}
import org.ergoplatform.uexplorer.node.ApiFullBlock
import org.ergoplatform.uexplorer.db.BlockBuilder

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.ergoplatform.uexplorer.db.Block
import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.Done
import akka.actor.typed.ActorSystem
import org.ergoplatform.uexplorer.db.BestBlockInserted
import org.ergoplatform.uexplorer.db.ForkInserted

class MvUtxoState(
  store: MVStore,
  utxosByAddress: MVMap[Address, Map[BoxId, Value]],
  addressByUtxo: MVMap[BoxId, Address],
  topAddresses: MVMap[Address, Address.Stats],
  blockIdByHeight: MVMap[Height, BlockId],
  blockCacheByHeight: ConcurrentSkipListMap[Height, Map[BlockId, BlockMetadata]]
) extends UtxoState {

  // TODO persistent ?
  val versionByHeight = new ConcurrentSkipListMap[Height, MvUtxoState.Version]()
  // TODO periodically drop versions and top addresses

  def load(blockHeightSource: Source[(Height, BlockId), NotUsed])(implicit s: ActorSystem[Nothing]): Future[Done] =
    blockHeightSource.runForeach { case (height, blockId) =>
      blockIdByHeight.put(height, blockId)
    }

  def init(getLastBlock: BlockId => Future[Option[BlockMetadata]]): Future[Unit] = {
    val lastHeight = blockIdByHeight.lastKey
    Future
      .sequence(
        Option(blockIdByHeight.get(lastHeight))
          .map(getLastBlock(_))
          .toList
      )
      .map(_.flatten)
      .map { blockMetadata =>
        blockCacheByHeight.put(lastHeight, blockMetadata.map(bm => bm.headerId -> bm).toMap)
      }
  }

  def isEmpty: Boolean = utxosByAddress.isEmpty || addressByUtxo.isEmpty

  def getLastBlock: Option[(Height, BlockId)] = Option(blockIdByHeight.lastKey()).map { lastKey =>
    lastKey -> blockIdByHeight.get(lastKey)
  }

  def getFirstBlock: Option[(Height, BlockId)] = Option(blockIdByHeight.firstKey()).map { firstKey =>
    firstKey -> blockIdByHeight.get(firstKey)
  }

  def getAddressStats(address: Address): Option[Address.Stats] = Option(topAddresses.get(address))

  def containsBlock(blockId: BlockId, atHeight: Height): Boolean = blockIdByHeight.get(atHeight) == blockId

  def getAddressByUtxo(boxId: BoxId): Option[Address] = Option(addressByUtxo.get(boxId))

  def getUtxosByAddress(address: Address): Option[Map[BoxId, Value]] = Option(utxosByAddress.get(address))

  def getTopAddresses: Iterator[(Address, Address.Stats)] = new Iterator[(Address, Address.Stats)]() {
    private val cursor = topAddresses.cursor(null.asInstanceOf[Address])

    override def hasNext: Boolean = cursor.hasNext

    override def next(): (Address, Address.Stats) = cursor.next() -> cursor.getValue
  }

  def getBlocksByHeight: Iterator[(Height, BlockId)] = new Iterator[(Height, BlockId)]() {
    private val cursor = blockIdByHeight.cursor(1)

    override def hasNext: Boolean = cursor.hasNext

    override def next(): (Height, BlockId) = cursor.next() -> cursor.getValue
  }

  def findMissingHeights: TreeSet[Height] = {
    val lastBlock = getLastBlock
    if (lastBlock.isEmpty || lastBlock.map(_._1).contains(1))
      TreeSet.empty
    else
      TreeSet((getFirstBlock.get._1 to lastBlock.get._1): _*)
        .diff(blockIdByHeight.keySet().asScala)
  }

  def flushCache: Unit =
    blockCacheByHeight
      .keySet()
      .asScala
      .take(Math.max(0, blockCacheByHeight.size() - MaxCacheSize))
      .foreach(blockCacheByHeight.remove)

  def mergeBlockBoxesUnsafe(
    height: Height,
    blockId: BlockId,
    boxes: Iterator[(Iterable[(BoxId, Address, Value)], Iterable[(BoxId, Address, Value)])]
  ): Try[Unit] = Try {
    boxes.foreach { case (inputBoxes, outputBoxes) =>
      outputBoxes
        .foldLeft(mutable.Map.empty[Address, Int]) { case (acc, (boxId, address, value)) =>
          addressByUtxo.put(boxId, address)
          utxosByAddress.adjust(address)(_.fold(Map(boxId -> value))(_.updated(boxId, value)))
          acc.adjust(address)(_.fold(1)(_ + 1))
        }
        .foreach { case (address, boxCount) =>
          topAddresses.adjust(address) {
            case None =>
              Address.Stats(height, 1, boxCount)
            case Some(Address.Stats(_, oldTxCount, oldBoxCount)) =>
              Address.Stats(height, oldTxCount + 1, oldBoxCount + boxCount)
          }
        }
      inputBoxes
        .groupBy(_._2)
        .view
        .mapValues(_.map(_._1))
        .foreach { case (address, inputIds) =>
          utxosByAddress.putOrRemove(address) {
            case None                 => None
            case Some(existingBoxIds) => Option(existingBoxIds.removedAll(inputIds)).filter(_.nonEmpty)
          }
        }

      inputBoxes.foreach { i =>
        addressByUtxo.remove(i._1)
      }
    }
    blockIdByHeight.put(height, blockId)
    versionByHeight.put(height, store.commit())
  }

  def getParentOrFail(apiBlock: ApiFullBlock): Try[Option[BlockMetadata]] =
    /** Genesis block has no parent so we assert that any block either has its parent cached or its a first block */
    if (apiBlock.header.height == 1)
      Try(Option.empty)
    else
      Option(blockCacheByHeight.get(apiBlock.header.height - 1))
        .flatMap(_.get(apiBlock.header.parentId))
        .fold(
          Failure(
            new IllegalStateException(
              s"Block ${apiBlock.header.id} at height ${apiBlock.header.height} has missing parent ${apiBlock.header.parentId}"
            )
          )
        )(parent => Try(Option(parent)))

  def addBestBlock(apiFullBlock: ApiFullBlock)(implicit
    ps: ProtocolSettings
  ): Try[BestBlockInserted] =
    getParentOrFail(apiFullBlock)
      .flatMap { parentOpt =>
        BlockBuilder(apiFullBlock, parentOpt)
          .map { b =>
            val outputLookup =
              apiFullBlock.transactions.transactions
                .flatMap(tx => tx.outputs.map(o => (o.boxId, (o.address, o.value))).toMap)
                .toMap

            val txs =
              apiFullBlock.transactions.transactions.zipWithIndex.map { case (tx, txIndex) =>
                val outputs = tx.outputs.map(o => (o.boxId, o.address, o.value))
                val inputs =
                  tx match {
                    case tx if tx.id == Const.Genesis.Emission.tx =>
                      ArraySeq(
                        (Const.Genesis.Emission.box, Const.Genesis.Emission.address, Const.Genesis.Emission.initialNanoErgs)
                      )
                    case tx if tx.id == Const.Genesis.Foundation.tx =>
                      ArraySeq(
                        (
                          Const.Genesis.Foundation.box,
                          Const.Genesis.Foundation.address,
                          Const.Genesis.Foundation.initialNanoErgs
                        )
                      )
                    case tx =>
                      tx.inputs.map { i =>
                        val inputAddress =
                          Option(addressByUtxo.get(i.boxId))
                            .orElse(outputLookup.get(i.boxId).map(_._1))
                            .getOrElse(
                              throw new IllegalStateException(
                                s"BoxId ${i.boxId} of block ${b.header.id} at height ${b.header.height} not found in utxo state" + outputs
                                  .mkString("\n", "\n", "\n")
                              )
                            )
                        val inputValue =
                          Option(utxosByAddress.get(inputAddress))
                            .map(
                              _.getOrElse(
                                i.boxId,
                                throw new IllegalStateException(
                                  s"BoxId ${i.boxId} of block ${b.header.id} at height ${b.header.height} not found in utxo state"
                                )
                              )
                            )
                            .orElse(outputLookup.get(i.boxId).map(_._2))
                            .getOrElse(
                              throw new IllegalStateException(
                                s"Address $inputAddress of block ${b.header.id} at height ${b.header.height} not found in utxo state"
                              )
                            )
                        (i.boxId, inputAddress, inputValue)
                      }
                  }
                Tx(tx.id, txIndex.toShort, b.header.height, b.header.timestamp) -> (inputs, outputs)
              }

            mergeBlockBoxesUnsafe(b.header.height, b.header.id, txs.iterator.map(_._2))
            blockCacheByHeight.adjust(b.header.height)(
              _.fold(Map(b.header.id -> BlockMetadata.fromBlock(b)))(
                _.updated(b.header.id, BlockMetadata.fromBlock(b))
              )
            )
            BestBlockInserted(b, txs)
          }
      }

  def hasParentAndIsChained(fork: List[ApiFullBlock]): Boolean =
    fork.size > 1 &&
      blockCacheByHeight.get(fork.head.header.height - 1).contains(fork.head.header.parentId) &&
      fork.sliding(2).forall {
        case first :: second :: Nil =>
          first.header.id == second.header.parentId
        case _ =>
          false
      }

  def addWinningFork(winningFork: List[ApiFullBlock])(implicit protocol: ProtocolSettings): Try[ForkInserted] =
    if (!hasParentAndIsChained(winningFork)) {
      Failure(
        new UnexpectedStateError(
          s"Inserting fork ${winningFork.map(_.header.id).mkString(",")} at height ${winningFork.map(_.header.height).mkString(",")} illegal"
        )
      )
    } else {
      Try(store.rollbackTo(versionByHeight.get(winningFork.head.header.height - 1)))
        .flatMap { _ =>
          winningFork
            .foldLeft(Try((ListBuffer.empty[BestBlockInserted], ListBuffer.empty[BlockMetadata]))) {
              case (f @ Failure(_), _) =>
                f
              case (Success((insertedBlocksAcc, toRemoveAcc)), apiBlock) =>
                addBestBlock(apiBlock).map { insertedBlock =>
                  val toRemove =
                    toRemoveAcc ++ blockCacheByHeight
                      .get(apiBlock.header.height)
                      .filter(_._1 != apiBlock.header.id)
                      .values
                  (insertedBlocksAcc :+ insertedBlock, toRemove)
                }
            }
            .map { case (newBlocks, supersededBlocks) =>
              ForkInserted(newBlocks.toList, supersededBlocks.toList)
            }
        }
    }

}

object MvUtxoState {
  type Version = Long
  val VersionsToKeep = 10
  val MaxCacheSize   = 10

  def inMemoryUtxoState: MvUtxoState =
    MvUtxoState(Paths.get(System.getProperty("java.io.tmpdir"), Random.nextString(10)).toFile)

  def apply(
    rootDir: File = Paths.get(System.getProperty("user.home"), ".ergo-uexplorer", "utxo").toFile
  ): MvUtxoState = {
    rootDir.mkdirs()
    val store =
      new MVStore.Builder()
        .fileName(rootDir.toPath.resolve("mvstore").toFile.getAbsolutePath)
        .autoCommitDisabled()
        .open()

    store.setVersionsToKeep(VersionsToKeep)
    store.setRetentionTime(3600 * 1000)
    new MvUtxoState(
      store,
      store.openMap[Address, Map[BoxId, Value]]("utxosByAddress"),
      store.openMap[BoxId, Address]("addressByUtxo"),
      store.openMap[Address, Address.Stats]("topAddresses"),
      store.openMap[Height, BlockId]("blockIdByHeight"),
      new ConcurrentSkipListMap[Height, Map[BlockId, BlockMetadata]]()
    )
  }
  implicit class MVMapPimp[K, V](underlying: MVMap[K, V]) {

    def putOrRemove(k: K)(f: Option[V] => Option[V]): MVMap[K, V] =
      f(Option(underlying.get(k))) match {
        case None =>
          underlying.remove(k)
          underlying
        case Some(v) =>
          underlying.put(k, v)
          underlying
      }

    def adjust(k: K)(f: Option[V] => V): MVMap[K, V] = {
      underlying.put(k, f(Option(underlying.get(k))))
      underlying
    }

  }
  implicit class ConcurrentMapPimp[K, V](underlying: ConcurrentSkipListMap[K, V]) {

    def adjust(k: K)(f: Option[V] => V): ConcurrentSkipListMap[K, V] = {
      underlying.put(k, f(Option(underlying.get(k))))
      underlying
    }

  }

}
