package org.ergoplatform.uexplorer.indexer.chain

import org.ergoplatform.uexplorer.{Address, BlockId, BoxId}
import org.ergoplatform.uexplorer.indexer.Const
import org.ergoplatform.uexplorer.indexer.chain.Epoch.*

import scala.collection.immutable.{ArraySeq, TreeMap, TreeSet}
import scala.collection.mutable

trait EpochCandidate {
  def epochIndex: Int

  def isComplete: Boolean
}

case class BlockRel(headerId: BlockId, parentId: BlockId)

case class InvalidEpochCandidate(epochIndex: Int, invalidHeightsAsc: TreeSet[Int], error: String) extends EpochCandidate {
  def isComplete = false
}

case class ValidEpochCandidate(
  epochIndex: Int,
  relsByHeight: TreeMap[Int, BlockRel],
  inputIds: ArraySeq[BoxId],
  utxosByAddress: Map[Address, mutable.Map[BoxId, Long]]
) extends EpochCandidate {
  def isComplete = true

  def getEpoch: Epoch = Epoch(epochIndex, relsByHeight.toVector.map(_._2.headerId), inputIds, utxosByAddress)
}

object EpochCandidate {

  def apply(
    rels: Seq[(Int, BlockRel)],
    inputIds: ArraySeq[BoxId],
    utxosByAddress: Map[Address, mutable.Map[BoxId, Long]]
  ): Either[InvalidEpochCandidate, ValidEpochCandidate] = {
    val sortedRels         = TreeMap[Int, BlockRel](rels: _*)
    val epochIndex         = sortedRels.headOption.map(tuple => epochIndexForHeight(tuple._1)).getOrElse(-1)
    val epochRange         = TreeSet(heightRangeForEpochIndex(epochIndex): _*)
    lazy val relationships = sortedRels.toSeq.sliding(2)
    lazy val invalidRels   = relationships.filterNot(rels => rels.head._2.headerId == rels.last._2.parentId).toVector
    if (epochIndex < 0) {
      Left(
        InvalidEpochCandidate(epochIndex, invalidHeightsAsc = TreeSet.empty, error = s"Empty epoch")
      )
    } else if (sortedRels.size != Const.EpochLength) {
      val missingHeights = epochRange.diff(sortedRels.keySet)
      Left(
        InvalidEpochCandidate(
          epochIndex,
          missingHeights,
          s"Epoch $epochIndex has invalid size ${sortedRels.size}, heights : ${sortedRels.keys.mkString("\n", ", ", "")}"
        )
      )
    } else if (sortedRels.keySet != epochRange) {
      val missingHeights = epochRange.diff(sortedRels.keySet)
      Left(
        InvalidEpochCandidate(
          epochIndex,
          missingHeights,
          s"Epoch $epochIndex heights are not sequential : ${sortedRels.keys.mkString("\n", ", ", "")}"
        )
      )
    } else if (invalidRels.nonEmpty) {
      val invalidHeights = TreeSet(invalidRels.flatMap(_.map(_._1)): _*)
      Left(
        InvalidEpochCandidate(
          epochIndex,
          invalidHeights,
          s"Epoch $epochIndex child-parent relationship broken : $invalidRels"
        )
      )
    } else {
      Right(ValidEpochCandidate(epochIndex, sortedRels, inputIds, utxosByAddress))
    }
  }
}
