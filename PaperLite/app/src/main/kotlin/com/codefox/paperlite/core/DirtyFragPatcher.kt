package com.codefox.paperlite.core

/**
 * DirtyFrag 载荷的路径改写。
 *
 * 载荷把作者自己的应用目录写死了（`/data/user_de/0/df.root/...`），
 * 我们的包名不同、槽位也放不下新路径，所以统一改到 `/data/local/tmp/`：
 * 那个目录普通应用写不了，但 adb shell（uid 2000）可以写，
 * 而载荷是以 uid 0 执行的，读得到。
 *
 * 改写是定长槽位内的字节替换，新路径更短时用 0 补齐。
 */
object DirtyFragPatcher {

    /** 设备上的落地路径（改写后的目标）。 */
    const val BOOTSTRAP_PATH = "/data/local/tmp/dfroot_boot"
    const val KSUD_PATH = "/data/local/tmp/dfroot_ksud"
    const val PREFS_PATH = "/data/local/tmp/dfroot.xml"

    /** 原始路径 → 新路径。 */
    private val REPLACEMENTS: List<Pair<String, String>> = listOf(
        "/data/user_de/0/df.root/bootstrap" to BOOTSTRAP_PATH,
        "/data/user_de/0/df.root/ksud" to KSUD_PATH,
        "/data/user_de/0/df.root/shared_prefs/dfroot.xml" to PREFS_PATH,
    )

    /**
     * 对载荷做路径改写。
     *
     * @return 改写后的字节；若一个都没命中会原样返回（并由调用方记录日志）
     */
    fun patch(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        for ((from, to) in REPLACEMENTS) {
            val src = from.toByteArray(Charsets.US_ASCII)
            val dst = to.toByteArray(Charsets.US_ASCII)
            require(dst.size <= src.size) {
                "替换路径比原路径长，放不进定长槽位：$to"
            }
            var index = 0
            while (index >= 0) {
                index = indexOf(out, src, index)
                if (index < 0) break
                // 写入新路径 + 0 填充，保证 C 字符串在此终止
                java.util.Arrays.fill(out, index, index + src.size, 0.toByte())
                System.arraycopy(dst, 0, out, index, dst.size)
                index += src.size
            }
        }
        return out
    }

    /** 统计载荷里还剩多少处原始路径（用于校验改写是否生效）。 */
    fun remainingOldPaths(bytes: ByteArray): Int {
        var count = 0
        for ((from, _) in REPLACEMENTS) {
            var index = 0
            val src = from.toByteArray(Charsets.US_ASCII)
            while (true) {
                index = indexOf(bytes, src, index)
                if (index < 0) break
                count++
                index += src.size
            }
        }
        return count
    }

    /** 统计新路径出现的次数。 */
    fun countNewPaths(bytes: ByteArray): Int {
        var count = 0
        for ((_, to) in REPLACEMENTS) {
            val dst = to.toByteArray(Charsets.US_ASCII)
            var index = 0
            while (true) {
                index = indexOf(bytes, dst, index)
                if (index < 0) break
                count++
                index += dst.size
            }
        }
        return count
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
