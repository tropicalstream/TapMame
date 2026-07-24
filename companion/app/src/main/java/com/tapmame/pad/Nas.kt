package com.tapmame.pad

import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream

/**
 * Thin SMB/CIFS wrapper for the LAN NAS ROM browser (jcifs-ng). All calls
 * block — run them off the UI thread. URLs are smb:// form, e.g.
 * smb://192.168.1.50/roms/ ; empty user = anonymous/guest.
 */
object Nas {

    data class Entry(val name: String, val isDir: Boolean, val size: Long)

    private fun ctx(user: String, pass: String): CIFSContext {
        val base = SingletonContext.getInstance()
        return if (user.isEmpty()) base
        else base.withCredentials(NtlmPasswordAuthenticator("", user, pass))
    }

    fun list(url: String, user: String, pass: String): List<Entry> {
        val dir = SmbFile(if (url.endsWith("/")) url else "$url/", ctx(user, pass))
        val out = ArrayList<Entry>()
        dir.listFiles()?.forEach { f ->
            val name = f.name.trimEnd('/')
            if (!name.startsWith(".") && !name.endsWith("$"))
                out.add(Entry(name, f.isDirectory, if (f.isDirectory) 0 else f.length()))
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun open(url: String, user: String, pass: String): Pair<InputStream, Long> {
        val f = SmbFile(url, ctx(user, pass))
        return Pair(f.inputStream, f.length())
    }
}
