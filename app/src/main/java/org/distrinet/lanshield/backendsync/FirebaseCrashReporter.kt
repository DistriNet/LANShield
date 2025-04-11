package org.distrinet.lanshield.backendsync

//We should be able to only include this file if useFirebase is false

//import com.google.firebase.crashlytics.FirebaseCrashlytics

object FirebaseCrashReporter : CrashReporter {
    override fun recordException(e: Throwable) {
//        FirebaseCrashlytics.getInstance().recordException(e)
    }
}