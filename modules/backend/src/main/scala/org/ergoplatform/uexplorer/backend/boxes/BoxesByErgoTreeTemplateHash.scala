package org.ergoplatform.uexplorer.backend.boxes

import org.ergoplatform.uexplorer.BlockId.unwrapped
import org.ergoplatform.uexplorer.BoxId.unwrapped
import org.ergoplatform.uexplorer.backend.{Codecs, ErrorResponse}
import org.ergoplatform.uexplorer.db.{Asset2Box, Block, Box, Utxo}
import org.ergoplatform.uexplorer.{Address, BlockId, BoxId, TxId}
import sttp.model.{QueryParams, StatusCode}
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import sttp.tapir.{queryParams, PublicEndpoint, Schema}
import zio.*
import zio.http.{HttpApp, Server}
import zio.json.*
import org.ergoplatform.uexplorer.backend.IdParsingException

trait BoxesByErgoTreeTemplateHash extends Codecs:

  protected[backend] val spentTemplateBoxesByErgoTreeHash: PublicEndpoint[(String, QueryParams), (ErrorResponse, StatusCode), Iterable[Box], Any] =
    endpoint.get
      .in("boxes" / "spent" / "templates" / "by-ergo-tree-hash" / path[String]("ergoTreeT8Hash"))
      .in(queryParams)
      .errorOut(jsonBody[ErrorResponse])
      .errorOut(statusCode)
      .out(jsonBody[Iterable[Box]])
      .description("Get spent boxes by ErgoTree template hash (base16 of Sha256)")

  protected[backend] val spentTemplateBoxesByErgoTreeHashEndpoint: ZServerEndpoint[BoxService, Any] =
    spentTemplateBoxesByErgoTreeHash.zServerLogic { case (ergoTreeT8Hash, qp) =>
      BoxService
        .getSpentBoxesByErgoTreeT8Hash(ergoTreeT8Hash, qp.toMap)
        .mapError(handleThrowable)
    }

  protected[backend] val spentTemplateBoxIdsByErgoTreeHash: PublicEndpoint[(String, QueryParams), (ErrorResponse, StatusCode), Iterable[BoxId], Any] =
    endpoint.get
      .in("box-ids" / "spent" / "templates" / "by-ergo-tree-hash" / path[String]("ergoTreeT8Hash"))
      .in(queryParams)
      .errorOut(jsonBody[ErrorResponse])
      .errorOut(statusCode)
      .out(jsonBody[Iterable[BoxId]])
      .description("Get spent box IDs by ErgoTree template hash (base16 of Sha256)")

  protected[backend] val spentTemplateBoxIdsByErgoTreeHashEndpoint: ZServerEndpoint[BoxService, Any] =
    spentTemplateBoxIdsByErgoTreeHash.zServerLogic { case (ergoTreeT8Hash, qp) =>
      BoxService
        .getSpentBoxesByErgoTreeT8Hash(ergoTreeT8Hash, qp.toMap)
        .map(_.map(_.boxId))
        .mapError(handleThrowable)
    }

  protected[backend] val unspentTemplateBoxesByErgoTreeHash: PublicEndpoint[(String, QueryParams), (ErrorResponse, StatusCode), Iterable[Utxo], Any] =
    endpoint.get
      .in("boxes" / "unspent" / "templates" / "by-ergo-tree-hash" / path[String]("ergoTreeT8Hash"))
      .in(queryParams)
      .errorOut(jsonBody[ErrorResponse])
      .errorOut(statusCode)
      .out(jsonBody[Iterable[Utxo]])
      .description("Get unspent boxes by ErgoTree template hash (base16 of Sha256)")

  protected[backend] val unspentTemplateBoxesByErgoTreeHashEndpoint: ZServerEndpoint[BoxService, Any] =
    unspentTemplateBoxesByErgoTreeHash.zServerLogic { case (ergoTreeT8Hash, qp) =>
      BoxService
        .getUnspentBoxesByErgoTreeT8Hash(ergoTreeT8Hash, qp.toMap)
        .mapError(handleThrowable)
    }

  protected[backend] val unspentTemplateBoxIdsByErgoTreeHash: PublicEndpoint[(String, QueryParams), (ErrorResponse, StatusCode), Iterable[BoxId], Any] =
    endpoint.get
      .in("box-ids" / "unspent" / "templates" / "by-ergo-tree-hash" / path[String]("ergoTreeT8Hash"))
      .in(queryParams)
      .errorOut(jsonBody[ErrorResponse])
      .errorOut(statusCode)
      .out(jsonBody[Iterable[BoxId]])
      .description("Get unspent box IDs by ErgoTree template hash (base16 of Sha256)")

  protected[backend] val unspentTemplateBoxIdsByErgoTreeHashEndpoint: ZServerEndpoint[BoxService, Any] =
    unspentTemplateBoxIdsByErgoTreeHash.zServerLogic { case (ergoTreeT8Hash, qp) =>
      BoxService
        .getUnspentBoxesByErgoTreeT8Hash(ergoTreeT8Hash, qp.toMap)
        .map(_.map(_.boxId))
        .mapError(handleThrowable)

    }

  protected[backend] val anyTemplateBoxesByErgoTreeHash: PublicEndpoint[(String, QueryParams), (ErrorResponse, StatusCode), Iterable[Box], Any] =
    endpoint.get
      .in("boxes" / "any" / "templates" / "by-ergo-tree-hash" / path[String]("ergoTreeT8Hash"))
      .in(queryParams)
      .errorOut(jsonBody[ErrorResponse])
      .errorOut(statusCode)
      .out(jsonBody[Iterable[Box]])
      .description("Get any boxes by ErgoTree template hash (base16 of Sha256)")

  protected[backend] val anyTemplateBoxesByErgoTreeHashEndpoint: ZServerEndpoint[BoxService, Any] =
    anyTemplateBoxesByErgoTreeHash.zServerLogic { case (ergoTreeT8Hash, qp) =>
      BoxService
        .getAnyBoxesByErgoTreeT8Hash(ergoTreeT8Hash, qp.toMap)
        .mapError(handleThrowable)
    }

  protected[backend] val anyTemplateBoxIdsByErgoTreeHash: PublicEndpoint[(String, QueryParams), (ErrorResponse, StatusCode), Iterable[BoxId], Any] =
    endpoint.get
      .in("box-ids" / "any" / "templates" / "by-ergo-tree-hash" / path[String]("ergoTreeT8Hash"))
      .in(queryParams)
      .errorOut(jsonBody[ErrorResponse])
      .errorOut(statusCode)
      .out(jsonBody[Iterable[BoxId]])
      .description("Get any box IDs by ErgoTree template hash (base16 of Sha256)")

  protected[backend] val anyTemplateBoxIdsByErgoTreeHashEndpoint: ZServerEndpoint[BoxService, Any] =
    anyTemplateBoxIdsByErgoTreeHash.zServerLogic { case (ergoTreeT8Hash, qp) =>
      BoxService
        .getAnyBoxesByErgoTreeT8Hash(ergoTreeT8Hash, qp.toMap)
        .map(_.map(_.boxId))
        .mapError(handleThrowable)
    }
