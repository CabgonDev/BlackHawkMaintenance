package com.cabgon.blackhawk.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cabgon.blackhawk.BuildConfig
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.ai.update.AppUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UpdateDownloadService : Service() {

    companion object {
        private const val TAG = "UpdateDownloadService"

        private const val CHANNEL_ID = "bhm_update_channel"
        private const val NOTIF_ID_ONGOING = 1001
        private const val NOTIF_ID_COMPLETE = 1002

        const val ACTION_CANCEL = "com.cabgon.blackhawk.UPDATE_CANCEL"

        fun start(
            context: Context,
            apk: AppUpdateManager.ApkSpec
        ) {
            Log.d(
                TAG,
                "start(): version=${apk.versionCode} path=${apk.storagePath} url=${apk.downloadUrl}"
            )

            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                putExtra("versionCode", apk.versionCode)
                putExtra("storagePath", apk.storagePath)
                putExtra("sha256", apk.sha256)
                apk.bytes?.let { putExtra("bytes", it) }
                putExtra("releaseNotes", apk.releaseNotes)
                putExtra("downloadUrl", apk.downloadUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    @Volatile
    private var cancelRequested: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() startId=$startId intent=$intent")

        if (intent == null) {
            Log.w(TAG, "onStartCommand(): intent is null, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // CANCEL desde la app
        if (intent.action == ACTION_CANCEL) {
            Log.d(TAG, "onStartCommand(): CANCEL received")

            cancelRequested = true
            currentJob?.cancel()

            val nm = getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIF_ID_ONGOING)

            // Avisamos a la Activity que se canceló
            UpdateDownloadEventBus.emit(
                UpdateDownloadEvent.Finished(success = false, percent = -1)
            )

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Inicio normal de descarga
        cancelRequested = false

        val versionCode = intent.getLongExtra("versionCode", -1L)
        val storagePath = intent.getStringExtra("storagePath") ?: ""
        val sha256 = intent.getStringExtra("sha256")
        val bytesExtra = intent.getLongExtra("bytes", -1L)
        val bytes = if (bytesExtra > 0L) bytesExtra else null
        val releaseNotes = intent.getStringExtra("releaseNotes")
        val downloadUrl = intent.getStringExtra("downloadUrl")

        Log.d(
            TAG,
            "onStartCommand(): versionCode=$versionCode storagePath=$storagePath url=$downloadUrl bytes=$bytes sha=$sha256"
        )

        if (versionCode <= 0L) {
            Log.w(TAG, "onStartCommand(): invalid versionCode, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val apk = AppUpdateManager.ApkSpec(
            versionCode = versionCode,
            storagePath = storagePath,
            sha256 = sha256,
            bytes = bytes,
            releaseNotes = releaseNotes,
            downloadUrl = downloadUrl
        )

        // Notificación mínima requerida por Android para foreground service
        val initialNotif = buildOngoingNotification()
        Log.d(TAG, "onStartCommand(): calling startForeground()")
        startForeground(NOTIF_ID_ONGOING, initialNotif)

        currentJob = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)

            try {
                val ok = AppUpdateManager.downloadAndPromptInstall(
                    applicationContext,
                    apk
                ) { done, total ->
                    if (cancelRequested) return@downloadAndPromptInstall

                    val percent = when {
                        total > 0L -> ((done * 100) / total).toInt().coerceIn(0, 100)
                        apk.bytes != null && apk.bytes > 0L ->
                            ((done * 100) / apk.bytes).toInt().coerceIn(0, 100)
                        else -> -1
                    }

                    // 🚫 NO actualizamos notificación de progreso
                    // ✅ Solo mandamos evento a la Activity
                    UpdateDownloadEventBus.emit(
                        UpdateDownloadEvent.Progress(percent)
                    )
                }

                if (cancelRequested) {
                    // Ya se manejó en ACTION_CANCEL
                    return@launch
                }

                // Quitamos la notificación ongoing
                nm?.cancel(NOTIF_ID_ONGOING)
                stopForeground(STOP_FOREGROUND_REMOVE)

                // Evento de fin para la Activity
                UpdateDownloadEventBus.emit(
                    UpdateDownloadEvent.Finished(
                        success = ok,
                        percent = if (ok) 100 else -1
                    )
                )

                // ✅ Notificación SOLO de descarga completada (si tuvo éxito)
                if (ok && !UpdateDownloadVisibility.isUpdateActivityVisible) {
                    val completeNotif = buildCompletionNotification()
                    nm?.notify(NOTIF_ID_COMPLETE, completeNotif)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "download coroutine cancelled: ${e.message}")
                if (!cancelRequested) {
                    val nm2 = getSystemService(NotificationManager::class.java)
                    nm2?.cancel(NOTIF_ID_ONGOING)
                    UpdateDownloadEventBus.emit(
                        UpdateDownloadEvent.Finished(success = false, percent = -1)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "download coroutine failed: ${e.message}", e)
                val nm2 = getSystemService(NotificationManager::class.java)
                nm2?.cancel(NOTIF_ID_ONGOING)
                stopForeground(STOP_FOREGROUND_REMOVE)

                UpdateDownloadEventBus.emit(
                    UpdateDownloadEvent.Finished(
                        success = false,
                        percent = -1
                    )
                )
            } finally {
                Log.d(TAG, "stopping foreground + service (finally)")
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Actualización de BlackHawk Maintenance",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
            Log.d(TAG, "createChannel(): channel created")
        }
    }

    /**
     * Notificación mínima para el foreground service mientras descarga.
     * Sin progreso, sin botón cancelar, solo para cumplir con Android.
     */
    private fun buildOngoingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BlackHawk Maintenance")
            .setContentText("Descargando actualización...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSubText("v${BuildConfig.VERSION_NAME}")
            .build()
    }

    /**
     * Notificación final de "descarga completada".
     * Al tocarla, abre la app (Main/Launcher).
     */
    private fun buildCompletionNotification(): Notification {
        // Intent para abrir la app (actividad launcher)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, UpdateRequiredActivity::class.java)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (PendingIntent.FLAG_IMMUTABLE)

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Actualización descargada")
            .setContentText("Presiona para abrir BlackHawk Maintenance.")
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}
