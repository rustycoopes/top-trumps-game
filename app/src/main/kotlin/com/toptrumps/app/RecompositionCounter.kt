package com.toptrumps.app

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Debug-only recomposition tallying for the match screen's stability work (WBS slice-7-polish's
 * "recomposition counts verified as bounded" acceptance criterion). Call once per composable under
 * test; each call logs its own running count on every recomposition, filterable in `adb logcat` by
 * tag `Recomposition` — a substitute for Layout Inspector when checking on a connected device.
 */
@Composable
internal fun LogRecomposition(tag: String) {
    if (!BuildConfig.DEBUG) return
    val count = remember { intArrayOf(0) }
    count[0]++
    SideEffect { Log.d("Recomposition", "$tag recomposed ${count[0]} times") }
}
