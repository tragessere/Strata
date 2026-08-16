package com.swordfish.lemuroid.ext.feature.core

import android.content.Context
import android.util.Log
import com.google.android.play.core.ktx.hasTerminalStatus
import com.google.android.play.core.ktx.requestCancelInstall
import com.google.android.play.core.ktx.requestInstall
import com.google.android.play.core.ktx.requestProgressFlow
import com.google.android.play.core.ktx.requestSessionStates
import com.google.android.play.core.ktx.sessionId
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.swordfish.lemuroid.common.coroutines.retry
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import retrofit2.Retrofit
import kotlin.time.Duration.Companion.seconds

class CoreUpdaterImpl(
    private val directoriesManager: DirectoriesManager,
    retrofit: Retrofit,
    private val coreLibrariesBundled: Boolean,
) : CoreUpdater {
    private val api = retrofit.create(CoreUpdater.CoreManagerApi::class.java)

    override suspend fun downloadCores(
        context: Context,
        coreIDs: List<CoreID>,
    ) {
        // Assets are fetched over the network in every flavor, so they are still needed when the core
        // libraries themselves ship inside the apk. Cheap once they are current, since
        // retrieveAssetsIfNeeded returns immediately unless the stored version key is stale.
        try {
            installAssets(context, coreIDs)
        } catch (e: Throwable) {
            log("Error while installing assets: ${e.message}")
        }

        // A bundled build has nothing to deliver on demand, and it does not even produce the modules
        // this would ask for: the cores flavor decides whether the :lemuroid_core_* projects are part
        // of the build at all. Asking Play to install modules which were never uploaded only burns the
        // retry budget below while the UI shows an operation in progress.
        if (coreLibrariesBundled) {
            log("Core libraries are bundled in the apk. Skipping Play delivery.")
            return
        }

        val installManager = SplitInstallManagerFactory.create(context)
        val installSession = requestCoresInstall(installManager, coreIDs)

        if (installSession != null) {
            try {
                waitForCompletion(installSession, installManager)
            } catch (e: Throwable) {
                log("Error while waiting for core install: ${e.message}")
            }
        }

        // Deliberately runs even when nothing was requested here. This cancels sessions left behind by
        // earlier runs, and a run which found everything already installed is exactly the one most
        // likely to have a stale session to clear.
        try {
            cancelPendingInstalls(installManager, installSession)
        } catch (e: Throwable) {
            log("Error while canceling pending installs: ${e.message}")
        }

        log("downloadCores has terminated")
    }

    private fun computePlayModuleName(it: CoreID) = "lemuroid_core_${it.coreName}"

    private suspend fun waitForCompletion(
        sessionId: Int,
        installManager: SplitInstallManager,
    ) {
        installManager
            .requestProgressFlow()
            .filter { it.sessionId() == sessionId }
            .onEach { log("Session status for id $sessionId updated to $it") }
            .takeWhile { !it.hasTerminalStatus }
            .catch { log("Error while waitingForCompletion $it") }
            .onCompletion { log("Terminating waitingForCompletion for session: $sessionId") }
            .collect()
    }

    private suspend fun requestCoresInstall(
        installManager: SplitInstallManager,
        coreIDs: List<CoreID>,
    ): Int? {
        val modulesToInstall =
            coreIDs
                .map { computePlayModuleName(it) }
                .filter { it !in installManager.installedModules }

        if (modulesToInstall.isEmpty()) {
            return null
        }

        log("Starting request install for the following modules: $modulesToInstall")

        val result =
            retry(RETRY_ATTEMPTS, RETRY_DELAY) {
                installManager.requestInstall(modulesToInstall)
            }

        return result.getOrNull()
    }

    private suspend fun installAssets(
        context: Context,
        coreIDs: List<CoreID>,
    ) {
        val sharedPreferences = SharedPreferencesHelper.getSharedPreferences(context.applicationContext)
        coreIDs
            .asFlow()
            .map { CoreID.getAssetManager(it) }
            .onEach { it.retrieveAssetsIfNeeded(api, directoriesManager, sharedPreferences) }
            .collect()
    }

    private suspend fun cancelPendingInstalls(
        installManager: SplitInstallManager,
        currentSession: Int?,
    ) {
        log("Starting cancelPendingInstalls")

        val pending =
            retry(RETRY_ATTEMPTS, RETRY_DELAY) { installManager.requestSessionStates() }
                .getOrNull() ?: return

        pending
            .asFlow()
            .filter { !it.hasTerminalStatus && it.sessionId != currentSession }
            .onEach { cancelPendingInstall(installManager, it.sessionId) }
            .catch { log("Failed to cancel pending installs: ${it.message}") }
            .collect()

        log("Terminating cancelPendingInstalls")
    }

    private suspend fun cancelPendingInstall(
        installManager: SplitInstallManager,
        sessionId: Int,
    ) {
        try {
            installManager.requestCancelInstall(sessionId)
        } catch (e: Throwable) {
            log("Failed to cancel pending install for session: $sessionId")
        }
        log("Terminated cancel for session: $sessionId")
    }

    private fun log(message: String) {
        if (VERBOSE) {
            Log.i(TAG_LOG, message)
        }
    }

    companion object {
        // Sadly dynamic features need to be tested directly on GooglePlay. Let's leave logging on.
        private const val TAG_LOG = "CoreUpdaterImpl"
        private const val VERBOSE = true
        private const val RETRY_ATTEMPTS = 5
        private val RETRY_DELAY = 2.seconds
    }
}
