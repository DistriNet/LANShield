package org.distrinet.lanshield.crashreport

val crashReporter: ICrashReporter = NoOpCrashReporter

object NoOpCrashReporter : ICrashReporter {
    override fun recordException(e: Throwable) {
    }
}