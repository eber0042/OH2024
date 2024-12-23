package com.temi.oh2024

import android.content.Context
import android.media.MediaPlayer

class AudioPlayer(context: Context, private val mediaResId: Int) {
    private val mediaPlayer: MediaPlayer = MediaPlayer.create(context, mediaResId).apply {
        isLooping = true  // Set looping to true so it repeats automatically
    }

    // Function to play the audio
    fun play() {
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
        }
    }

    // Function to stop the audio and reset to the beginning
    fun stop() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            mediaPlayer.seekTo(0)
        }
    }

    // Function to release the media player resources
    fun release() {
        mediaPlayer.release()
    }

    // Function to set the volume (value between 0.0f and 1.0f for both left and right channels)
    fun setVolume(leftVolume: Float, rightVolume: Float) {
        mediaPlayer.setVolume(leftVolume, rightVolume)
    }

    // Function to set the volume to a specific level (for example, 0.5 for 50% volume)
    fun setVolumeLevel(volumeLevel: Float) {
        val volume = volumeLevel.coerceIn(0.0f, 1.0f)  // Ensure the value is between 0.0 and 1.0
        mediaPlayer.setVolume(
            volume,
            volume
        )  // Set the same volume for both left and right channels
    }

    // Function to play the audio only once
    fun playOnce() {
        // Reset looping and seek to the beginning
        mediaPlayer.isLooping = false
        mediaPlayer.seekTo(0)

        // Start the audio
        mediaPlayer.start()

        // Stop the media player when playback is complete
        mediaPlayer.setOnCompletionListener { mp ->
            mp.seekTo(0) // Reset to the beginning
            mp.setOnCompletionListener(null) // Remove listener to avoid memory leaks
            mp.release() // Release the media player to free resources
        }
    }
}