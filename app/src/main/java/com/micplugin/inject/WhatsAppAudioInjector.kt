package com.micplugin.inject

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.micplugin.BuildConfig
import com.micplugin.R
import com.micplugin.inject.IWhatsAppMicInjector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

object WhatsAppLiveState {
    private val mutableMessage = kotlinx.coroutines.flow.MutableStateFlow("WhatsApp live mic is off")
    val message: kotlinx.coroutines.flow.StateFlow<String> = mutableMessage
    internal fun set(value: String) { mutableMessage.value = value }
}

/**
 * App-side controller for the Shizuku UserService. The remote service is allowed
 * to create an AudioPolicy only for the installed official WhatsApp UID.
 */
@Singleton
class WhatsAppAudioInjector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TARGET_PACKAGE = "com.whatsapp"
        private const val SAMPLE_RATE = 48_000
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val lock = Any()
    @Volatile private var remote: IWhatsAppMicInjector? = null
    private var pending: CompletableDeferred<IWhatsAppMicInjector>? = null
    private var bindingStarted = false

    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context, WhatsAppAudioUserService::class.java)
        )
            .daemon(false)
            .processNameSuffix("whatsapp-audio")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val api = service?.let { IWhatsAppMicInjector.Stub.asInterface(it) }
            synchronized(lock) {
                remote = api
                val deferred = pending
                pending = null
                bindingStarted = false
                if (api != null) deferred?.complete(api)
                else deferred?.completeExceptionally(IllegalStateException("Shizuku returned an empty injector Binder"))
            }
            WhatsAppLiveState.set(if (api != null) "Shizuku shell service connected" else "Shizuku injector Binder unavailable")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                remote = null
                pending?.completeExceptionally(IllegalStateException("Shizuku injector disconnected"))
                pending = null
                bindingStarted = false
            }
            WhatsAppLiveState.set("Shizuku injector disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    private suspend fun ensureConnected(): IWhatsAppMicInjector {
        remote?.let { return it }

        val deferred: CompletableDeferred<IWhatsAppMicInjector>
        var shouldBind = false
        synchronized(lock) {
            remote?.let { return it }
            if (pending != null) {
                deferred = pending!!
            } else {
                deferred = CompletableDeferred()
                pending = deferred
                bindingStarted = true
                shouldBind = true
            }
        }

        if (shouldBind) {
            withContext(Dispatchers.Main.immediate) {
                try {
                    Shizuku.bindUserService(args, connection)
                } catch (error: Throwable) {
                    synchronized(lock) {
                        if (pending === deferred) pending = null
                        bindingStarted = false
                    }
                    deferred.completeExceptionally(error)
                }
            }
        }
        return withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    suspend fun startWhatsApp(): String = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder()) {
            WhatsAppLiveState.set("Shizuku is not running. Start Shizuku through Wireless debugging.")
            return@withContext "ERROR: Shizuku binder is not available"
        }
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
            WhatsAppLiveState.set("Update Shizuku to version 13 or newer for UserService support.")
            return@withContext "ERROR: Shizuku 13+ is required"
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            WhatsAppLiveState.set("Grant Shizuku permission to WEIRDO in Shizuku Manager, then retry.")
            return@withContext "ERROR: Shizuku permission is not granted"
        }

        val targetUid = try {
            context.packageManager.getApplicationInfo(TARGET_PACKAGE, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            WhatsAppLiveState.set("Official WhatsApp package com.whatsapp is not installed.")
            return@withContext "ERROR: official WhatsApp package not found"
        }

        try {
            WhatsAppLiveState.set("Connecting to Shizuku shell…")
            val api = ensureConnected()
            WhatsAppLiveState.set("Registering WhatsApp-only microphone route…")
            val result = api.start(targetUid, SAMPLE_RATE)
            WhatsAppLiveState.set(result)
            result
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            WhatsAppLiveState.set("Could not start the WhatsApp microphone route: $message")
            "ERROR: $message"
        }
    }

    fun pullInputPcm(waitMs: Int = 25): ByteArray {
        val api = remote ?: return ByteArray(0)
        return try {
            api.pullInputPcm(waitMs.coerceIn(0, 25)) ?: ByteArray(0)
        } catch (error: Throwable) {
            WhatsAppLiveState.set("Microphone input bridge failed: ${error.message}")
            ByteArray(0)
        }
    }

    fun pushProcessedPcm(pcm16le: ByteArray) {
        val api = remote ?: return
        try {
            api.pushOutputPcm(pcm16le)
        } catch (error: Throwable) {
            WhatsAppLiveState.set("Microphone output bridge failed: ${error.message}")
        }
    }

    fun remoteStatus(): String =
        runCatching { remote?.getStatus() ?: "not connected" }.getOrDefault("not connected")

    suspend fun stopWhatsApp() = withContext(Dispatchers.IO) {
        runCatching { remote?.stop() }
        WhatsAppLiveState.set("WhatsApp live mic is off; physical microphone restored")
    }

    fun unbind() {
        runCatching { remote?.stop() }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        synchronized(lock) {
            remote = null
            pending?.completeExceptionally(IllegalStateException("Injector unbound"))
            pending = null
            bindingStarted = false
        }
    }
}
