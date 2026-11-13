package com.smitnk.sketcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.smitnk.sketcher.ui.SketchScreen
import com.smitnk.sketcher.ui.SketchViewModel

class MainActivity : ComponentActivity() {
    private val vm: SketchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SketchScreen()
                }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        vm.handleIntentFlags(
            autodemo = intent.getBooleanExtra("autodemo", false),
            showLayers = intent.getBooleanExtra("showlayers", false),
            autoExport = intent.getBooleanExtra("autoexport", false),
        )
    }
}
