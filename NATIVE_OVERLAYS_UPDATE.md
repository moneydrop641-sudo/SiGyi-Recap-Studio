# Native overlay update — SiGyi Recap Studio

## Added to Android native render
- Subtitle-only blur mask: enable/disable plus X/Y, width, height and strength controls. No full-frame top/bottom blur bands are used.
- Logo image picker through Android Storage Access Framework. Logo is copied to app-private storage and rendered at the top-right; size is adjustable.
- Custom title text rendered into every chunk with editable X/Y position, font size and color. The title is written as UTF-8 text to a temporary file, not interpolated into the FFmpeg command.
- Native preview overlays reflect the current title, logo and blur rectangle settings.

## Implementation notes
- The Android plugin uses FFmpeg `filter_complex` to blur only the selected rectangle, then composites the logo and title.
- Logo and title are composited independently on every 5-minute chunk before concat/merge.
- The blur rectangle coordinates and dimensions are percentages of the output frame and are clamped to keep the crop inside the frame.
- Burmese title rendering depends on the Android image containing `/system/fonts/NotoSansMyanmar-Regular.ttf` and on the FFmpeg build having `drawtext` enabled. This must be confirmed on a physical device; if unavailable, bundle a licensed Myanmar font in the app and use its copied path.

## Copyright note
These editing controls do not guarantee YouTube fair use or prevent Content ID claims. Translation, logo, blur, zoom, crop or title overlays alone do not establish permission or fair use. Use licensed/authorized source material where possible and add substantial original commentary, criticism, analysis or other genuinely transformative material. YouTube may still issue claims or takedowns.
