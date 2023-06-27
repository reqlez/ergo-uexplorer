package org.ergoplatform.uexplorer.storage

import org.ergoplatform.uexplorer.{ErgoTreeHex, *}
import org.ergoplatform.uexplorer.db.BlockInfo
import org.ergoplatform.uexplorer.mvstore.*
import org.ergoplatform.uexplorer.mvstore.SuperNodeCollector.Counter
import org.ergoplatform.uexplorer.mvstore.multimap.MultiMapCodec
import org.ergoplatform.uexplorer.mvstore.multiset.MultiSetCodec
import org.ergoplatform.uexplorer.storage.kryo.*

object Implicits {
  implicit def valueByBoxCodec[V]: MultiMapCodec[java.util.Map, BoxId, V] = new ValueByBoxCodec[V]

  implicit val blockIdsCodec: ValueCodec[java.util.Set[BlockId]] = BlockIdsCodec
  implicit val boxCodec: MultiSetCodec[java.util.Set, BoxId]     = BoxCodec
  implicit val blockInfoCodec: ValueCodec[BlockInfo]             = BlockInfoCodec
  implicit val counterCodec: ValueCodec[Counter]                 = CounterCodec
  implicit val addressCodec: ValueCodec[ErgoTreeHex]             = ErgoTreeHexCodec

  implicit val hexCodec: HotKeyCodec[HexString] = new HotKeyCodec[HexString] {
    import org.ergoplatform.uexplorer.HexString.unwrapped
    def serialize(key: HexString): String = key.unwrapped

    def deserialize(key: String): HexString = ErgoTreeHex.fromStringUnsafe(key)
  }

}
