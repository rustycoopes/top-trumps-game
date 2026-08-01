package com.toptrumps.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.toptrumps.session.MatchView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "match"
private const val NOTIFICATION_ID = 1

/**
 * A `connectedDevice` foreground service for the duration of a two-device match — see the
 * foreground-service ADR. [AppGraph] starts and stops it exactly around a match's lifetime;
 * never used for solo mode or the lobby. `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_CONNECTED_DEVICE`
 * are normal permissions (no prompt); `POST_NOTIFICATIONS` is runtime but this service runs
 * regardless of whether it's granted — denial only hides the notification (see [updateNotification]).
 */
public class MatchForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Match in progress", NotificationManager.IMPORTANCE_LOW),
        )
    }

    // The FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE constant is API 29+ (minSdk is 26), but it's
    // inlined by the compiler rather than looked up at runtime, and ServiceCompat.startForeground
    // itself only actually passes a type through on API levels that understand one — safe below 29.
    @Suppress("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Synchronous, well inside the 5s deadline API 31+ enforces on a foreground-started service.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(peerName = null, roundText = null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        observeMatch()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observeMatch() {
        val appGraph = (application as TopTrumpsApplication).appGraph
        scope.launch {
            appGraph.activeMatch.collectLatest { controller ->
                if (controller == null) return@collectLatest
                val peerName = appGraph.activeMatchPeerName.value
                controller.phase.collectLatest { phase ->
                    val session = (phase as? MatchPhase.InMatch)?.session
                    if (session == null) {
                        updateNotification(peerName, roundText = null)
                    } else {
                        session.view.collectLatest { view ->
                            val roundText = (view as? MatchView.InProgress)?.let { "round ${it.roundNumber} of ${it.totalRounds}" }
                            updateNotification(peerName, roundText)
                        }
                    }
                }
            }
        }
    }

    private fun updateNotification(peerName: String?, roundText: String?) {
        // Denial only hides the notification — the service itself keeps running either way,
        // which is the whole point of `connectedDevice` not requiring the permission.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(peerName, roundText))
    }

    private fun buildNotification(peerName: String?, roundText: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (peerName != null) "Playing $peerName" else "Top Trumps match")
            .setContentText(roundText ?: "Connecting…")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
}
