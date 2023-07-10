package org.ergoplatform.uexplorer.indexer

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.Source
import akka.stream.{KillSwitches, SharedKillSwitch}
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.uexplorer.backend.H2Backend
import org.ergoplatform.uexplorer.db.UtxoTracker
import org.ergoplatform.uexplorer.http.*
import org.ergoplatform.uexplorer.indexer.chain.*
import org.ergoplatform.uexplorer.indexer.chain.Initializer.ChainEmpty
import org.ergoplatform.uexplorer.indexer.config.ChainIndexerConf
import org.ergoplatform.uexplorer.indexer.mempool.MempoolStateHolder.MempoolState
import org.ergoplatform.uexplorer.indexer.mempool.{MempoolStateHolder, MempoolSyncer}
import org.ergoplatform.uexplorer.indexer.plugin.PluginManager
import org.ergoplatform.uexplorer.janusgraph.api.InMemoryGraphBackend
import org.ergoplatform.uexplorer.parser.ErgoTreeParser
import org.ergoplatform.uexplorer.storage.{MvStorage, MvStoreConf}
import org.ergoplatform.uexplorer.{ProtocolSettings, Storage}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import sttp.capabilities.WebSockets
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub

import java.nio.file.Paths
import scala.collection.immutable.{ListMap, TreeMap}
import scala.concurrent.Future
import scala.concurrent.duration.*

class SchedulerSpec extends AsyncFreeSpec with TestSupport with Matchers with BeforeAndAfterAll with ScalaFutures {

  private val (conf, config) = ChainIndexerConf.loadDefaultOrThrow
  private val testKit        = ActorTestKit(config)

  implicit private val protocol: ProtocolSettings               = conf.protocol
  implicit private val sys: ActorSystem[_]                      = testKit.internalSystem
  implicit private val enc: ErgoAddressEncoder                  = protocol.addressEncoder
  implicit private val localNodeUriMagnet: LocalNodeUriMagnet   = LocalNodeUriMagnet(uri"http://local")
  implicit private val remoteNodeUriMagnet: RemoteNodeUriMagnet = RemoteNodeUriMagnet(uri"http://remote")
  implicit val killSwitch: SharedKillSwitch                     = KillSwitches.shared("scheduler-kill-switch")

  override def afterAll(): Unit = {
    super.afterAll()
    sys.terminate()
  }

  implicit val mempoolSyncerSyncerRef: ActorRef[MempoolStateHolder.MempoolStateHolderRequest] =
    testKit.spawn(MempoolStateHolder.behavior(MempoolState(ListMap.empty)), "MempoolSyncer")

  implicit val testingBackend: SttpBackendStub[Future, WebSockets] = SttpBackendStub.asynchronousFuture
    .whenRequestMatches { r =>
      r.uri.path.endsWith(List("info"))
    }
    .thenRespondCyclicResponses(
      (1 to 3).map(_ => Response.ok(getPeerInfo(Rest.info.poll))): _*
    )
    .whenRequestMatchesPartial {
      case r if r.uri.path.endsWith(List("transactions", "unconfirmed")) =>
        Response.ok(getUnconfirmedTxs)
      case r if r.uri.path.endsWith(List("peers", "connected")) =>
        Response.ok(getConnectedPeers)
      case r if r.uri.path.startsWith(List("blocks", "at")) =>
        val chainHeight = r.uri.path.last.toInt
        Response.ok(s"""["${Rest.blockIds.byHeight(chainHeight)}"]""")
      case r if r.uri.path.startsWith(List("blocks")) && r.uri.params.get("offset").isDefined =>
        val offset = r.uri.params.get("offset").get.toInt
        val limit  = r.uri.params.get("limit").getOrElse("50").toInt
        Response.ok(Rest.blocks.forOffset(offset, limit).map(blockId => s""""$blockId"""") mkString ("[", ",", "]"))
      case r if r.uri.path.startsWith(List("blocks")) =>
        val blockId = r.uri.path.last
        Response.ok(Rest.blocks.byId(blockId))
    }

  val mvStoreConf     = MvStoreConf(10, 500.millis, 500.millis, 10000)
  val storage         = MvStorage(64).get
  val pluginManager   = new PluginManager(List.empty)
  val storageService  = StorageService(storage, mvStoreConf)
  val blockHttpClient = new BlockHttpClient(new MetadataHttpClient[WebSockets](minNodeHeight = Rest.info.minNodeHeight))
  val backend         = H2Backend(sys).get
  val graphBackend    = Some(new InMemoryGraphBackend)
  val blockReader     = new BlockReader(blockHttpClient)
  val blockWriter     = new BlockWriter(storage, storageService, mvStoreConf, backend, graphBackend)
  val streamExecutor  = new StreamExecutor(false, blockHttpClient, blockReader, blockWriter, storage)
  val mempoolSyncer   = new MempoolSyncer(blockHttpClient)
  val initializer     = new Initializer(storage, backend, graphBackend)
  val scheduler       = new Scheduler(pluginManager, streamExecutor, mempoolSyncer, initializer)

  "Scheduler should sync from 1 to 4200" in {
    initializer.init.flatMap { state =>
      state shouldBe ChainEmpty
      scheduler.periodicSync.map { newMempoolState =>
        storage.getLastHeight.get shouldBe 4200
        storage.findMissingHeights shouldBe empty
        newMempoolState.stateTransitionByTx.size shouldBe 9
      }
    }
  }
}
