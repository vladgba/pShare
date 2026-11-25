package x.vladgba.sync.core

import x.vladgba.sync.core.fs.LocalFileSystem
import x.vladgba.sync.core.fs.PathUtil
import x.vladgba.sync.core.index.FolderIndex
import x.vladgba.sync.core.index.Scanner
import x.vladgba.sync.core.merge.ConflictNaming
import x.vladgba.sync.core.merge.Diff3
import x.vladgba.sync.core.merge.MyersDiff
import x.vladgba.sync.core.model.FileEntry
import x.vladgba.sync.core.model.Ordering
import x.vladgba.sync.core.model.VersionVector
import x.vladgba.sync.core.rules.Glob
import x.vladgba.sync.core.rules.PathFilter
import x.vladgba.sync.core.sync.Action
import x.vladgba.sync.core.sync.Planner
import java.nio.file.Files
import kotlin.random.Random
import x.vladgba.sync.core.testing.Test
import x.vladgba.sync.core.testing.assertEquals
import x.vladgba.sync.core.testing.assertFalse
import x.vladgba.sync.core.testing.assertIs
import x.vladgba.sync.core.testing.assertNull
import x.vladgba.sync.core.testing.assertTrue

class VersionVectorTest {
    @Test
    fun ordering() {
        val a = VersionVector().increment("A")
        val ab = a.increment("B")
        val aa = a.increment("A")
        assertEquals(Ordering.BEFORE, a.compare(ab))
        assertEquals(Ordering.AFTER, ab.compare(a))
        assertEquals(Ordering.CONCURRENT, ab.compare(aa))
        assertEquals(Ordering.EQUAL, ab.compare(VersionVector(mapOf("A" to 1, "B" to 1))))
        val m = ab.merge(aa)
        assertEquals(Ordering.AFTER, m.compare(ab))
        assertEquals(Ordering.AFTER, m.compare(aa))
    }
}

class DiffTest {
    private fun lines(vararg s: String) = s.joinToString("") { "$it\n" }

    @Test
    fun myersMatchesLcs() {
        val rnd = Random(42)
        repeat(200) {
            val a = List(rnd.nextInt(0, 30)) { "l${rnd.nextInt(6)}" }
            val b = List(rnd.nextInt(0, 30)) { "l${rnd.nextInt(6)}" }
            val m = MyersDiff.matches(a, b)!!
            m.forEach { assertEquals(a[it[0]], b[it[1]]) }
            m.zipWithNext().forEach { (p, q) -> assertTrue(q[0] > p[0] && q[1] > p[1]) }
            assertEquals(lcsLength(a, b), m.size)
        }
    }

    private fun lcsLength(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        return dp[0][0]
    }

    @Test
    fun mergesNonOverlappingEdits() {
        val base = lines("a", "b", "c", "d", "e")
        val ours = lines("A", "b", "c", "d", "e")
        val theirs = lines("a", "b", "c", "d", "E", "f")
        assertEquals(lines("A", "b", "c", "d", "E", "f"), Diff3.merge(base, ours, theirs))
        assertEquals(Diff3.merge(base, ours, theirs), Diff3.merge(base, theirs, ours))
    }

    @Test
    fun identicalChangesMerge() {
        val base = lines("a", "b")
        assertEquals(lines("a", "X"), Diff3.merge(base, lines("a", "X"), lines("a", "X")))
    }

    @Test
    fun overlappingEditsConflict() {
        val base = lines("a", "b", "c")
        assertNull(Diff3.merge(base, lines("a", "X", "c"), lines("a", "Y", "c")))
    }

    @Test
    fun insertionsAndDeletions() {
        val base = lines("1", "2", "3", "4", "5", "6")
        val ours = lines("1", "2", "new", "3", "4", "5", "6")
        val theirs = lines("1", "2", "3", "4", "6")
        assertEquals(lines("1", "2", "new", "3", "4", "6"), Diff3.merge(base, ours, theirs))
    }

    @Test
    fun keepsCrLf() {
        val base = "a\r\nb\r\nc\r\n"
        assertEquals("A\r\nb\r\nC\r\n", Diff3.merge(base, "A\r\nb\r\nc\r\n", "a\r\nb\r\nC\r\n"))
    }
}

class RulesTest {
    @Test
    fun globs() {
        assertTrue(Glob("*.tmp").matches("a/b/c.TMP"))
        assertFalse(Glob("*.tmp").matches("a/tmp/c.txt"))
        assertTrue(Glob("node_modules/").matches("x/node_modules/y/z.js"))
        assertFalse(Glob("/build/").matches("x/build/a"))
        assertTrue(Glob("/build/").matches("build/a"))
        assertTrue(Glob("docs/**/*.md").matches("docs/a/b/readme.md"))
        assertTrue(Glob("docs/**/*.md").matches("docs/readme.md"))
        assertFalse(Glob("docs/*.md").matches("docs/a/readme.md"))
        assertTrue(Glob("photo-??.jpg").matches("photo-01.jpg"))
    }

    @Test
    fun filter() {
        val f = PathFilter(include = listOf("*.jpg", "*.png"), exclude = listOf("private/"), maxSize = 100)
        assertTrue(f.accepts("a/b.jpg", 10))
        assertFalse(f.accepts("a/b.txt", 10))
        assertFalse(f.accepts("private/b.jpg", 10))
        assertFalse(f.accepts("b.jpg", 1000))
        assertFalse(PathFilter().accepts("x/.lbtmp-123"))
    }

    @Test
    fun pathSafety() {
        assertTrue(PathUtil.isSafe("a/b c/d.txt"))
        listOf("", "/a", "a/../b", "a//b", "a\\b", "C:/x", "./a").forEach { assertFalse(PathUtil.isSafe(it), it) }
    }

    @Test
    fun conflictName() {
        assertEquals(
            "dir/report.conflict-ABCDEF-19700101-000000.docx",
            ConflictNaming.conflictPath("dir/report.docx", "ABCDEFGHIJ", 0)
        )
        assertEquals("Makefile.conflict-ABCDEF-19700101-000000", ConflictNaming.conflictPath("Makefile", "ABCDEFGH", 0))
    }
}

class ScanPlanTest {
    @Test
    fun scanDetectsChangesAndDeletes() {
        val dir = Files.createTempDirectory("lb").toFile()
        val fs = LocalFileSystem(dir)
        val idx = FolderIndex("f")
        val old = System.currentTimeMillis() - 60_000
        dir.resolve("a.txt").apply { writeText("hello\n"); setLastModified(old) }
        dir.resolve("sub").mkdirs()
        dir.resolve("sub/b.bin").apply { writeBytes(byteArrayOf(0, 1, 2)); setLastModified(old) }
        val scanner = Scanner("DEV1")
        assertEquals(2, scanner.scan(fs, idx, PathFilter(), null).changed)
        assertEquals(0, scanner.scan(fs, idx, PathFilter(), null).changed)

        dir.resolve("a.txt").apply { writeText("hello world\n"); setLastModified(old + 1000) }
        dir.resolve("sub/b.bin").delete()
        dir.resolve("fresh.txt").writeText("still being written") // mtime = now → skipped until settled
        val r = scanner.scan(fs, idx, PathFilter(), null)
        assertEquals(2, r.changed)
        assertEquals(1, r.pending)
        val a = idx.entries.getValue("a.txt")
        assertEquals(2L, a.version.c["DEV1"])
        assertEquals(1, a.history.size)
        assertTrue(idx.entries.getValue("sub/b.bin").deleted)
    }

    @Test
    fun planner() {
        fun e(path: String, hash: String, v: Map<String, Long>, deleted: Boolean = false) =
            FileEntry(path, 1, 1, hash, deleted, VersionVector(v))
        val local = mapOf(
            "same" to e("same", "h1", mapOf("A" to 1)),
            "older" to e("older", "h1", mapOf("A" to 1)),
            "conc" to e("conc", "h1", mapOf("A" to 2)),
            "concSame" to e("concSame", "h9", mapOf("A" to 2)),
            "del" to e("del", "h1", mapOf("A" to 1)),
        )
        val remote = listOf(
            e("same", "h1", mapOf("A" to 1)),
            e("older", "h2", mapOf("A" to 1, "B" to 1)),
            e("conc", "h3", mapOf("A" to 1, "B" to 1)),
            e("concSame", "h9", mapOf("A" to 1, "B" to 1)),
            e("del", "", mapOf("A" to 1, "B" to 1), deleted = true),
            e("new", "h5", mapOf("B" to 1)),
            e("../evil", "h6", mapOf("B" to 1)),
        )
        val plan = Planner.plan(local, remote, PathFilter()).associateBy { it.path }
        assertEquals(setOf("older", "conc", "concSame", "del", "new"), plan.keys)
        assertIs<Action.Download>(plan["older"])
        assertIs<Action.Conflict>(plan["conc"])
        assertIs<Action.Record>(plan["concSame"])
        assertIs<Action.Delete>(plan["del"])
        assertIs<Action.Download>(plan["new"])
    }
}
