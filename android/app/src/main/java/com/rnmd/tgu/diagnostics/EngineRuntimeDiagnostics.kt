package com.rnmd.tgu.diagnostics

import android.util.Log

                                                                                  
object DiagnosticLogger {
    private const val TAG = "TGU-LocalProxy"
    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val details = fields.joinToString(" ") { (key, value) -> "$key=${DiagnosticRedactor.field(key, value)}" }
        Log.i(TAG, if (details.isBlank()) name else "$name $details")
    }
    fun failure(name: String, error: Throwable, vararg fields: Pair<String, Any?>) {
        val details = fields.joinToString(" ") { (key, value) -> "$key=${DiagnosticRedactor.field(key, value)}" }
        val suffix = if (details.isBlank()) "" else " $details"
        Log.w(TAG, "$name$suffix (${error.javaClass.simpleName})")
    }
}

object DiagnosticRedactor {
    fun field(key: String, value: Any?): String {
        val normalized = key.lowercase()
        if (listOf("secret", "password", "token", "authorization", "url", "domain", "host", "ip").any { normalized.contains(it) }) return "[redacted]"
        return value?.toString()?.take(96) ?: "null"
    }
}