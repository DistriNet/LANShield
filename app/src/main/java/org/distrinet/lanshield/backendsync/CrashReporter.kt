package org.distrinet.lanshield.backendsync

import org.distrinet.lanshield.BuildConfig

interface CrashReporter {
    fun recordException(e: Throwable)
}

val crashReporter: CrashReporter = if (BuildConfig.USE_FIREBASE) {
    FirebaseCrashReporter
} else {
    NoOpCrashReporter
}

object NoOpCrashReporter : CrashReporter {
    override fun recordException(e: Throwable) {
    }
}