package com.smitnk.sketcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.smitnk.sketcher.ui.AppNavigation
import com.smitnk.sketcher.ui.theme.SketcherTheme
import com.smitnk.sketcher.ui.SketchViewModel

class MainActivity : ComponentActivity() {
    private val vm: SketchViewModel by viewModels()
    private var editorRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startInEditor = wantsEditor(intent)
        setContent {
            SketcherTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavigation(startInEditor = startInEditor, editorRequest = editorRequest, vm = vm)
                }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
        if (wantsEditor(intent)) editorRequest++
    }

    private fun wantsEditor(i: Intent?): Boolean = i != null && (
        i.getBooleanExtra("autodemo", false) || i.getBooleanExtra("showlayers", false) ||
            i.getBooleanExtra("autoexport", false) || i.getBooleanExtra("autovideo", false) ||
            i.getStringExtra("demo") != null
        )

    private fun handle(intent: Intent?) {
        if (intent == null) return
        vm.handleIntentFlags(
            autodemo = intent.getBooleanExtra("autodemo", false),
            showLayers = intent.getBooleanExtra("showlayers", false),
            autoExport = intent.getBooleanExtra("autoexport", false),
            autoVideo = intent.getBooleanExtra("autovideo", false),
            demo = intent.getStringExtra("demo"),
            mode = intent.getStringExtra("mode"),
            engine = intent.getStringExtra("engine"),
            shading = intent.getStringExtra("shading"),
            testImage = intent.getStringExtra("testimage"),
        )
    }
}
