package org.ergoplatform.uexplorer.plugin

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.ergoplatform.uexplorer.db.{BestBlockInserted, Block}
import org.ergoplatform.uexplorer.node.ApiTransaction
import org.ergoplatform.uexplorer.{Address, BoxId, SortedTopAddressMap, Storage, TopAddressMap, TxId, Value}

import scala.collection.immutable.ListMap
import scala.concurrent.Future
import scala.util.Try

trait Plugin {

  def name: String

  def init: Future[Unit]

  def close: Future[Unit]

  def processMempoolTx(
    newTx: ApiTransaction,
    storage: Storage,
    graphTraversalSource: Option[GraphTraversalSource]
  ): Future[Unit]

  def processNewBlock(
    newBlock: BestBlockInserted,
    storage: Storage,
    graphTraversalSource: Option[GraphTraversalSource]
  ): Future[Unit]
}
