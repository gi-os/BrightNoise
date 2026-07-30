package com.gios.lightnoise

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnoise.hw.LightKey
import com.gios.lightnoise.hw.LightKeys
import com.gios.lightnoise.hw.LocalWheelBus
import com.gios.lightnoise.hw.WheelBus
import com.gios.lightnoise.service.NoiseController
import com.gios.lightnoise.ui.MixScreen
import com.gios.lightnoise.ui.SoundsScreen
import com.gios.lightnoise.ui.TabBar
import com.gios.lightnoise.ui.TimerScreen
import com.gios.lightnoise.ui.TransportBar
import com.gios.lightnoise.ui.theme.LightNoiseTheme

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever tab is up. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — `DecorView` hands the event to the window
     * callback before it walks the view hierarchy — so a notch reaches the tab that is
     * showing whatever happens to hold focus.
     *
     * Only the turns. The wheel click and the camera button belong to LightControl, which
     * owns them phone-wide and passes bare turns through on purpose.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    // Only the shade notification needs a grant, and playback works fine without it.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NoiseController.attach(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LightNoiseTheme {
                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val state by NoiseController.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = remember { listOf("SOUNDS", "MIX", "TIMER") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxSize()) {
            when (tab) {
                0 -> SoundsScreen(state)
                1 -> MixScreen(state)
                else -> TimerScreen(state)
            }
        }
        TransportBar(state)
        TabBar(selected = tab, labels = labels) { tab = it }
    }
}
