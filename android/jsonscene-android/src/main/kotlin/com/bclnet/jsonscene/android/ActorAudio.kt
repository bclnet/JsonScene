/*
 * ActorAudio.kt
 * JsonScene (Android)
 *
 * Sounds through MediaPlayer (one per playing sound name per actor) and
 * speech through the platform TextToSpeech engine. Shared by the Filament and
 * Spatial SDK renderers.
 */
package com.bclnet.jsonscene.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.bclnet.jsonscene.Sound
import java.io.File
import java.util.Locale

class ActorAudio(context: Context) {
    private val players = HashMap<Pair<String, String>, MediaPlayer>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<String>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                while (pendingSpeech.isNotEmpty()) speak(pendingSpeech.removeFirst())
            }
        }
    }

    /** Plays `file` as `name` for `actor`; `onEnd` runs when a non looping sound finishes. */
    fun play(actor: String, name: String, sound: Sound, file: File, loop: Boolean, onEnd: () -> Unit) {
        stop(actor, name)
        val player = MediaPlayer()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        player.setDataSource(file.path)
        player.isLooping = loop
        player.setVolume(sound.volume.toFloat(), sound.volume.toFloat())
        player.setOnCompletionListener {
            if (!loop) {
                players.remove(actor to name)?.release()
                onEnd()
            }
        }
        player.setOnErrorListener { _, _, _ -> players.remove(actor to name)?.release(); true }
        player.prepare()
        players[actor to name] = player
        player.start()
    }

    fun stop(actor: String, name: String?) {
        val keys = players.keys.filter { it.first == actor && (name == null || it.second == name) }
        for (key in keys) players.remove(key)?.let { runCatching { it.stop() }; it.release() }
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) { pendingSpeech.addLast(text); return }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "jsonscene-${text.hashCode()}")
    }

    fun release() {
        for (p in players.values) { runCatching { p.stop() }; p.release() }
        players.clear()
        tts?.shutdown()
        tts = null
    }
}
