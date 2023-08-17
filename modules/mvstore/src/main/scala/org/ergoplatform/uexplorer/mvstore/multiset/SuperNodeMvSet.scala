package org.ergoplatform.uexplorer.mvstore.multiset

import org.ergoplatform.uexplorer.mvstore.*
import org.ergoplatform.uexplorer.mvstore.SuperNodeCounter.HotKey
import org.h2.mvstore.db.NullValueDataType
import org.h2.mvstore.{MVMap, MVStore}
import org.h2.value.Value
import zio.{Task, ZIO}

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class SuperNodeMvSet[HK, C[_], V](
  id: String,
  superNodeCollector: SuperNodeCollector[HK],
  existingMapsByHotKey: concurrent.Map[HK, MVMap[V, Value]]
)(implicit store: MVStore, codec: SuperNodeSetCodec[C, V], vc: ValueCodec[SuperNodeCounter])
  extends SuperNodeSetLike[HK, C, V] {

  private lazy val counterByHotKey = new MvMap[HK, SuperNodeCounter](s"$id-counter")

  private def collectReadHotKey(k: HK): SuperNodeCounter =
    counterByHotKey.adjust(k)(_.fold(SuperNodeCounter(1, 1, 0, 0)) { case SuperNodeCounter(writeOps, readOps, added, removed) =>
      SuperNodeCounter(writeOps + 1, readOps + 1, added, removed)
    })

  private def collectInsertedHotKey(k: HK, size: Int): SuperNodeCounter =
    counterByHotKey.adjust(k)(_.fold(SuperNodeCounter(1, 0, size, 0)) { case SuperNodeCounter(writeOps, readOps, added, removed) =>
      SuperNodeCounter(writeOps + 1, readOps, added + size, removed)
    })

  private def collectRemovedHotKey(k: HK, size: Int): Option[SuperNodeCounter] =
    counterByHotKey.removeOrUpdate(k) { case SuperNodeCounter(writeOps, readOps, added, removed) =>
      Some(SuperNodeCounter(writeOps + 1, readOps, added, removed + size))
    }

  def clearEmptySuperNodes: Task[Unit] = {
    val emptyMaps =
      existingMapsByHotKey
        .foldLeft(Set.newBuilder[HK]) {
          case (acc, (hotKey, map)) if map.isEmpty =>
            acc.addOne(hotKey)
          case (acc, _) =>
            acc
        }
        .result()
    ZIO.log(s"$id contains ${existingMapsByHotKey.size} supernodes") *>
    ZIO.log(s"Going to remove ${emptyMaps.size} empty $id supernode maps") *>
    ZIO.attempt(
      emptyMaps
        .foreach { hk =>
          existingMapsByHotKey
            .remove(hk)
            .foreach(store.removeMap)
        }
    )
  }

  def getReport: (Path, Vector[HotKey]) =
    superNodeCollector
      .filterAndSortHotKeys(counterByHotKey.iterator(None, None, false))

  def putAllNewOrFail(hotKey: HK, values: IterableOnce[V], size: Int): Option[Try[Unit]] =
    superNodeCollector
      .getHotKeyString(hotKey)
      .map { superNodeName =>
        val replacedValueOpt =
          existingMapsByHotKey.get(hotKey) match {
            case None =>
              val newSuperNodeMap: MVMap[V, Value] =
                store.openMap(
                  superNodeName,
                  MVMap.Builder[V, Value].valueType(NullValueDataType.INSTANCE)
                )
              existingMapsByHotKey.putIfAbsent(hotKey, newSuperNodeMap)
              codec.writeAll(newSuperNodeMap, values)
            case Some(m) =>
              codec.writeAll(m, values)
          }
        replacedValueOpt
          .map(v => Failure(new AssertionError(s"In $id, secondary-key $v was already present under hotkey $hotKey!")))
          .getOrElse(Success(()))
      }
      .orElse {
        collectInsertedHotKey(hotKey, size)
        None
      }

  def removeAllOrFail(hotKey: HK, values: IterableOnce[V], size: Int): Option[Try[Unit]] =
    superNodeCollector
      .getHotKeyString(hotKey)
      .flatMap { superNodeName =>
        existingMapsByHotKey.get(hotKey).map { mvMap =>
          values.iterator
            .find(k => mvMap.remove(k) == null)
            .fold(Success(())) { sk =>
              Failure(new AssertionError(s"In $id, removing non-existing secondary key $sk from superNode $superNodeName"))
            } // we don't remove supernode map when it gets empty as common map as  on/off/on/off is expensive
        /*
            .flatMap { _ =>
              if (mvMap.isEmpty) {
                existingSupernodeMapsByKey.remove(sk).fold(Try(store.removeMap(superNodeName))) { m =>
                  Try(store.removeMap(m))
                }
              } else
                Success(())
            }
         */
        }
      }
      .orElse {
        collectRemovedHotKey(hotKey, size)
        None
      }

  def isEmpty: Boolean = existingMapsByHotKey.forall(_._2.isEmpty)

  def get(hotKey: HK): Option[C[V]] =
    superNodeCollector
      .getHotKeyString(hotKey)
      .flatMap { _ =>
        existingMapsByHotKey.get(hotKey)
      }
      .map(codec.readAll)

  def contains(hotKey: HK): Boolean =
    superNodeCollector
      .getHotKeyString(hotKey)
      .exists { _ =>
        existingMapsByHotKey.contains(hotKey)
      }

  def contains(hotKey: HK, v: V): Option[Boolean] =
    superNodeCollector
      .getHotKeyString(hotKey)
      .flatMap { _ =>
        existingMapsByHotKey.get(hotKey)
      }
      .map(codec.contains(v, _))

  def size: Int = existingMapsByHotKey.size

  def totalSize: Int = existingMapsByHotKey.iterator.map(_._2.size()).sum

}

object SuperNodeMvSet {
  def apply[HK: HotKeyCodec, C[_], V](id: String, hotKeyDir: Path)(implicit
    store: MVStore,
    sc: SuperNodeSetCodec[C, V],
    vc: ValueCodec[SuperNodeCounter]
  ): SuperNodeMvSet[HK, C, V] = {
    val superNodeCollector = new SuperNodeCollector[HK](id, hotKeyDir)
    val existingMapsByHotKey: concurrent.Map[HK, MVMap[V, Value]] =
      new ConcurrentHashMap[HK, MVMap[V, Value]]().asScala.addAll(
        superNodeCollector
          .getExistingStringifiedHotKeys(store.getMapNames.asScala.toSet)
          .view
          .mapValues { name =>
            store.openMap(
              name,
              MVMap.Builder[V, Value].valueType(NullValueDataType.INSTANCE)
            )
          }
          .toMap
      )

    new SuperNodeMvSet[HK, C, V](id, superNodeCollector, existingMapsByHotKey)
  }
}
