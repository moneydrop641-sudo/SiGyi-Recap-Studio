import { defineConfig } from 'vite';

function renderLogMiddleware(req, res, next) {
  if (req.method !== 'POST') return next();
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', () => {
    let data = {};
    try { data = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); } catch { data = { error: 'Could not parse browser diagnostic JSON' }; }
    if (data.type === 'progress') {
      console.log(`[SIGYI FFMPEG-WASM] ${data.progress ?? '?'}% | ${data.stage || 'render'} | ${data.resolution || ''} | ${data.time || ''}`);
    } else {
      console.error('\n========== SIGYI BROWSER RENDER ERROR ==========');
      console.error(JSON.stringify(data, null, 2));
      console.error('========== END SIGYI RENDER ERROR ==============\n');
    }
    res.statusCode = 204;
    res.end();
  });
}

// dev and preview expose only the local render diagnostic endpoint.
const ttsPlugin = {
  name: 'sigyi-render-log-api',
  configureServer(server) { server.middlewares.use('/api/render-log', renderLogMiddleware); },
  configurePreviewServer(server) { server.middlewares.use('/api/render-log', renderLogMiddleware); }
};

export default defineConfig({
  plugins: [ttsPlugin],
  // ffmpeg.wasm single-thread core needs NO SharedArrayBuffer, so no COOP/COEP headers
  optimizeDeps: { exclude: ['@ffmpeg/ffmpeg', '@ffmpeg/util', '@ffmpeg/core'] },
  worker: { format: 'es' }
});
