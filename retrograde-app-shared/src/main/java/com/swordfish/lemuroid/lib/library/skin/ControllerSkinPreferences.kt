package com.swordfish.lemuroid.lib.library.skin

import android.content.SharedPreferences
import androidx.core.content.edit
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.Orientation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Persists the controller skin chosen for each (system, orientation) slot. A missing value means the
 * default (PadKit) controls are used. Backed by the multi-process shared preferences so a selection
 * made in the main process is visible in the emulator process.
 */
class ControllerSkinPreferences(
    private val sharedPreferences: SharedPreferences,
) {
    companion object {
        /**
         * Opacity (as a percentage) of the artwork of skins that declare themselves translucent. Shared
         * with the settings slider, which writes it through the same preferences.
         */
        const val KEY_OPACITY = "controller_skin_opacity"
        const val DEFAULT_OPACITY = 100

        private const val KEY_PREFIX = "controller_skin_"
    }

    fun getSelectedSkinId(
        systemID: SystemID,
        orientation: Orientation,
    ): String? = sharedPreferences.getString(key(systemID, orientation), null)

    fun setSelectedSkinId(
        systemID: SystemID,
        orientation: Orientation,
        skinId: String?,
    ) {
        sharedPreferences.edit {
            if (skinId == null) {
                remove(key(systemID, orientation))
            } else {
                putString(key(systemID, orientation), skinId)
            }
        }
    }

    /** Artwork opacity of translucent skins, as a percentage. Applies to every system. */
    fun getOpacity(): Int = sharedPreferences.getInt(KEY_OPACITY, DEFAULT_OPACITY)

    fun observeOpacity(): Flow<Int> =
        callbackFlow {
            trySend(getOpacity())
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { prefs, changedKey ->
                    if (changedKey == KEY_OPACITY) {
                        trySend(prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY))
                    }
                }
            sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate()

    fun observeSelectedSkinId(
        systemID: SystemID,
        orientation: Orientation,
    ): Flow<String?> =
        callbackFlow {
            val prefKey = key(systemID, orientation)
            trySend(sharedPreferences.getString(prefKey, null))
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { prefs, changedKey ->
                    if (changedKey == prefKey) {
                        trySend(prefs.getString(prefKey, null))
                    }
                }
            sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    /**
     * Emits every time any (system, orientation) selection changes, so screens listing selections can
     * reload while they are in the background instead of showing stale content when they come back.
     * Does not emit an initial value.
     */
    fun observeSelectionChanges(): Flow<Unit> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                    if (changedKey?.startsWith(KEY_PREFIX) == true && changedKey != KEY_OPACITY) {
                        trySend(Unit)
                    }
                }
            sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate()

    private fun key(
        systemID: SystemID,
        orientation: Orientation,
    ): String {
        // Systems that share a skin slot (e.g. GB and GBC) resolve to the same canonical key.
        val canonical = DeltaSkinSystemMapping.canonicalSkinSystem(systemID)
        return "$KEY_PREFIX${canonical.dbname}_${orientation.ordinal}"
    }
}
