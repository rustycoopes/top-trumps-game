package com.toptrumps.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.flow.StateFlow

/**
 * [SoundPool] wrapper for the six cues WBS slice-7-polish calls for — stat selection, flip, round
 * win/loss, match victory/defeat. `USAGE_GAME` per the TDD's design notes; preloaded once, at app
 * start ([AppGraph] owns the one instance, constructed from [TopTrumpsApplication.onCreate]).
 *
 * [SoundPool.load] is asynchronous — a cue requested before its own load completes would otherwise
 * be silently dropped, and the very first stat-selection tap is exactly the case the WBS calls out
 * ("the first sound after launch is not silent"). [pendingUntilLoaded] plays each cue the instant
 * its own load finishes if it was requested before that, rather than accepting the loss.
 */
public class SoundEffects(context: Context, private val muted: StateFlow<Boolean>) {

    public enum class Cue(internal val resId: Int) {
        SELECT(R.raw.sfx_select),
        FLIP(R.raw.sfx_flip),
        ROUND_WIN(R.raw.sfx_win_trick),
        ROUND_LOSS(R.raw.sfx_lose_trick),
        MATCH_VICTORY(R.raw.sfx_win_game),
        MATCH_DEFEAT(R.raw.sfx_lose_game),
    }

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIdToCue = mutableMapOf<Int, Cue>()
    private val cueToSoundId = mutableMapOf<Cue, Int>()
    private val loadedSoundIds = mutableSetOf<Int>()
    private val pendingUntilLoaded = mutableSetOf<Cue>()

    init {
        soundPool.setOnLoadCompleteListener { _, soundId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loadedSoundIds += soundId
            val cue = soundIdToCue[soundId] ?: return@setOnLoadCompleteListener
            if (pendingUntilLoaded.remove(cue)) playNow(soundId)
        }
        Cue.entries.forEach { cue ->
            val soundId = soundPool.load(context, cue.resId, 1)
            cueToSoundId[cue] = soundId
            soundIdToCue[soundId] = cue
        }
    }

    /** Silently does nothing if [muted] or if [cue]'s id is somehow unknown — there is no error channel for a sound effect. */
    public fun play(cue: Cue) {
        if (muted.value) return
        val soundId = cueToSoundId[cue] ?: return
        if (soundId in loadedSoundIds) playNow(soundId) else pendingUntilLoaded += cue
    }

    private fun playNow(soundId: Int) {
        soundPool.play(soundId, 1f, 1f, /* priority = */ 1, /* loop = */ 0, /* rate = */ 1f)
    }

    /** Releases the native SoundPool — call once, when the owning [AppGraph] is torn down. */
    public fun release() {
        soundPool.release()
    }
}
