package org.ergoplatform.uexplorer.indexer

import com.zaxxer.hikari.HikariDataSource
import org.ergoplatform.uexplorer.{BlockId, ReadableStorage}
import org.ergoplatform.uexplorer.backend.PersistentRepo
import org.ergoplatform.uexplorer.backend.blocks.PersistentBlockRepo
import org.ergoplatform.uexplorer.backend.boxes.PersistentBoxRepo
import org.ergoplatform.uexplorer.db.GraphBackend
import org.ergoplatform.uexplorer.http.*
import org.ergoplatform.uexplorer.http.Rest.Blocks
import org.ergoplatform.uexplorer.indexer.chain.*
import org.ergoplatform.uexplorer.indexer.config.ChainIndexerConf
import org.ergoplatform.uexplorer.indexer.mempool.{MemPool, MempoolSyncer}
import org.ergoplatform.uexplorer.indexer.plugin.PluginManager
import org.ergoplatform.uexplorer.storage.{MvStorage, MvStoreConf}
import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import zio.*
import zio.test.*

object StreamSchedulerSpec extends ZIOSpecDefault with TestSupport with SpecZioLayers {

  private val regularBlocks     = Rest.Blocks.regular
  private val forkShorterBlocks = Rest.Blocks.forkShorter
  private val forkLongerBlocks  = Rest.Blocks.forkLoner

  def backendLayer(blocks: Blocks): ZLayer[Any, Nothing, UnderlyingBackend] =
    ZLayer.scoped(
      ZIO.acquireRelease(
        ZIO.succeed(
          UnderlyingBackend(
            HttpClientZioBackend.stub
              .whenRequestMatches(_.uri.path.endsWith(List("info")))
              .thenRespond(Response.ok(getPeerInfo(Rest.info.poll)))
              .whenRequestMatchesPartial {
                case r if r.uri.path.endsWith(List("transactions", "unconfirmed")) =>
                  Response.ok(getUnconfirmedTxs)
                case r if r.uri.path.endsWith(List("peers", "connected")) =>
                  Response.ok(getConnectedPeers)
                case r if r.uri.path.startsWith(List("blocks")) && r.uri.params.get("offset").isDefined =>
                  Response.ok(
                    blocks
                      .forOffset(r.uri.params.get("offset").get.toInt, r.uri.params.get("limit").getOrElse("50").toInt)
                      .map(blockId => s""""$blockId"""") mkString ("[", ",", "]")
                  )
                case r if r.uri.path.startsWith(List("blocks")) =>
                  blocks.byId
                    .get(BlockId.fromStringUnsafe(r.uri.path.last))
                    .fold(Response("", StatusCode.NotFound))(r => Response.ok(r))
              }
          )
        )
      )(b => ZIO.log(s"Closing sttp backend") *> ZIO.succeed(b.backend.close()))
    )

  def spec =
    suite("meta")(
      test("Regular sync") {
        (for {
          _              <- ZIO.serviceWithZIO[StreamScheduler](_.validateAndSchedule(Schedule.once))
          lastHeight     <- ZIO.serviceWith[ReadableStorage](_.getLastHeight)
          missingHeights <- ZIO.serviceWith[ReadableStorage](_.findMissingHeights)
          chainTip       <- ZIO.serviceWithZIO[ReadableStorage](_.getChainTip)
          latestBlock    <- chainTip.latestBlock
          latestBlocks   <- chainTip.toMap
          memPoolState   <- ZIO.serviceWithZIO[MemPool](_.getTxs)
        } yield assertTrue(
          lastHeight.get == 4200,
          missingHeights.isEmpty,
          latestBlocks.size == 100,
          latestBlock.map(_.height).contains(4200),
          memPoolState.underlyingTxs.size == 9
        )).provide(backendLayer(regularBlocks) >+> dbPerEachRun >+> allLayers)
      },
      test("Forked sync") {
        for {
          _ <- ZIO.serviceWithZIO[StreamScheduler](_.validateAndSchedule(Schedule.once)).provide(backendLayer(forkShorterBlocks) >+> dbPerJvmRun >+> allLayers)
          _ <- ZIO.serviceWithZIO[StreamScheduler](_.validateAndSchedule(Schedule.once)).provide(backendLayer(forkLongerBlocks) >+> dbPerJvmRun >+> allLayers)
        } yield assertTrue(true)
      }
    )
}
