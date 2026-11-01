package x.vladgba.share.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class QrBundleMemoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun largeUnknownStreamIsOpenedOnceAndRoundTripsWithoutSourceArrays() {
        val size = 24L * 1024 * 1024
        var opens = 0
        var closed = false
        val item = object : OutgoingItem {
            override val info = ItemInfo(ItemInfo.KIND_FILE, "large", "application/octet-stream", -1)
            override fun open(): InputStream {
                check(++opens == 1)
                return object : InputStream() {
                    var remaining = size
                    override fun read(): Int = if (remaining-- > 0) 0 else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        check(len <= 32 * 1024)
                        if (remaining == 0L) return -1
                        val n = minOf(len.toLong(), remaining).toInt()
                        b.fill(0, off, off + n)
                        remaining -= n
                        return n
                    }
                    override fun close() { closed = true }
                }
            }
        }
        val bytes = QrBundle.build(listOf(item), temp.root)
        assertTrue(closed)
        assertTrue(bytes.size < 100_000)
        var received = 0L
        var finished = false
        val infos = QrBundle.parse(bytes, object : ReceiveSink {
            override fun open(index: Int, info: ItemInfo) = object : OutputStream() {
                override fun write(b: Int) { assertEquals(0, b); received++ }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    check((off until off + len).all { b[it] == 0.toByte() })
                    received += len
                }
            }
            override fun finish(index: Int, info: ItemInfo, ok: Boolean) { finished = ok }
        })
        assertEquals(size, infos.single().size)
        assertEquals(size, received)
        assertTrue(finished)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun staleDeclaredSizeUsesActualStreamLength() {
        val item = object : OutgoingItem {
            override val info = ItemInfo(ItemInfo.KIND_FILE, "changed", "text/plain", 500)
            override fun open() = "actual".byteInputStream()
        }
        val infos = QrBundle.parse(QrBundle.build(listOf(item), temp.root), object : ReceiveSink {
            override fun open(index: Int, info: ItemInfo) = java.io.ByteArrayOutputStream()
            override fun finish(index: Int, info: ItemInfo, ok: Boolean) { assertTrue(ok) }
        })
        assertEquals(6L, infos.single().size)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun failedReadsAndOversizedBundlesRemoveTemporaryFiles() {
        val bad = object : OutgoingItem {
            override val info = ItemInfo(ItemInfo.KIND_FILE, "bad", "text/plain", -1)
            override fun open() = object : InputStream() {
                override fun read(): Int = throw IOException("read failed")
            }
        }
        assertTrue(runCatching { QrBundle.build(listOf(bad), temp.root) }.exceptionOrNull() is IOException)
        assertTrue(temp.root.listFiles()!!.isEmpty())
        assertTrue(runCatching {
            QrBundle.build(listOf(TextItem("does not fit")), temp.root, maxBundleBytes = 1)
        }.exceptionOrNull() is IOException)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
}
