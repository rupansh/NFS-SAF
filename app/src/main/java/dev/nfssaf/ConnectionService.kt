package dev.nfssaf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.nfssaf.core.OperationGate
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-started connected-device service. No background-start or boot loopholes. */
class ConnectionService : Service() {
    private val services
        get() = Services.get(this)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wake: PowerManager.WakeLock
    private var stopping = false
    private var foreground = false
    private var lastCount = -1
    private var renewWakeAt = 0L
    private val pulse =
        object : Runnable {
            override fun run() {
                val count = services.backend.openCount
                val now = SystemClock.elapsedRealtime()
                if (count > 0 && (!wake.isHeld || now >= renewWakeAt)) {
                    wake.acquire(WAKE_TIMEOUT_MILLIS)
                    renewWakeAt = now + WAKE_RENEW_MILLIS
                }
                if (count == 0 && wake.isHeld) wake.release()
                if (count != lastCount) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(count))
                    lastCount = count
                }
                main.postDelayed(this, 1000)
            }
        }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "NFS connections", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while NFS connections are running" }
            )
        wake =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nfssaf:open-files")
        wake.setReferenceCounted(false)
    }

    // Each early return is a terminal service lifecycle decision.
    @Suppress("ReturnCount")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(
                    NOTIFICATION_ID,
                    notification(0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            else startForeground(NOTIFICATION_ID, notification(0))
            foreground = true
        }
        if (intent?.action == STOP) {
            stopGracefully()
            return START_NOT_STICKY
        }
        if (!stopping && services.backend.gate.state() == OperationGate.State.Draining) {
            stopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!stopping) {
            services.backend.start()
            mutableStatus.value = OperationGate.State.Running
            main.removeCallbacks(pulse)
            main.post(pulse)
        }
        return START_NOT_STICKY
    }

    private fun notification(count: Int): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ConnectionService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_storage)
            .setContentTitle(
                if (stopping) "Finishing NFS operations" else "NFS connections running"
            )
            .setContentText(
                if (stopping) "Closing files and disconnecting safely…"
                else if (count == 0) "Ready for Android apps · Tap to manage"
                else "$count open file${if(count==1) "" else "s"} · Keeping server state active"
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply {
                if (!stopping)
                    addAction(Notification.Action.Builder(null, "Stop connections", stop).build())
            }
            .build()
    }

    private fun stopGracefully() {
        if (stopping) return
        stopping = true
        mutableStatus.value = OperationGate.State.Draining
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(services.backend.openCount))
        val ticket = services.backend.requestDrain()
        cleanup.execute {
            val errors = services.backend.drain(ticket)
            recordErrors(errors)
            main.post {
                main.removeCallbacks(pulse)
                if (wake.isHeld) wake.release()
                mutableStatus.value = OperationGate.State.Stopped
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        // Best effort for OS-initiated destruction. Force-stop does not call onDestroy.
        if (!stopping) {
            mutableStatus.value = OperationGate.State.Draining
            val ticket = services.backend.requestDrain()
            cleanup.execute {
                recordErrors(services.backend.drain(ticket))
                if (wake.isHeld) wake.release()
                mutableStatus.value = OperationGate.State.Stopped
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Persist the error before service teardown; the process can die immediately afterward.
    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    private fun recordErrors(errors: List<Throwable>) {
        if (errors.isNotEmpty())
            services.errors
                .edit()
                .putString(
                    "last",
                    "While stopping: ${errors.joinToString { it.message ?: "Close failed" }}",
                )
                .commit()
    }

    companion object {
        private const val WAKE_TIMEOUT_MILLIS = 60_000L
        private const val WAKE_RENEW_MILLIS = 30_000L
        const val NOTIFICATION_ID = 100
        private const val CHANNEL = "nfs-connections"
        private const val STOP = "dev.nfssaf.STOP"
        private val cleanup = Executors.newSingleThreadExecutor()
        private val mutableStatus =
            MutableStateFlow<OperationGate.State>(OperationGate.State.Stopped)
        val status = mutableStatus.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ConnectionService::class.java).setAction(STOP))
        }
    }
}
