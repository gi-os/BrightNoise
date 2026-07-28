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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Either way we carry on: the synthesised sounds need no permission at all,
            // and a refused storage grant only means the loop list stays empty.
            NoiseController.refreshLoops()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NoiseController.attach(this)
        askForWhatWeNeed()

        setContent {
            LightNoiseTheme {
                Root()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Files may have been copied over by USB since the app was last opened.
        NoiseController.refreshLoops()
    }

    private fun askForWhatWeNeed() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.READ_MEDIA_AUDIO
            wanted += Manifest.permission.POST_NOTIFICATIONS
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) NoiseController.refreshLoops()
        else requestPermissions.launch(missing.toTypedArray())
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
