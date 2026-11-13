# ExportSheet wiring (optional, all params have defaults; current EditorScreen still compiles)

ExportDialog (now a ModalBottomSheet), new optional params:
- durationSec: Float? - draw-along length for video/GIF readout (e.g. vm.anim duration).
- resolutionNote: String? - e.g. "1080 x 1350 px".
- onExportLayersSeparately: ((scale, format, transparent) -> Unit)? - enables "Each layer"; needs a new VM export that writes one file per layer. Disabled when null.

ExportProgressDialog new optional params: done, savedPath, savedUri, savedMime, onDone.
- Keep showing it while exporting; when export finishes, pass done=true with savedPath (e.g. "Pictures/Sketcher/xxx.mp4") and the MediaStore Uri (VM must expose last saved Uri + mime). Share button appears only when savedUri != null. onDone should clear the VM state that shows the dialog.
- Without wiring, behaviour is unchanged (progress only).
