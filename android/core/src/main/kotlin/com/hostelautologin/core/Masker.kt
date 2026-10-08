package com.hostelautologin.core

import java.util.concurrent.CopyOnWriteArraySet

/** Secret masking for logs and diagnostics (SPEC section 6). */
class Masker {
    private val secrets = CopyOnWriteArraySet<String>()

    fun register(secret: String?) {
        if (secret != null && secret.length >= 3) secrets.add(secret)
    }

    fun clear() = secrets.clear()

    fun mask(text: Any?): String {
        var s = text.toString()
        for (sec in secrets.sortedByDescending { it.length }) s = s.replace(sec, MASK)
        s = HEADER.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        s = KV.replace(s) { "${it.groupValues[1]}$MASK" }
        s = LONG.replace(s, MASK)
        return s
    }

    companion object {
        const val MASK = "***"
        private const val KEY = "(?:pass(?:word|wd)?|pwd|secret|token|cookie|session(?:id)?|auth(?:orization)?|csrf|xsrf|sid|nonce|key)"
        private val KV = Regex("""(\b[\w-]*$KEY[\w-]*"?\s*[=:]\s*"?)([^&\s"',;]+)""", RegexOption.IGNORE_CASE)
        private val HEADER = Regex("""\b(cookie|set-cookie|authorization|proxy-authorization)\b(\s*[:=]\s*)([^\r\n]+)""", RegexOption.IGNORE_CASE)
        private val LONG = Regex("""(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{24,}(?![A-Za-z0-9+/=_-])""")

        /** Process-wide instance used by AppLog. */
        val global = Masker()
    }
}
