/*
 * Copyright 2012 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.frank.breakout.opengl.base

import android.content.Context
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import com.frank.breakout.opengl.view.GameSurfaceRenderer
import com.frank.breakout.activity.BreakoutActivity.Companion.TAG
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Generate and play sound data.
 *
 *
 * The initialize() method must be called before any sounds can be played.
 */
class SoundResources private constructor(privateDir: File) : SoundPool.OnLoadCompleteListener {

    // The actual sound data.  Must be "final" for immutability guarantees.
    private val mSounds = arrayOfNulls<Sound>(NUM_SOUNDS)

    override fun onLoadComplete(soundPool: SoundPool, sampleId: Int, status: Int) {
        /*
         * Complain about any failures.  We could update mSounds[n] (where "n" is the index
         * of the Sound whose mHandle matches "sampleId") with a status enum like {pending,
         * ready, failed}, but the only advantage to doing so would be that we could skip the
         * SoundPool call and avoid filling the log with complaints.  In practice we should
         * never see a failure here, and the brief pause we do before releasing the ball at the
         * start of the game should provide more than enough time to load the sounds.
         *
         * If not, we'd want to add a "SoundPool response" counter and signal when the
         * counter reached the expected count.
         */
        if (status != 0) {
            Log.w(
                TAG, "onLoadComplete: pool=$soundPool sampleId=$sampleId status=$status"
            )
        }
    }

    /**
     * Generates all sounds.
     */
    private fun generateSoundFiles(soundPool: SoundPool, privateDir: File) {
        // Be aware that lower-frequency tones don't reproduce well on the internal speakers
        // present on some devices.
        mSounds[BRICK_HIT] = generateSound(soundPool, privateDir, "brick", 50 /*ms*/, 900 /*Hz*/)
        mSounds[PADDLE_HIT] = generateSound(soundPool, privateDir, "paddle", 50, 700)
        mSounds[WALL_HIT] = generateSound(soundPool, privateDir, "wall", 50, 300)
        mSounds[BALL_LOST] = generateSound(soundPool, privateDir, "ball_lost", 500, 280)
    }

    /**
     * Generate a sound with specific characteristics.
     */
    private fun generateSound(
        soundPool: SoundPool, dir: File, name: String, lengthMsec: Int,
        freqHz: Int
    ): Sound {
        /*
         * Since we're generating trivial tones, we could just generate a short set of samples
         * and then set a nonzero loop count in SoundPool.  We could also generate it at twice
         * the frequency for half the duration, and then use a playback rate of 0.5.  These would
         * save space on disk and in memory, but our sounds are already pretty tiny.
         *
         * These files can be erased by the user (using the "clear app data") function, so we
         * need to be able to regenerate them.  If they already exist we can skip the process
         * and save some wear on flash memory.
         */
        val outFile = File(dir, "$name.wav")
        if (!outFile.exists()) {
            try {
                val fos = FileOutputStream(outFile)

                // Number of samples.  Not worried about int overflow for our short sounds.
                val sampleCount = lengthMsec * SAMPLE_RATE / 1000
                var buf = generateWavHeader(sampleCount)
                var array = buf.array()
                fos.write(array)
                buf = generateWavData(sampleCount, freqHz)
                array = buf.array()
                fos.write(array)
                fos.close()
                Log.d(TAG, "Wrote sound file $outFile")
            } catch (ioe: IOException) {
                Log.e(TAG, "sound file op failed: ${ioe.message}")
                throw RuntimeException(ioe)
            }
        } else {
            Log.d(TAG, "Sound '${outFile.name}' exists, not regenerating");
        }
        val handle = soundPool.load(outFile.toString(), 1)
        return Sound(name, soundPool, handle)
    }

    /**
     * A self-contained sound effect.
     * Creates a new sound for a SoundPool entry.
     *
     * @param name A name to use for debugging.
     * @param soundPool The SoundPool that holds the sound.
     * @param handle The handle for the sound within the SoundPool.
     */
    private class Sound(
        private val mName: String,
        private val mSoundPool: SoundPool,
        private val mHandle: Int
    ) {
        private val mVolume = 0.5f

        /**
         * Plays the sound.
         */
        fun play() {
            /*
             * Contrary to popular opinion, it is not necessary to manually scale the volume
             * to the system volume level.  This is handled automatically by SoundPool.
             */
            Log.d(TAG, "SOUND: play '$mName' @");
            mSoundPool.play(mHandle, mVolume, mVolume, 1, 0, 1.0f)
        }
    }

    companion object {

        /*
     * We have very simple needs, so we just generate our sounds with a tone generator.  The
     * Android SoundPool API doesn't let us play sounds from byte buffers, though, so we need to
     * generate a WAV file in our private app storage area and then tell the system to load it.
     * A more common approach would be to generate the sounds ahead of time and include them in
     * the APK, and let SoundPool load them from Android resources.
     *
     * As with TextResources, we're doing some initialization on one thread (e.g. loading
     * sound data from resources obtained through the Activity context) and using it on
     * a different thread (the game renderer, which doesn't want to know about Activity).
     * Unlike TextResources, we don't need to do anything with OpenGL, and the sounds don't
     * change based on device settings, so we can just load all of the sounds immediately and
     * keep a static reference to them.
     *
     * We create a single, immutable instance of the class to hold the data.  Once created,
     * any thread that can see the reference is guaranteed to be able to see all of the data.
     * (We don't actually guarantee that other threads can see our singleton reference, but a
     * simple null check will handle that.)
     *
     * Note that the sound data won't be discarded when the game Activity goes away, because
     * it's held by the class.  For our purposes that's reasonable, and perhaps even desirable.
     */
        // Pass these as arguments to playSound().
        const val BRICK_HIT = 0
        const val PADDLE_HIT = 1
        const val WALL_HIT = 2
        const val BALL_LOST = 3
        private const val NUM_SOUNDS = 4

        // Parameters for our generated sounds.
        private const val SAMPLE_RATE = 22050
        private const val NUM_CHANNELS = 1
        private const val BITS_PER_SAMPLE = 8

        // Singleton instance.
        private var sSoundResources: SoundResources? = null

        // Maximum simultaneous sounds.  Four seems nice.
        private const val MAX_STREAMS = 4

        // Global mute flag.  This should arguably be in GameState, i.e. the game shouldn't be trying
        // to play sounds at all, but it's convenient to have a single check in the code here.  This
        // is not immutable state, so it does not belong in the singleton.
        private var sSoundEffectsEnabled = true

        /**
         * Initializes global data.  We have a small, fixed set of sounds, so we just load them all
         * statically.  Call this when the game activity starts.
         *
         *
         * We need the application context to figure out where files will live.
         */
        @Synchronized
        fun initialize(context: Context) {
            /*
         * In theory, this could be called from two different threads at the same time, and
         * we'd end up with two sets of sounds.  This isn't a huge problem for us, but the
         * correct thing to do is use a mutex to ensure it only gets initialized once.
         */
            if (sSoundResources == null) {
                val dir = context.filesDir
                sSoundResources = SoundResources(dir)
            }
        }

        /**
         * Starts playing the specified sound.
         */
        fun play(soundNum: Int) {
            /*
         * Because this method is not declared synchronized, we're not actually guaranteed to
         * see the initialization of sSoundResources.  The immutable instance rules do
         * guarantee that we either see a null pointer or a fully-constructed instance, so
         * rather than using "synchronized" or "volatile" we just do a null check here.
         */
            if (sSoundEffectsEnabled) {
                val instance = sSoundResources
                instance?.mSounds?.get(soundNum)!!.play()
            }
        }

        /**
         * Sets the "sound effects enabled" flag.  If disabled, sounds will still be loaded but
         * won't be played.
         */
        fun setSoundEffectsEnabled(enabled: Boolean) {
            sSoundEffectsEnabled = enabled
        }

        /**
         * Generates the 44-byte WAV file header.
         */
        private fun generateWavHeader(sampleCount: Int): ByteBuffer {
            val numDataBytes = sampleCount * NUM_CHANNELS * BITS_PER_SAMPLE / 8
            val buf = ByteBuffer.allocate(44)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0x46464952) // 'RIFF'
            buf.putInt(36 + numDataBytes)
            buf.putInt(0x45564157) // 'WAVE'
            buf.putInt(0x20746d66) // 'fmt '
            buf.putInt(16)
            buf.putShort(1.toShort()) // audio format PCM
            buf.putShort(NUM_CHANNELS.toShort())
            buf.putInt(SAMPLE_RATE)
            buf.putInt(SAMPLE_RATE * NUM_CHANNELS * BITS_PER_SAMPLE / 8)
            buf.putShort((NUM_CHANNELS * BITS_PER_SAMPLE / 8).toShort())
            buf.putShort(BITS_PER_SAMPLE.toShort())
            buf.putInt(0x61746164) // 'data'
            buf.putInt(numDataBytes)
            buf.position(0)
            return buf
        }

        /**
         * Generates the raw WAV-compatible audio data.
         */
        private fun generateWavData(sampleCount: Int, freqHz: Int): ByteBuffer {
            val numDataBytes = sampleCount * NUM_CHANNELS * BITS_PER_SAMPLE / 8
            val freq = freqHz.toDouble()
            val buf = ByteBuffer.allocate(numDataBytes)
            buf.order(ByteOrder.LITTLE_ENDIAN)

            // We can generate 8-bit or 16-bit sound.  For these short simple tones it won't make
            // an audible difference.
            if (BITS_PER_SAMPLE == 8) {
                val peak = 127.0
                for (i in 0 until sampleCount) {
                    val timeSec = i / SAMPLE_RATE.toDouble()
                    val sinValue = Math.sin(2 * Math.PI * freq * timeSec)
                    // 8-bit data is unsigned, 0-255
                    if (GameSurfaceRenderer.EXTRA_CHECK) {
                        val output = (peak * sinValue + 127.0).toInt()
                        if (output < 0 || output >= 256) {
                            throw RuntimeException("bad byte gen")
                        }
                    }
                    buf.put((peak * sinValue + 127.0).toInt().toByte())
                }
            } else if (BITS_PER_SAMPLE == 16) {
                val peak = 32767.0
                val sbuf = buf.asShortBuffer()
                for (i in 0 until sampleCount) {
                    val timeSec = i / SAMPLE_RATE.toDouble()
                    val sinValue = sin(2 * Math.PI * freq * timeSec)
                    // 16-bit data is signed, +/- 32767
                    sbuf.put((peak * sinValue).toInt().toShort())
                }
            }
            buf.position(0)
            return buf
        }
    }

    /**
     * Constructs the object.  All sounds are generated and loaded into the sound pool.
     */
    init {
        val soundPool = SoundPool(MAX_STREAMS, AudioManager.STREAM_MUSIC, 0)
        soundPool.setOnLoadCompleteListener(this)
        generateSoundFiles(soundPool, privateDir)
        if (false) {
            // Sleep briefly to allow SoundPool to finish loading, then play each sound.
            try {
                Thread.sleep(1000)
            } catch (ie: InterruptedException) {
            }
            for (i in 0 until NUM_SOUNDS) {
                mSounds[i]!!.play()
                try {
                    Thread.sleep(800)
                } catch (ie: InterruptedException) {
                }
            }
        }
    }
}