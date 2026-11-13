# Sketcher Design System: "Artist's Sketchbook"

Sources: ui-ux-pro-max search (style: Minimalism/Swiss, type mood: hand-drawn "Kalam / Patrick Hand", palette hint: canvas + tool accent on dark) and android-ux (M3 audit). The generated purple palette was rejected as generic. Direction below keeps its minimal, high-contrast structure but swaps to paper, ink and one vermilion accent.

## 1. Direction

- Concept: the app is a sketchbook. The artwork is the hero; chrome is quiet, floating, and collapses away.
- Light = "Paper mode" (warm cream paper, ink-black text, vermilion red-pencil accent).
- Dark = "Studio mode" (warm near-black, chalk text, softened vermilion). Default follows system; Settings may override.
- One accent only (vermilion) for primary actions and the active tool. Blue graphite (secondary) is used only for the Shading layer; ochre (tertiary) only for the Colour-fill layer. This colour-codes layers consistently across thumbnails, cards, and the layers panel.
- Never pure white or pure black as UI surfaces (the artboard paper may be white-ish `#FFFDF8`).
- Rule: no gradients, no glass blur. Flat fills, 1dp pencil-grey outlines, tonal elevation.

## 2. Colour tokens

| Token | Light (Paper) | Dark (Studio) | Use |
|---|---|---|---|
| background / surface | #F6F1E7 | #14130F | App background |
| surfaceContainerLowest | #FFFDF8 | #0E0D0A | Cards on paper, artboard |
| surfaceContainerLow | #FAF6EC | #1C1A16 | Project cards |
| surfaceContainer | #F1EBDD | #26231E | Sheets, drawers |
| surfaceContainerHigh | #EAE3D2 | #312E28 | Chips, tool rail fill |
| surfaceContainerHighest | #E2DAC7 | #3C3832 | Pressed, scrubber track |
| onSurface (ink / chalk) | #1B1A17 | #EDE7DA | Text, icons |
| onSurfaceVariant | #5B574E | #BDB5A5 | Secondary text |
| primary (vermilion) | #C23B22 | #FF8A6B | CTA, active tool, scrubber thumb |
| onPrimary | #FFFFFF | #3A0D03 | Text on primary |
| primaryContainer | #FFDAD0 | #7A2614 | Selected card/chip fill |
| secondary (graphite blue) | #3E6A8F | #9CC4EA | Shading layer |
| tertiary (ochre) | #8A6A12 | #E6C35C | Colour-fill layer |
| outline | #8A8478 | #8C8576 | Control borders (>=3:1) |
| outlineVariant | #D5CDBB | #3E3A33 | Dividers |
| error | #B3261E | #F2B8B5 | Errors, delete |

Extra (`SketcherTokens.colors`): `paper`, `ink`, `canvasBackdrop` (light #DDD5C2 / dark #0B0A08), `floatingSurface` (95% opaque container for floating bars), `checkerA/B`, `shadingTint`, `fillTint`, `success`, `scrim`.

Contrast (WCAG, computed): ink on paper ~15:1; onSurfaceVariant on paper ~6:1; white on vermilion ~5.3:1; vermilion on paper ~4.7:1; dark primary on Studio ~8:1; chalk on Studio ~15:1; outline on paper ~3.3:1 (UI components >= 3:1). Never convey layer type by colour alone: always pair with an icon and label.

## 3. Typography (system fonts, no bundling)

- Display/headline: `FontFamily.Serif` (Noto Serif on Android), bold, tight tracking; gives the sketchbook-cover character. headlineLarge is italic for screen titles ("Your sketchbook").
- Title/body/label: `FontFamily.SansSerif`, with distinctive weights (Bold labels, SemiBold titles, Regular body).
- Optional later upgrade: bundle Kalam (Google Font, hand-drawn) in `res/font` for display only. Not downloaded now; swap `SketchDisplay` in Type.kt.
- Scale: display 52/40/32, headline 30i/26/22, title 20/16/14, body 16/14/12, label 14/12/11 sp. Body line-height >= 1.4. Min text 11sp, only for labels under icons; body never below 12sp. Respect system font scaling; rail labels hide at >1.3x rather than clip.

## 4. Spacing, shape, elevation

- Spacing: 4dp base; tokens 2/4/8/12/16/20/24/32/48. Screen margin 16dp. Grid 8dp.
- Shapes: xs 6 (chips, scrubber badge), s 10 (thumbnails, buttons), m 14 (cards), l 20 (sheets top corners, floating bars use full pill), xl 28 (FAB, tool rail).
- Elevation: tonal by default. Shadow only for floating chrome (6dp) over the canvas so it reads against any artwork. Sheets 3dp tonal.
- Max content width 840dp on tablets; the editor canvas stays full-bleed.

## 5. Iconography

- Material Symbols Rounded, Outlined at 24dp, 2dp stroke feel (weight 400), filled variant only for the active/selected state.
- Icons never stand alone for non-obvious actions: tool rail shows icon + 11sp label on long-press tooltip; top bar icons have contentDescription; sheets use text labels.
- Layer-type glyphs: outline = pen nib, shading = hatch lines, fill = droplet. Consistent everywhere.
- No emoji as icons.

## 6. Motion

| Interaction | Duration | Easing |
|---|---|---|
| Press/selection feedback | 120ms | FastOutSlowIn |
| Tool options expand/collapse | 240ms | EmphasizedDecelerate in / Accelerate out (exit ~70% of enter) |
| Sheet / drawer enter | 300ms | EmphasizedDecelerate |
| Sheet / drawer exit | 200ms | EmphasizedAccelerate |
| Style card selection (scale 0.98 -> 1, border morph) | 160ms | FastOutSlowIn |
| Auto-draw reveal (the "sketching" animation) | user-controlled speed, 0.25x-4x | Linear |
| Floating chrome auto-hide while drawing | 200ms fade | Linear |

Rules: animate transform/alpha only; all interruptible; respect `ANIMATOR_DURATION_SCALE == 0` (use instant state changes, no auto-draw playback flourish; scrubber still works).

## 7. Accessibility

- All targets >= 48x48dp (visual may be 40dp with padded hit area), >= 8dp between adjacent targets.
- Text contrast >= 4.5:1, UI components >= 3:1 (values above).
- Every icon button has contentDescription; layer rows merge semantics (`mergeDescendants`) and announce "Layer: Outline, visible, selected"; section titles use `heading()`.
- Sliders expose stateDescription with units ("Detail 60 percent"); provide +/- step buttons in the options strip for fine control.
- Canvas gestures (pinch, two-finger pan) have button alternatives (zoom fit, 1:1) in the overflow.
- TalkBack: drawing itself is visual; ensure all project/import/export actions are fully operable.

## 8. Components and wireframes (412dp wide; each char ~ 4dp)

### 8.1 Home

Surface: background (paper). Header serif headline. Three action tiles (Import photo = primary filled vermilion, Pose render = outlined, Demo = outlined/tonal). Projects grid 2 columns, 16dp gutters, cards `surfaceContainerLowest`, radius 14, thumbnail 4:5 on paper with 1dp outlineVariant, title labelLarge, date bodySmall. Empty state: dotted-outline artboard with "Start with a photo".

```
+--------------------------------------------------+ 412
| 16                                           [gear]|  settings 48dp
|  Sketcher                                          |  headlineLarge italic
|  Turn any photo into a drawing                     |  bodyMedium variant
|                                                    |
|  +----------------------------------------------+  |
|  | [image]  Import photo                        |  |  primary, h=72, r=20
|  |          From gallery or camera              |  |
|  +----------------------------------------------+  |
|  +----------------------+ +---------------------+  |
|  | [figure] Pose render | | [play]  Try demo    |  |  outlined, h=64
|  +----------------------+ +---------------------+  |
|                                                    |
|  Recent                                  See all   |  titleMedium
|  +---------------------+ +---------------------+   |
|  |                     | |                     |   |
|  |    (thumbnail)      | |    (thumbnail)      |   |  4:5
|  |                     | |                     |   |
|  | Cat study           | | Hand pose 3         |   |
|  | 2 layers - Today    | | 3 layers - Mon      |   |
|  +---------------------+ +---------------------+   |
|  +---------------------+ +---------------------+   |
|  |        ...          | |        ...          |   |
+--------------------------------------------------+
```

### 8.2 Style picker (modal bottom sheet)

Opens after import. Sheet `surfaceContainer`, top radius 20, drag handle. Three selectable cards in a vertical list (or horizontal pager of 3 on wide). Each: 88dp mini preview thumbnail (real preview of the image rendered in that style; fall back to illustrative placeholder), title, one-line description. Selected = primaryContainer fill + 2dp primary border + check. Below: "Detail" slider (1-100, labelled "Fewer lines / More lines"), "AI line art" switch row with helper text "Cleaner, smoother lines. Slower on this phone." CTA "Start drawing" (full-width, primary, 56dp).

```
+--------------------------------------------------+
|                      ----                          |  handle
|  Choose a style                                    |  headlineSmall
|  +----------------------------------------------+  |
|  | +------+  Outline only                    (o)  |  |  selected
|  | |thumb |  Clean ink lines                      |  |
|  | +------+                                       |  |
|  +----------------------------------------------+  |
|  | +------+  Outline + Shading               ( )  |  |
|  | |thumb |  Lines plus hatched shadows           |  |
|  | +------+                                       |  |
|  +----------------------------------------------+  |
|  | +------+  Colour fill                     ( )  |  |
|  | |thumb |  Lines, shadows, flat colour          |  |
|  | +------+                                       |  |
|  +----------------------------------------------+  |
|  Detail                                      60%   |
|  Fewer lines  [-]-----o----------[+]  More lines   |
|  AI line art                               [ == o] |
|  Cleaner, smoother lines. Slower.                  |
|  [          Start drawing          ]               |  56dp
+--------------------------------------------------+
```

### 8.3 Editor

Canvas is full-bleed over `canvasBackdrop`, artboard centred. Chrome floats and auto-fades (200ms) during strokes; tap canvas edge or two-finger tap to restore.

- Top bar: two floating pills, margin 12dp, h=48: left pill [back] [undo] [redo]; right pill [layers] [play] [export]. `floatingSurface`, shadow 6dp. Project title shown only in overflow to save space.
- Tool rail: vertical, left edge, 56dp wide, radius 28, vertically centred, 6 tools max (Brush, Eraser, Shade, Fill, Move, Eyedropper), each 48dp; active = primary fill + onPrimary icon. Left-handed option mirrors to right. In landscape the rail stays; in portrait optional bottom dock alternative (same tools horizontally, 56dp tall) chosen by Settings.
- Tool options: a floating strip above the playback bar, collapsed to a 32dp-tall chip (tool icon + size dot + colour swatch); tap expands (240ms) to size / opacity sliders, colour, brush type. Collapses on canvas touch.
- Playback bar: bottom floating, see 8.5.

```
+--------------------------------------------------+
|  (<)(undo)(redo)              (layers)(play)(exp) |  floating pills y=12
|                                                    |
| +--+                                               |
| |B |                                               |
| |E |        (full-bleed canvas / artboard)         |
| |S |                                               |
| |F |                                               |
| |M |                                               |
| |P |                                               |
| +--+                                               |
|                                                    |
|        +------------------------------+            |
|        | (brush) 12px (o) Opacity 80% |  v         |  options (expanded)
|        +------------------------------+            |
|  +--------------------------------------------+    |
|  | [>] 00:03/00:08 ---o------------ 1x        |    |  playback
|  +--------------------------------------------+    |
+--------------------------------------------------+
```

### 8.4 Layers panel (side drawer, right)

Width 280dp (max 85% of screen), `surfaceContainer`, left corners radius 20, slides over the canvas with scrim 40%. Rows h=64: drag handle, 48dp thumbnail on checkerboard (r=10, 1dp outlineVariant, tinted by type colour: ink / graphite blue / ochre), name + type label, eye toggle, lock toggle (each 48dp hit area). Selected row = primaryContainer. Reorder by long-press-drag. Footer: opacity slider for selected layer and [+ Layer] button. Auto-generated layers (Outline, Shading, Fill) can be hidden but show a small "auto" tag.

```
                         +------------------------+
                         | Layers            [x]  |
                         |------------------------|
                         | = [thumb] Colour fill  |
                         |           fill   (e)(l)|
                         | = [thumb] Shading      |
                         |           shading(e)(l)|
                         | = [thumb] Outline  *   |   selected
                         |           ink    (e)(l)|
                         | = [thumb] Paper        |
                         |           bg     (e)   |
                         |------------------------|
                         | Opacity  ----o---- 100%|
                         | [ + New layer ]        |
                         +------------------------+
```

### 8.5 Playback bar

Floating pill, h=56, `floatingSurface`, radius 28, 12dp side margin. Play/pause (48dp, primary tonal), time label (labelMedium, tabular), scrubber (track 4dp surfaceContainerHighest, active primary, thumb 20dp with 48dp touch area; shows frame tooltip while dragging), speed chip cycling 0.5x / 1x / 2x on tap (long-press opens full 0.25x-4x list). Loop toggle in overflow. Scrubber exposes seek via accessibility actions.

```
+--------------------------------------------------+
|  ( > )  0:03 / 0:08  ----o-----------  [ 1x ]     |
+--------------------------------------------------+
```

### 8.6 Export sheet (modal bottom sheet)

Segmented control (PNG / GIF / MP4). Per format options: PNG = current frame or final drawing, transparent background switch, scale (1x/2x). GIF = fps (12/24), width. MP4 = resolution (720p/1080p), fps 24/30, include paper background. A live size estimate under options. CTA "Export" then progress with determinate bar and Cancel; finish shows Share and Save to Gallery buttons. Note: exported MP4/GIF default to paper background; transparent only for PNG, with helper text.

```
+--------------------------------------------------+
|                      ----                          |
|  Export                                            |
|  [ PNG ] [ GIF ] [ MP4 ]                           |  segmented, 48dp
|  Resolution          [ 720p ][ 1080p ]             |
|  Frame rate          [ 24 ][ 30 ]                  |
|  Background          Paper            [ == o]      |
|  Speed               1x                            |
|  About 4.2 MB - 8 s                                |
|  [        Export MP4        ]                      |  56dp
+--------------------------------------------------+
```

## 9. Do and do not

- Do keep one primary (vermilion) action per screen. Do keep sheets <= 3 groups.
- Do not introduce new accent hues; do not use raw `Color(0x...)` in screens (use `MaterialTheme.colorScheme` or `SketcherTokens.colors`); do not hard-code `RoundedCornerShape` or font sizes.
- Test light, dark, 1.3x font scale, and TalkBack before shipping each screen.
