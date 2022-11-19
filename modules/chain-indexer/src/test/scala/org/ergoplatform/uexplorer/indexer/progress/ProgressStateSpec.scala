package org.ergoplatform.uexplorer.indexer.progress

import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import io.circe.parser.*
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.uexplorer.db.Block
import org.ergoplatform.uexplorer.indexer.config.{ChainIndexerConf, ProtocolSettings}
import org.ergoplatform.uexplorer.indexer.db.BlockBuilder
import org.ergoplatform.uexplorer.indexer.parser.ErgoTreeParser
import org.ergoplatform.uexplorer.indexer.progress.ProgressState.*
import org.ergoplatform.uexplorer.indexer.{Rest, UnexpectedStateError}
import org.ergoplatform.uexplorer.node.ApiFullBlock
import org.ergoplatform.uexplorer.{Address, BlockId}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.TreeMap

class ProgressStateSpec extends AnyFreeSpec with Matchers with DiffShouldMatcher {

  implicit private val protocol: ProtocolSettings = ChainIndexerConf.loadDefaultOrThrow.protocol
  implicit private val e: ErgoAddressEncoder      = protocol.addressEncoder

  private def getBlock(height: Int): ApiFullBlock =
    parse(Rest.blocks.byHeight(height)).flatMap(_.as[ApiFullBlock]).toOption.get

  private def forkBlock(
    apiFullBlock: ApiFullBlock,
    newBlockId: String,
    parentIdOpt: Option[BlockId] = None
  ): ApiFullBlock = {
    import monocle.syntax.all._
    apiFullBlock
      .focus(_.header.id)
      .modify(_ => BlockId.fromStringUnsafe(newBlockId))
      .focus(_.header.parentId)
      .modify(parentId => parentIdOpt.getOrElse(parentId))
  }

  val lastBlockInfoByEpochIndex =
    "ProgressState state should" - {
      "allow for updating epoch indexes" - {
        "when has epochs" in {
          val e0b1Block     = BlockBuilder(getBlock(1023), None).get
          val e0b2Block     = BlockBuilder(getBlock(1024), Option(BufferedBlockInfo.fromBlock(e0b1Block))).get
          val e0b2BlockInfo = BufferedBlockInfo.fromBlock(e0b2Block)

          val e1b1Block     = BlockBuilder(getBlock(2047), None).get
          val e1b2Block     = BlockBuilder(getBlock(2048), Option(BufferedBlockInfo.fromBlock(e1b1Block))).get
          val e1b2BlockInfo = BufferedBlockInfo.fromBlock(e1b2Block)

          val e0In = List(e0b1Block, e0b2Block).flatMap(_.inputs.map(_.boxId))
          val e1In = List(e1b1Block, e1b2Block).flatMap(_.inputs.map(_.boxId))

          val e0Out = List(e0b1Block, e0b2Block).flatMap(_.outputs.map(b => (b.boxId, b.address, b.value)))
          val e1Out = List(e1b1Block, e1b2Block).flatMap(_.outputs.map(b => (b.boxId, b.address, b.value)))

          val lastBlockIdByEpochIndex = TreeMap(0 -> e0b2BlockInfo, 1 -> e1b2BlockInfo)
          val utxos                   = (e0Out ++ e1Out).filterNot(b => e0In.contains(b._1) || e1In.contains(b._1))

          val utxoState =
            UtxoState(
              TreeMap.empty,
              utxos.map(o => o._1 -> o._2).toMap,
              utxos.groupBy(_._2).view.mapValues(_.map(o => o._1 -> o._3).toMap).toMap,
              (e0In ++ e1In).filterNot(b => e0Out.map(_._1).contains(b) || e1Out.map(_._1).contains(b)).toSet
            )
          val actualProgressState = ProgressState.load(
            lastBlockIdByEpochIndex,
            utxoState
          )
          actualProgressState shouldBe ProgressState(
            lastBlockIdByEpochIndex.map { case (k, v) => k -> v.headerId },
            TreeMap.empty,
            BlockBuffer(
              Map(e0b2Block.header.id -> e0b2BlockInfo, e1b2Block.header.id -> e1b2BlockInfo),
              TreeMap(1024            -> e0b2BlockInfo, 2048                -> e1b2BlockInfo)
            ),
            utxoState
          )
        }
      }
      "throw when inserting block without parent being applied first" in {
        assertThrows[UnexpectedStateError](ProgressState.empty.insertBestBlock(getBlock(1025)).get)

      }
      "allow for inserting new block" - {
        "after genesis" in {
          val firstApiBlock             = getBlock(1)
          val firstFlatBlock            = BlockBuilder(firstApiBlock, None).get
          val (blockInserted, newState) = ProgressState.empty.insertBestBlock(firstApiBlock).get
          blockInserted.flatBlock shouldBe firstFlatBlock
          newState shouldBe ProgressState(
            TreeMap.empty,
            TreeMap.empty,
            BlockBuffer(
              Map(firstApiBlock.header.id -> BufferedBlockInfo.fromBlock(firstFlatBlock)),
              TreeMap(1                   -> BufferedBlockInfo.fromBlock(firstFlatBlock))
            ),
            newState.utxoState
          )
        }
        "after an existing block" in {
          val e0b1Block               = BlockBuilder(getBlock(1024), None).get
          val e0b1Info                = BufferedBlockInfo.fromBlock(e0b1Block)
          val lastBlockIdByEpochIndex = TreeMap(0 -> e0b1Info)

          val utxos =
            e0b1Block.outputs
              .map(b => (b.boxId, b.address, b.value))
              .filterNot(b => e0b1Block.inputs.map(_.boxId).contains(b._1))
          val utxoState =
            UtxoState(
              TreeMap.empty,
              utxos.map(o => o._1 -> o._2).toMap,
              utxos.groupBy(_._2).view.mapValues(_.map(o => o._1 -> o._3).toMap).toMap,
              e0b1Block.inputs.map(_.boxId).filterNot(b => e0b1Block.outputs.map(_._1).contains(b)).toSet
            )
          val newState = ProgressState.load(lastBlockIdByEpochIndex, utxoState)
          newState shouldBe ProgressState(
            lastBlockIdByEpochIndex.map { case (k, v) => k -> v.headerId },
            TreeMap.empty,
            BlockBuffer(
              Map(e0b1Block.header.id -> e0b1Info),
              TreeMap(1024            -> e0b1Info)
            ),
            utxoState
          )

          val e1b1                       = getBlock(1025)
          val e1b1Block                  = BlockBuilder(e1b1, Some(e0b1Info)).get
          val e1b1Info                   = BufferedBlockInfo.fromBlock(e1b1Block)
          val (blockInserted, newState2) = newState.insertBestBlock(e1b1).get
          blockInserted.flatBlock shouldBe e1b1Block
          newState2 shouldBe ProgressState(
            TreeMap(0 -> e0b1Info.headerId),
            TreeMap.empty,
            BlockBuffer(
              Map(e1b1Block.header.id -> e1b1Info, e0b1Block.header.id -> e0b1Info),
              TreeMap(1024            -> e0b1Info, 1025                -> e1b1Info)
            ),
            newState2.utxoState
          )
        }
      }

      "throw when inserting an empty fork, one-sized fork or unchained fork" in {
        assertThrows[UnexpectedStateError](ProgressState.empty.insertWinningFork(List.empty).get)
        assertThrows[UnexpectedStateError](ProgressState.empty.insertWinningFork(List(getBlock(1024))).get)
        assertThrows[UnexpectedStateError](ProgressState.empty.insertWinningFork(List(getBlock(1024), getBlock(1026))).get)
      }

      "allow for inserting new fork" in {
        val commonBlock     = BlockBuilder(getBlock(1024), None).get
        val commonBlockInfo = BufferedBlockInfo.fromBlock(commonBlock)
        val utxos = commonBlock.outputs
          .map(b => (b.boxId, b.address, b.value))
          .filterNot(b => commonBlock.inputs.map(_.boxId).contains(b._1))

        val utxoState =
          UtxoState(
            TreeMap.empty,
            utxos.map(o => o._1 -> o._2).toMap,
            utxos.groupBy(_._2).view.mapValues(_.map(o => o._1 -> o._3).toMap).toMap,
            Set.empty
          )
        val s               = ProgressState.load(TreeMap(0 -> commonBlockInfo), utxoState)
        val b1ApiBlock      = getBlock(1025)
        val b1FlatBlock     = BlockBuilder(b1ApiBlock, Option(commonBlockInfo)).get
        val b1FlatBlockInfo = BufferedBlockInfo.fromBlock(b1FlatBlock)
        val b2ApiBlock      = getBlock(1026)
        val b2FlatBlock     = BlockBuilder(b2ApiBlock, Option(b1FlatBlockInfo)).get
        val b2FlatBlockInfo = BufferedBlockInfo.fromBlock(b2FlatBlock)
        val b3              = getBlock(1027)
        val b3FlatBlock     = BlockBuilder(b3, Option(b2FlatBlockInfo)).get
        val b3FlatBlockInfo = BufferedBlockInfo.fromBlock(b3FlatBlock)
        val b1Fork          = forkBlock(b1ApiBlock, "7975b60515b881504ec471affb84234123ac5491d0452da0eaf5fb96948f18e7")
        val b1ForkFlatBlock = BlockBuilder(b1Fork, Option(commonBlockInfo)).get
        val b2Fork =
          forkBlock(b2ApiBlock, "4077fcf3359c15c3ad3797a78fff342166f09a7f1b22891a18030dcd8604b087", Option(b1Fork.header.id))
        val b2ForkFlatBlock           = BlockBuilder(b2Fork, Option(BufferedBlockInfo.fromBlock(b1ForkFlatBlock))).get
        val (_, s2)                   = s.insertBestBlock(b1Fork).get
        val (_, s3)                   = s2.insertBestBlock(b2Fork).get
        val (forkInserted, newState4) = s3.insertWinningFork(List(b1ApiBlock, b2ApiBlock, b3)).get
        forkInserted.newFork.size shouldBe 3
        forkInserted.supersededFork.size shouldBe 2
        forkInserted.newFork shouldBe List(b1FlatBlock, b2FlatBlock, b3FlatBlock)
        forkInserted.supersededFork shouldBe List(
          BufferedBlockInfo.fromBlock(b1ForkFlatBlock),
          BufferedBlockInfo.fromBlock(b2ForkFlatBlock)
        )
        newState4 shouldBe ProgressState(
          TreeMap(0 -> commonBlock.header.id),
          TreeMap.empty,
          BlockBuffer(
            Map(
              commonBlock.header.id -> commonBlockInfo,
              b1ApiBlock.header.id  -> b1FlatBlockInfo,
              b2ApiBlock.header.id  -> b2FlatBlockInfo,
              b3.header.id          -> b3FlatBlockInfo
            ),
            TreeMap(
              1024 -> commonBlockInfo,
              1025 -> b1FlatBlockInfo,
              1026 -> b2FlatBlockInfo,
              1027 -> b3FlatBlockInfo
            )
          ),
          newState4.utxoState
        )
      }
    }
}
