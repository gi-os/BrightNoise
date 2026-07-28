package com.gios.lightnoise

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnoise.service.NoiseController
import com.gios.lightnoise.ui.MixScreen
import com.gios.lightnoise.ui.SoundsScreen
import com.gios.lightnoise.ui.TabBar
import com.gios.lightnoise.ui.TimerScreen
import com.gios.lightnoise.ui.TransportBar
import com.gios.lightnoise.ui.theme.LightNoiseTheme

class MainActivity : ComponentActivity() {

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
                Root()
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
