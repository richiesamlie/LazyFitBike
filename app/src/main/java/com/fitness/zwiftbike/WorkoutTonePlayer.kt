package com.fitness.zwiftbike

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Low-latency audio tone generator for 3-2-1 interval countdowns,
 * step start alerts, and workout completion fanfare.
 * Uses Android's built-in [ToneGenerator] with zero audio assets or external dependencies.
 */
class WorkoutTonePlayer {

    private var toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    }.getOrNull()

    /**
     * Plays a crisp pip tone for countdown seconds (3, 2, 1).
     */
    fun playCountdownPip() {
        runCatching {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 120)
        }
    }

    /**
     * Plays a high-pitch transition alert tone when an interval starts.
     */
    fun playStepTransitionChime() {
        runCatching {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
        }
    }

    /**
     * Plays a celebratory multi-note fanfare upon completing a full workout.
     */
    fun playWorkoutCompletedFanfare(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            runCatching {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                delay(220)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                delay(270)
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 450)
            }
        }
    }

    /**
     * Releases audio hardware resources.
     */
    fun release() {
        runCatching {
            toneGenerator?.release()
            toneGenerator = null
        }
    }
}
