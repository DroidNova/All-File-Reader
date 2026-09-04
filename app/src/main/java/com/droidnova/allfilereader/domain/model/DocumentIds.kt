package com.droidnova.allfilereader.domain.model

import java.security.MessageDigest

object DocumentIds {
    /**
     * Scans pass canonical paths, producing stable rescan/restart identity without persisting paths.
     * Moving or renaming a path-based file changes its identity because Android provides no stable file ID.
     */
    fun fromStorageLocation(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
