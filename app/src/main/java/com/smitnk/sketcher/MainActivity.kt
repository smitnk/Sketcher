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
        // An automation intent must not wipe a project being edited (unless it carries a debug label).
        if (wantsEditor(intent) && !vm.acceptsAutomation(intent.getStringExtra("label"))) return
        handle(intent)
        if (wantsEditor(intent)) editorRequest++
    }

    private fun wantsEditor(i: Intent?): Boolean = i != null && (
        i.getBooleanExtra("autodemo", false) || i.getBooleanExtra("showlayers", false) ||
            i.getBooleanExtra("autoexport", false) || i.getBooleanExtra("autovideo", false) || i.getBooleanExtra("autosvg", false) ||
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
            cartoonModel = intent.getStringExtra("cartoonModel"),
            autoSvg = intent.getBooleanExtra("autosvg", false),
            fill = intent.getStringExtra("fill"),
            shading = intent.getStringExtra("shading"),
            testImage = intent.getStringExtra("testimage"),
            model = intent.getStringExtra("model"),
            accel = intent.getStringExtra("accel"),
            outline = intent.getStringExtra("outline"),
            label = intent.getStringExtra("label"),
            look = intent.getStringExtra("look"),
            pen = if (intent.hasExtra("pen")) intent.getBooleanExtra("pen", false) else null,
            camera = if (intent.hasExtra("camera")) intent.getBooleanExtra("camera", false) else null,
        )
    }
}
