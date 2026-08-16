package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long a load is given to finish before it is worth telling the user it is happening. */
private const val SPINNER_DELAY_MS = 100L

private val SPINNER_SIZE = 32.dp

/**
 * A loading indicator that only appears once the wait becomes noticeable. Most of the loads it
 * covers finish within a few frames, and a spinner that flashes on and straight back off reads as
 * a glitch rather than as progress.
 *
 * The caller supplies the size, so the space the loaded content will take can be held in the
 * meantime instead of collapsing and springing back.
 */
@Composable
fun LemuroidDelayedLoading(modifier: Modifier = Modifier) {
    var spinnerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MS)
        spinnerVisible = true
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = spinnerVisible, enter = fadeIn()) {
            CircularProgressIndicator(modifier = Modifier.size(SPINNER_SIZE))
        }
    }
}
