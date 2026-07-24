package com.tapmame.pad

/**
 * Friendly NetPlay invite codes: an IPv4 host + port packed into 48 bits and
 * spelled in Crockford base32 as XXXX-XXXX-XX — easy to read out loud or
 * paste into a messenger. Addresses that aren't plain IPv4 (IPv6, hostnames)
 * are shared as-is; decode() passes anything containing a dot or colon
 * straight through, so a raw address also works in the join box.
 */
object InviteCode {

    private const val ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(addr: String): String? {
        val m = Regex("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+):?(\\d*)").find(addr) ?: return null
        val (a, b, c, d, ps) = m.destructured
        val port = if (ps.isEmpty()) 2080 else ps.toInt()
        var v = 0L
        for (o in listOf(a, b, c, d)) v = (v shl 8) or o.toLong()
        v = (v shl 16) or port.toLong()
        val sb = StringBuilder()
        for (i in 9 downTo 0) sb.append(ALPHA[((v shr (i * 5)) and 31).toInt()])
        return "${sb.substring(0, 4)}-${sb.substring(4, 8)}-${sb.substring(8)}"
    }

    fun decode(code: String): String? {
        val raw = code.trim().uppercase()
        if (raw.contains(".") || raw.contains("[")) return code.trim()   // raw address given
        val s = raw.replace("-", "").replace(" ", "")
            .replace('O', '0').replace('I', '1').replace('L', '1')
        if (s.length != 10) return null
        var v = 0L
        for (ch in s) {
            val idx = ALPHA.indexOf(ch)
            if (idx < 0) return null
            v = (v shl 5) or idx.toLong()
        }
        val port = (v and 0xFFFF).toInt()
        val ip = (v shr 16)
        return "${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}:$port"
    }
}
