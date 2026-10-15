package com.cc.libackupnext.util

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * 把 SAF 的 tree Uri 转成 rustic 能用的文件系统路径。
 *
 * 例：`content://com.android.externalstorage.documents/tree/primary%3ABackup`
 *     -> `/storage/emulated/0/Backup`
 */
object RepoPath {

    fun toFilesystemPath(raw: String): String? {
        if (raw.isBlank()) return null
        if (!raw.startsWith("content://")) return raw

        return runCatching {
            val uri = Uri.parse(raw)
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            documentIdToPath(documentId)
        }.getOrNull()
    }

    private fun documentIdToPath(documentId: String): String? {
        if (documentId.startsWith("raw:")) {
            return documentId.removePrefix("raw:")
        }

        val separator = documentId.indexOf(':')
        if (separator <= 0) return null

        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)

        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }

        return if (relative.isBlank()) base else "$base/$relative"
    }
}
