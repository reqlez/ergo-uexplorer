package org.ergoplatform.uexplorer.indexer.config

import cats.data.NonEmptyList
import cats.syntax.list.*
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import com.typesafe.scalalogging.LazyLogging
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.refineV
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.mining.emission.EmissionRules
import org.ergoplatform.settings.MonetarySettings
import org.ergoplatform.uexplorer.{Address, NetworkPrefix}
import org.ergoplatform.uexplorer.indexer.http.{LocalNodeUriMagnet, RemoteNodeUriMagnet}
import pureconfig.ConfigReader.Result
import pureconfig.error.CannotConvert
import pureconfig.{ConfigReader, ConfigSource}
import sttp.model.Uri
import pureconfig.generic.derivation.default.*

import java.io.File

final case class ProtocolSettings(
                                   networkPrefix: NetworkPrefix,
                                   genesisAddress: Address,
                                 )  derives ConfigReader {

  val monetary = MonetarySettings()
  val emission = new EmissionRules(monetary)

  val addressEncoder: ErgoAddressEncoder =
    ErgoAddressEncoder(networkPrefix.value.toByte)
}
object ProtocolSettings {

  implicit def addrConfigReader: ConfigReader[Address] =
    implicitly[ConfigReader[String]].map(Address.fromStringUnsafe)

  implicit def netConfigReader: ConfigReader[NetworkPrefix] =
    implicitly[ConfigReader[String]].map(NetworkPrefix.fromStringUnsafe)

}

sealed trait BackendType derives ConfigReader

case class CassandraDb(parallelism: Int) extends BackendType
case object InMemoryDb extends BackendType

case class ChainIndexerConf(
  nodeAddressToInitFrom: Uri,
  peerAddressToPollFrom: Uri,
  backendType: BackendType,
  protocol: ProtocolSettings
) derives ConfigReader {
  def remoteUriMagnet: RemoteNodeUriMagnet = RemoteNodeUriMagnet(peerAddressToPollFrom)
  def localUriMagnet: LocalNodeUriMagnet   = LocalNodeUriMagnet(nodeAddressToInitFrom)
}

object ChainIndexerConf extends LazyLogging {

  implicit def nelReader[A: ConfigReader]: ConfigReader[NonEmptyList[A]] =
    implicitly[ConfigReader[List[A]]].emap { list =>
      list.toNel.toRight(CannotConvert(list.toString, s"NonEmptyList", "List is empty"))
    }

  implicit def uriConfigReader(implicit cr: ConfigReader[String]): ConfigReader[Uri] =
    cr.emap(addr => Uri.parse(addr).left.map(r => CannotConvert(addr, "Uri", r)))

  lazy val loadDefaultOrThrow: ChainIndexerConf =
    ConfigSource.default.at("uexplorer.chain-indexer").loadOrThrow[ChainIndexerConf]

  lazy val loadWithFallback: Result[(ChainIndexerConf, Config)] = {
    def formatting(formatted: Boolean) = ConfigRenderOptions.concise().setFormatted(formatted).setJson(true)
    val rootConfig =
      ConfigFactory
        .parseFile(new File("conf/chain-indexer.conf"))
        .withFallback(ConfigFactory.load())
        .resolve()

    val chainIndexerConf =
      rootConfig.getValue("uexplorer.chain-indexer").render(formatting(true))
    val cassandraContactPoints =
      rootConfig.getValue("datastax-java-driver.basic.contact-points").render(formatting(false))
    logger.info(s"ChainIndexer config: $chainIndexerConf")
    logger.info(s"Cassandra contact points: $cassandraContactPoints")

    ConfigSource
      .file("conf/chain-indexer.conf")
      .withFallback(ConfigSource.default)
      .at("uexplorer.chain-indexer")
      .load[ChainIndexerConf]
      .map(_ -> rootConfig)
  }
}
