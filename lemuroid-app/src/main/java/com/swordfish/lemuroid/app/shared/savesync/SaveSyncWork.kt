package com.swordfish.lemuroid.app.shared.savesync

import android.content.Context
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import coil.imageLoader
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.lib.injection.AndroidWorkerInjection
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.library.findByName
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.savesync.SaveSyncResult
import dagger.Binds
import dagger.android.AndroidInjector
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.guava.await
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SaveSyncWork(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    @Inject
    lateinit var saveSyncManager: SaveSyncManager

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var lemuroidLibrary: LemuroidLibrary

    override suspend fun doWork(): Result {
        AndroidWorkerInjection.inject(this)

        if (!shouldPerformSaveSync()) {
            return Result.success()
        }

        val coresToSync =
            settingsManager
                .syncStatesCores()
                .mapNotNull { findByName(it) }
                .toSet()

        val syncResult =
            try {
                saveSyncManager.sync(coresToSync)
            } catch (e: Throwable) {
                Timber.e(e, "Error in saves sync")
                SaveSyncResult(isPartial = true)
            }

        // Runs even if the sync failed, since it may have transferred some files before giving up.
        refreshSyncedCovers(syncResult)

        return Result.success()
    }

    /**
     * Custom artwork travels with the sync, but the games pointing at it live in the database, so a
     * cover which just arrived (or which the sync deleted) has to be picked up here.
     *
     * Only a sync which could have moved one of those files is reconciled. Reconciling reads every
     * game row, and the periodic sync runs whether or not there is anything to do, so doing it
     * unconditionally meant walking the whole library every three hours to find nothing. A run which
     * gave up part way through is reconciled all the same, since it does not report what it managed
     * to transfer first.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private suspend fun refreshSyncedCovers(syncResult: SaveSyncResult) {
        if (syncResult.changedCovers.isEmpty() && !syncResult.isPartial) {
            return
        }

        val reconciledCovers =
            runCatching { lemuroidLibrary.refreshCustomCovers() }
                .getOrElse {
                    Timber.e(it, "Error while refreshing custom artwork")
                    emptyList()
                }

        // Reconciling catches games which changed which image they point at, while the sync reports
        // the files it rewrote in place. The latter keep the same url, so nothing else would reveal
        // that what sits behind it is no longer the same picture.
        val staleCovers = reconciledCovers.toSet() + syncResult.changedCovers.map { it.toUri().toString() }

        if (staleCovers.isEmpty()) {
            return
        }

        Timber.i("Invalidating ${staleCovers.size} cached covers")
        val imageLoader = applicationContext.imageLoader
        staleCovers.forEach { imageLoader.diskCache?.remove(it) }
        imageLoader.memoryCache?.clear()
    }

    private suspend fun shouldPerformSaveSync(): Boolean {
        val conditionsToRunThisWork =
            flow {
                emit(saveSyncManager.isSupported())
                emit(saveSyncManager.isConfigured())
                emit(settingsManager.syncSaves())
                emit(shouldScheduleThisSync())
            }

        return conditionsToRunThisWork.firstOrNull { !it } ?: true
    }

    private suspend fun shouldScheduleThisSync(): Boolean {
        val isAutoSync = inputData.getBoolean(IS_AUTO, false)
        val isManualSync = !isAutoSync
        return (settingsManager.autoSaveSync() && isAutoSync) || isManualSync
    }

    companion object {
        val UNIQUE_WORK_ID: String = SaveSyncWork::class.java.simpleName
        val UNIQUE_PERIODIC_WORK_ID: String = SaveSyncWork::class.java.simpleName + "Periodic"
        private const val IS_AUTO = "IS_AUTO"

        fun enqueueManualWork(applicationContext: Context) {
            enqueue(applicationContext, buildManualWorkRequest())
        }

        /**
         * Enqueues a manual sync and hands back the id of that run. It waits for WorkManager to take
         * the request on record, not for the sync to happen.
         *
         * A caller which has to follow one particular run needs the id, because the unique name also
         * matches the run before this one. That earlier run may well be sitting there finished, and
         * waiting for a finished run is over before it begins.
         */
        suspend fun enqueueManualWorkAndGetId(applicationContext: Context): UUID {
            val request = buildManualWorkRequest()

            // Until the enqueue itself has gone through, a lookup by this id finds nothing, which is
            // indistinguishable from a run which has already been and gone.
            //
            // Awaited through the coroutines library rather than with Operation.await(), which is an
            // inline function over a ListenableFuture extension WorkManager restricts to its own
            // library group, leaving the restricted call at this call site.
            enqueue(applicationContext, request).result.await()

            return request.id
        }

        private fun buildManualWorkRequest() =
            OneTimeWorkRequestBuilder<SaveSyncWork>()
                .setInputData(workDataOf(IS_AUTO to false))
                .build()

        private fun enqueue(
            applicationContext: Context,
            request: OneTimeWorkRequest,
        ): Operation =
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_ID,
                ExistingWorkPolicy.REPLACE,
                request,
            )

        fun enqueueAutoWork(
            applicationContext: Context,
            delayMinutes: Long = 0,
        ) {
            val inputData: Data = workDataOf(IS_AUTO to true)

            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_ID,
                ExistingPeriodicWorkPolicy.REPLACE,
                PeriodicWorkRequestBuilder<SaveSyncWork>(3, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).setInputData(inputData)
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .build(),
            )
        }

        fun cancelManualWork(applicationContext: Context) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(UNIQUE_WORK_ID)
        }

        fun cancelAutoWork(applicationContext: Context) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(UNIQUE_PERIODIC_WORK_ID)
        }
    }

    @dagger.Module(subcomponents = [Subcomponent::class])
    abstract class Module {
        @Binds
        @IntoMap
        @ClassKey(SaveSyncWork::class)
        abstract fun bindMyWorkerFactory(builder: Subcomponent.Builder): AndroidInjector.Factory<*>
    }

    @dagger.Subcomponent
    interface Subcomponent : AndroidInjector<SaveSyncWork> {
        @dagger.Subcomponent.Builder
        abstract class Builder : AndroidInjector.Builder<SaveSyncWork>()
    }
}
