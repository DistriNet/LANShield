package org.distrinet.lanshield.crashreport

import com.google.firebase.crashlytics.FirebaseCrashlytics

val crashReporter: ICrashReporter = FirebaseCrashReporter

object FirebaseCrashReporter : ICrashReporter {
    override fun recordException(e: Throwable) {
        FirebaseCrashlytics.getInstance().recordException(e)
    }
}