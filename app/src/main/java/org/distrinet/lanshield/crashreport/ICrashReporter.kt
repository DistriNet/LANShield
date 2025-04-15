package org.distrinet.lanshield.crashreport

interface ICrashReporter {
    fun recordException(e: Throwable)
}