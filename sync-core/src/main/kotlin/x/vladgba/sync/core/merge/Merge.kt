package x.vladgba.sync.core.merge

import x.vladgba.sync.core.fs.PathUtil
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Myers O(ND) diff over lines, returning the matched (LCS) index pairs. */
object MyersDiff {
    /** Returns matched pairs `[i, j]` with a[i] == b[j], increasing; null if the edit distance exceeds [maxD]. */
    fun matches(a: List<String>, b: List<String>, maxD: Int = 2000): List<IntArray>? {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--; endB--
        }
        val result = ArrayList<IntArray>()
        for (i in 0 until start) result += intArrayOf(i, i)
        val mid = core(a.subList(start, endA), b.subList(start, endB), maxD) ?: return null
        for (p in mid) result += intArrayOf(p[0] + start, p[1] + start)
        for (k in 0 until a.size - endA) result += intArrayOf(endA + k, endB + k)
        return result
    }

    private fun core(a: List<String>, b: List<String>, maxD: Int): List<IntArray>? {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()
        val max = n + m
        val off = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>() // trace[d][k + d] = furthest x on diagonal k after round d
        for (d in 0..minOf(max, maxD)) {
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) {
                    trace += v.copyOfRange(off - d, off + d + 1)
                    return backtrack(trace, n, m)
                }
            }
            trace += v.copyOfRange(off - d, off + d + 1)
        }
        return null
    }

    private fun backtrack(trace: List<IntArray>, n: Int, m: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 1) {
            val prev = trace[d - 1]
            fun pv(k: Int) = prev[k + d - 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && pv(k - 1) < pv(k + 1))) k + 1 else k - 1
            val prevX = pv(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x--; y--
                out += intArrayOf(x, y)
            }
            x = prevX
            y = prevY
        }
        while (x > 0 && y > 0) {
            x--; y--
            out += intArrayOf(x, y)
        }
        out.reverse()
        return out
    }
}

/** Line-based three-way merge. Symmetric: merge(o, a, b) == merge(o, b, a). */
object Diff3 {
    fun splitLines(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in s.indices) {
            if (s[i] == '\n') {
                out += s.substring(start, i + 1)
                start = i + 1
            }
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    /** Returns the merged text, or null when both sides changed the same region differently. */
    fun merge(base: String, ours: String, theirs: String): String? {
        if (ours == theirs) return ours
        if (ours == base) return theirs
        if (theirs == base) return ours
        val o = splitLines(base)
        val a = splitLines(ours)
        val b = splitLines(theirs)
        val ma = mapping(o, a) ?: return null
        val mb = mapping(o, b) ?: return null

        val out = StringBuilder()
        var io = 0
        var ia = 0
        var ib = 0
        while (true) {
            var l = 0
            while (io + l < o.size && ma[io + l] == ia + l && mb[io + l] == ib + l) l++
            if (l > 0) {
                for (k in 0 until l) out.append(o[io + k])
                io += l; ia += l; ib += l
                continue
            }
            var j = io
            while (j < o.size && (ma[j] < 0 || mb[j] < 0)) j++
            val ea = if (j < o.size) ma[j] else a.size
            val eb = if (j < o.size) mb[j] else b.size
            val oc = o.subList(io, j)
            val ac = a.subList(ia, ea)
            val bc = b.subList(ib, eb)
            if (oc.isEmpty() && ac.isEmpty() && bc.isEmpty()) break
            val chosen = when {
                ac == oc -> bc
                bc == oc -> ac
                ac == bc -> ac
                else -> return null
            }
            chosen.forEach { out.append(it) }
            io = j; ia = ea; ib = eb
        }
        return out.toString()
    }

    private fun mapping(o: List<String>, x: List<String>): IntArray? {
        val m = IntArray(o.size) { -1 }
        val pairs = MyersDiff.matches(o, x) ?: return null
        for (p in pairs) m[p[0]] = p[1]
        return m
    }
}

object ConflictNaming {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    /** `dir/report.docx` → `dir/report.conflict-ABCDEF-20261011-120000.docx` */
    fun conflictPath(path: String, deviceId: String, changedAt: Long): String {
        val name = PathUtil.name(path)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val stamp = fmt.format(Instant.ofEpochMilli(changedAt))
        return PathUtil.join(PathUtil.parent(path), "$base.conflict-${deviceId.take(6)}-$stamp$ext")
    }
}
