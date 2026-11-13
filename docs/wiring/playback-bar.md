# PlaybackBar wiring (EditorScreen)
New defaulted params: playing, onTogglePlay, progress, onSeek, onSeekEnd, modifier. AnimationController gained `seek(fraction)` (pauses, sets active + progress). `progress` already existed.

```kotlin
PlaybackBar(
    durationSec = anim.durationSec,
    onDurationChange = { anim.durationSec = it },
    onRestart = { anim.restart() },
    onShowFinished = { anim.showFinished() },
    playing = anim.playing,
    onTogglePlay = { anim.togglePlay() },
    progress = anim.progress,
    onSeek = { anim.seek(it) },
)
```
The bar is its own floating pill (Surface); drop the surrounding `Surface(tonalElevation = 3.dp)` or make it transparent so the pill floats. Canvas already renders from anim.allowances(), so scrubbing shows that progress.
