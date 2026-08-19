package com.swordfish.lemuroid.app.mobile.feature.game

import android.os.Build
import androidx.compose.runtime.Composable
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.BACKGROUND_BLUR_MIN_SDK
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.BACKGROUND_CAPTURE_SIZE
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.BACKGROUND_CAPTURE_TIMEOUT_MS
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuBackground
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import com.swordfish.lemuroid.common.graphics.captureFrame
import com.swordfish.libretrodroid.GLRetroView
import kotlinx.coroutines.withTimeoutOrNull

class GameActivity : BaseGameActivity() {
    @Composable
    override fun GameScreen(viewModel: BaseGameScreenViewModel) {
        MobileGameScreen(viewModel)
    }

    override fun getDialogClass() = GameMenuActivity::class.java

    /**
     * The menu blurs the game behind it, which means capturing it here: this window is the last
     * point at which the game is on screen on its own, and the menu cannot reach a SurfaceView
     * belonging to another activity.
     *
     * Nothing is captured on a platform that cannot blur it, and the wait is bounded because the
     * menu opening is worth more than the blur is. Either way the menu dims the game instead.
     */
    override suspend fun prepareGameMenu(gameView: GLRetroView?) {
        if (Build.VERSION.SDK_INT < BACKGROUND_BLUR_MIN_SDK) return

        val frame =
            withTimeoutOrNull(BACKGROUND_CAPTURE_TIMEOUT_MS) {
                window.captureFrame(BACKGROUND_CAPTURE_SIZE, gameView)
            }
        GameMenuBackground.set(frame)
    }

    override fun onGameMenuClosed() {
        GameMenuBackground.clear()
    }

    /**
     * The menu draws its own entrance: the sheet slides up and its scrim fades. A window fade on
     * top of that would cross fade the sheet while it slides, so this window stays still.
     */
    override fun applyGameMenuOpenTransition() {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
