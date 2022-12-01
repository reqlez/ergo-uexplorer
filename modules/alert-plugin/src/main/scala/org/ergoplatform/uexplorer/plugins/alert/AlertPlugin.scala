package org.ergoplatform.uexplorer.plugins.alert

import org.ergoplatform.uexplorer.{Address, BoxId, TxId}
import org.ergoplatform.uexplorer.node.ApiTransaction
import org.ergoplatform.uexplorer.plugin.Plugin
import scala.concurrent.Future
import scala.collection.mutable

class AlertPlugin extends Plugin {

  def execute(
    newMempoolTxs: Map[TxId, ApiTransaction],
    addressByUtxo: Map[BoxId, Address],
    utxosByAddress: Map[Address, mutable.Map[BoxId, Long]]
  ): Future[Unit] =
    Future.successful(println("blaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))

}
