package com.swordfish.lemuroid.app.mobile.feature.game

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import dagger.android.DaggerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

class GameService : DaggerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        liveInstances.incrementAndGet()
        serviceScope.launch {
            awaitTermination()
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@GameService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                exitProcess(0)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent,
        flags: Int,
        startId: Int,
    ): Int {
        displayNotification(intent)
        return START_NOT_STICKY
    }

    private fun displayNotification(intent: Intent) {
        // The game running notification is optional: only promote the service to the foreground
        // (which requires posting a notification) when the user has granted the permission.
        // Without it we keep the service as a plain started service and show nothing.
        if (!hasNotificationPermission()) {
            return
        }
        val gameIntent =
            intent.getParcelableExtra<Intent>(EXTRA_GAME_ACTIVITY_INTENT)
                ?: return
        val notification = NotificationsManager(applicationContext).gameRunningNotification(gameIntent)
        ServiceCompat.startForeground(
            this,
            NotificationsManager.GAME_RUNNING_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hideNotification() {
        NotificationManagerCompat.from(this).cancel(NotificationsManager.GAME_RUNNING_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        liveInstances.decrementAndGet()
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        hideNotification()
    }

    companion object {
        private const val EXTRA_GAME_ACTIVITY_INTENT = "EXTRA_GAME_ACTIVITY_INTENT"

        private data class GameProcessTask(
            val task: suspend () -> Unit = {},
            val terminate: Boolean = false,
        )

        private val tasks = Channel<GameProcessTask>(capacity = Channel.BUFFERED)

        /**
         * Runs the queued tasks for as long as the process does, rather than for as long as the
         * service instance does.
         *
         * The last task a session queues is the auto-save written as the game is backgrounded, and
         * the service is at its least dependable exactly then: it is only ever promoted to the
         * foreground when the notification permission has been granted, so without it the system is
         * free to tear the service down while the process lives on. A pump owned by the instance
         * goes down with it, and every task queued afterwards sits in the channel unnoticed, which
         * is a whole session's progress dropped without a word.
         */
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Completed once a queued termination has been reached, so the service can shut down. */
        private val terminationReached = CompletableDeferred<Unit>()

        private var pump: Job? = null

        /**
         * Service instances able to act on a termination. Shutting the process down is the service's
         * job, but nothing guarantees one is still around by the time a termination is reached, and
         * a game process left running with no activity and no service would never go away.
         */
        private val liveInstances = AtomicInteger(0)

        fun startService(
            context: Context,
            gameActivityIntent: Intent,
        ) {
            context.startService(
                Intent(context, GameService::class.java).apply {
                    putExtra(EXTRA_GAME_ACTIVITY_INTENT, gameActivityIntent)
                },
            )
        }

        fun schedule(task: suspend () -> Unit) {
            val result = enqueue(GameProcessTask(task = task))
            Timber.i("GameService.schedule sent=%s", result)
        }

        fun requestTermination() {
            val result = enqueue(GameProcessTask(terminate = true))
            Timber.i("GameService.requestTermination sent=%s", result)
        }

        @Synchronized
        private fun enqueue(task: GameProcessTask): Boolean {
            if (pump?.isActive != true) {
                pump = processScope.launch { drainTasks() }
            }
            return tasks.trySend(task).isSuccess
        }

        private suspend fun drainTasks() {
            for (task in tasks) {
                runCatching { task.task() }
                    .onFailure { Timber.e(it, "GameService task failed") }

                if (task.terminate) {
                    terminationReached.complete(Unit)
                    if (liveInstances.get() == 0) {
                        Timber.i("Terminating the game process with no service left to do it")
                        exitProcess(0)
                    }
                    return
                }
            }
        }

        private suspend fun awaitTermination() {
            terminationReached.await()
        }
    }
}
