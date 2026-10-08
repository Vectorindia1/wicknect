package com.hostelautologin.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every line is masked before it reaches any sink. Never log request bodies. */
object AppLog {
    @Volatile var sink: (String) -> Unit = { }
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    fun log(level: String, msg: String) {
        sink("${fmt.get()!!.format(Date())} $level ${Masker.global.mask(msg)}")
    }

    fun i(msg: String) = log("I", msg)
    fun w(msg: String) = log("W", msg)
    fun e(msg: String) = log("E", msg)
}
