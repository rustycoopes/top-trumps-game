package com.toptrumps.app

import android.app.Application

/**
 * Owns [AppGraph] for the process's lifetime rather than [MainActivity]'s — a two-device match's
 * state (and the socket underneath it) must not die just because the Activity was recreated
 * (backgrounded and reclaimed, or a configuration change), only because the match itself ended.
 * See the foreground-service ADR's "application-scoped holder" note.
 */
public class TopTrumpsApplication : Application() {
    public lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this)
    }
}
