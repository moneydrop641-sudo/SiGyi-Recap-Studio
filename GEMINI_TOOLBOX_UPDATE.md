# Gemini Toolbox UI update

- Replaced the plain Gemini URL panel in the first workflow stage with a branded Si Gyi Gemini Workspace.
- Added 14 shortcut cards from the supplied Gemini share links.
- Selecting a shortcut loads its share URL in the embedded WebView area and exposes a browser fallback link.
- Added custom Gemini URL input and WAV/SRT manual import controls.
- Kept the existing native download bridge event listeners and WAV/SRT import handlers intact.

## Important compatibility note
Some Gemini share pages may send anti-embedding headers or require Google sign-in/cookies that an iframe cannot provide. In that case use the "Browser မှဖွင့်" fallback. Full in-app login and automatic downloads must be verified in the Android APK; this source UI change alone does not prove that native download interception works on every Gemini page.
