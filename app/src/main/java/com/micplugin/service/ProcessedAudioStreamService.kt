package com.micplugin.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.micplugin.audio.IProcessedAudioStream

/**
 * Exported, read-only PCM service for the explicitly modified WhatsApp package.
 * The manifest permission is checked by Android; package identity is checked again
 * on every Binder method to prevent other apps from reading the microphone stream.
 */
class ProcessedAudioStreamService : Service() {

    private val binder = object : IProcessedAudioStream.Stub() {
        override fun openPcmStream(): ParcelFileDescriptor {
            enforceWhatsAppCaller()
            return ProcessedAudioBridge.openPcmStream()
        }

        override fun getSampleRate(): Int {
            enforceWhatsAppCaller()
            return ProcessedAudioBridge.sampleRate
        }

        override fun getChannelCount(): Int {
            enforceWhatsAppCaller()
            return 1
        }

        override fun getEncoding(): Int {
            enforceWhatsAppCaller()
            return android.media.AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun enforceWhatsAppCaller() {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid).orEmpty()
        if ("com.whatsapp" !in packages) {
            throw SecurityException("Processed microphone access is restricted to the modified WhatsApp package.")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        ProcessedAudioBridge.close()
        super.onDestroy()
    }
}
