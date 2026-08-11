package com.droidnova.allfilereader.domain.model

import java.security.MessageDigest

object DocumentIds {
    fun fromStorageLocation(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
