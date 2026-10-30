import { cp, mkdir, access } from 'node:fs/promises';
import { dirname, join } from 'node:path';
const root = process.cwd();
const target = join(root, 'android/app/src/main/java/com/sigyi/recapstudio');
try { await access(join(root, 'android')); }
catch { console.error('Android project missing. Run npm run android:add first.'); process.exit(1); }
await mkdir(target, { recursive: true });
for (const file of ['MainActivity.java', 'GeminiDownloadBridgePlugin.java']) {
  await cp(join(root, 'android-native/app/src/main/java/com/sigyi/recapstudio', file), join(target, file));
}
console.log('Native Gemini download bridge copied into android project.');
