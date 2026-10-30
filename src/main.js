import { registerPlugin } from '@capacitor/core';
import './styles.css';
import { renderRecap, renderOfflineVideo, terminateFfmpeg, getRenderLogs } from './lib/render.js';

const app = document.querySelector('#app');

const steps = [
  { id: 0, en: 'Upload video', mm: 'ဗီဒီယိုတင်ရန်' },
  { id: 3, en: 'Video style', mm: 'ပုံစံပြင်ရန်' },
  { id: 4, en: 'Render', mm: 'ဗီဒီယိုထုတ်ရန်' },
  { id: 5, en: 'Offline Editor', mm: 'အင်တာနက်မလို Video Editor' }
];


const geminiTools = [
  ['video-srt', 'Video to SRT (5 Mins)', 'စာတန်းထိုး SRT ထုတ်ရန်', '65e1d03f0f69?skid=bba43807-569d-4423-b0df-afb7c21d846f'],
  ['script-mp3', 'Script To MP3', 'Script မှ အသံဖိုင်ထုတ်ရန်', '5f411380fc6f?skid=3f72c4bf-a12b-4c0a-9b2c-0f787d130d5a'],
  ['translate-video', 'ဘာသာပြန် (SRT / Video)', 'Video နှင့် SRT ဘာသာပြန်ရန်', '214cc6582fce?skid=ab66ce8b-eaee-4639-be9b-f7c00c2ee72a'],
  ['ai-studio', 'Si Gyi AI Studio', 'Movie Recap Content / Audio', '52b8a4763185?skid=c9dc3f5b-e7a7-4091-9bcf-0d726bdc2485'],
  ['translate-audio', 'ဘာသာပြန် + Audio', 'ဘာသာပြန်ပြီး အသံထုတ်ရန်', '995ff3d1f570?skid=1a492b3e-3732-4e02-8fa0-a956c86d4ac1'],
  ['audio-17', 'Audio 17 Types', 'အသံပုံစံ ၁၇ မျိုး', 'b23ca4a067d9?skid=f06affe0-1fcf-49c8-a54d-4a9ab7bf1e66'],
  ['audio-natural', 'Audio Natural Speed / Emotional', 'သဘာဝအမြန်နှုန်းနှင့် ခံစားချက်ပါသောအသံ', '5f411380fc6f?skid=3f72c4bf-a12b-4c0a-9b2c-0f787d130d5a'],
  ['image-prompt', 'Image To Prompt Generator', 'ပုံမှ Prompt ထုတ်ရန်', 'cbe99207798d?skid=5251af36-c9f7-4fc8-bd2a-3feb15249028'],
  ['typed-update', '10 Typed Update', 'စာသားပုံစံ Update ကိရိယာ', 'b23598a0da79?skid=7db1287f-d6db-4906-8975-fc37bf3ba0cc'],
  ['video-prompt', 'Video Prompt Generator', 'Video Prompt ဖန်တီးရန်', '56beaaa19853?skid=dc00430c-d6af-4604-ae67-f9c6322f51fb'],
  ['thumbnail', 'Thumbnail Generator', 'Thumbnail ဖန်တီးရန်', '7ecc3adebc25?skid=b32215e9-6e07-42e7-89cc-38206cc1a4d8'],
  ['mp3-srt', 'MP3 + SRT File (SRT Translator)', 'MP3 နှင့် SRT ဘာသာပြန်ရန်', '4c903bd497f6?skid=cdc4f943-42aa-459b-804f-ca5c4e6b2154'],
  ['srt-txt', 'SRT TO TXT Translator', 'SRT ကို TXT ဘာသာပြန်ရန်', 'b43e8b15a5b2?skid=fb787c59-bcbc-4e26-b47e-d9a1f70ecd80'],
  ['srt-srt', 'SRT TO SRT TRANSLATOR', 'SRT ကို SRT အဖြစ် ဘာသာပြန်ရန်', 'fdcecf62b5cc?skid=05138052-77d6-48d0-88bc-973e9e7d33ee']
].map(([id, title, desc, path]) => ({ id, title, desc, url: `https://gemini.google.com/share/${path}` }));

const sampleSegments = [
  { start: 0, end: 4.8, original: 'In the middle of the city stands a door nobody has opened for years.', text: 'မြို့ရဲ့အလယ်မှာ နှစ်ပေါင်းများစွာ မဖွင့်ဖြစ်သေးတဲ့ တံခါးတစ်ချပ် ရှိနေပါတယ်။' },
  { start: 4.8, end: 10.2, original: 'Behind that door hides a secret that not everyone knows.', text: 'အဲဒီတံခါးရဲ့နောက်ကွယ်မှာတော့ လူတိုင်းမသိတဲ့ လျှို့ဝှက်ချက်တစ်ခု ရှိနေခဲ့တယ်။' },
  { start: 10.2, end: 16.7, original: 'He went in alone to find the truth.', text: 'သူက အမှန်တရားကို ရှာဖို့ တစ်ယောက်တည်း ဝင်သွားလိုက်ပါတယ်။' }
];

const sampleRecap = 'မြို့အလယ်က ပိတ်ထားတဲ့ တံခါးဟောင်းတစ်ချပ်နောက်မှာ လူတိုင်းမသိတဲ့ လျှို့ဝှက်ချက်တစ်ခု ရှိနေပါတယ်။ အမှန်တရားကို သိချင်တဲ့သူက အရဲစွန့်ပြီး အဲဒီတံခါးကို ဖွင့်လိုက်တဲ့အချိန်မှာတော့ အရာအားလုံးက မျှော်လင့်ထားတာထက် ပိုပြီး အန္တရာယ်များနေခဲ့ပါတယ်။';

const state = {
  step: 0,
  captionsOn: true,
  resolution: '720p',
  file: null,
  videoUrl: '',
  voiceFile: null,
  subtitleFile: null,
  segments: [],
  geminiUrl: '',
  demo: false,
  metadata: null,
  renderStatus: 'idle',
  renderProgress: 0,
  renderStage: '',
  renderError: '',
  outputUrl: '',
  offlineFiles: [],
  offlineMode: 'cut',
  offlineStart: 0,
  offlineEnd: 60,
  offlineClipSeconds: 180,
  offlineSpeed: 1,
  offlineRotate: 0,
  offlineMute: false,
  offlineFill: false,
  offlineFrame: 'original',
  offlineOutputSize: 'original',
  offlineBusy: false,
  offlineProgress: 0,
  offlineStage: 'Ready — Offline',
  offlineOutputs: [],
  logoFile: null,
  logoUrl: '',
  logoPosition: 'top-right',
  toast: '',
  modal: '',
  style: {
    topBlur: 8,
    bottomBlur: 10,
    subtitleBlur: 14,
    subY: 66,
    blurStrength: 24,
    flip: false,
    zoom: 1.02,
    fitMode: 'fit',
    subtitleFontSize: 20,
    brightness: 2,
    contrast: 5,
    saturation: 7,
    crop: 'center',
    subtitleColor: '#ffdf3d',
    subtitleBg: '#111111',
    frameColor: '#d6ef47',
    frameWidth: 0,
    filter: 'cinematic'
  }
};

function escapeHtml(value = '') {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function formatTime(seconds = 0) {
  const safe = Math.max(0, Number(seconds) || 0);
  const minutes = Math.floor(safe / 60);
  const secs = Math.floor(safe % 60).toString().padStart(2, '0');
  return `${minutes}:${secs}`;
}

function fileSize(bytes = 0) {
  if (!bytes) return '—';
  const units = ['B', 'KB', 'MB', 'GB'];
  let size = bytes;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size.toFixed(unit ? 1 : 0)} ${units[unit]}`;
}

function sourceName() {
  if (state.demo) return 'mystery-door-demo.mp4';
  return state.file?.name || 'မရွေးရသေးပါ';
}



function setText(selector, value) {
  const el = document.querySelector(selector);
  if (el) el.textContent = value;
}

function render() {
  app.innerHTML = `
    <div class="shell">
      <aside class="sidebar">
        <div class="brand-lockup">
          <div class="brand-mark"><span></span><span></span><span></span></div>
          <div>
            <div class="brand-name">SI GYI</div>
            <div class="brand-sub">RECAP STUDIO</div>
          </div>
        </div>

        <div class="workspace-label">WORKSPACE</div>
        <nav class="side-nav">
          <button class="nav-item active" type="button"><span class="nav-icon">✦</span><span>Recap Studio</span><span class="nav-count">01</span></button>
          <button class="nav-item" type="button"><span class="nav-icon">◫</span><span>My projects</span><span class="nav-count muted">0</span></button>
          <button class="nav-item" type="button"><span class="nav-icon">⌁</span><span>Quick guide</span></button>
        </nav>

        <div class="workspace-label lower-label">PREFERENCES</div>
        <nav class="side-nav">
          <button class="nav-item" type="button"><span class="nav-icon">◉</span><span>Local workspace</span><span class="key-dot ready"></span></button>
          <button class="nav-item" type="button"><span class="nav-icon">◌</span><span>Settings</span></button>
        </nav>

        <div class="sidebar-bottom">
          <div class="local-card">
            <div class="local-orbit"><span></span></div>
            <div>
              <strong>Local mode</strong>
              <p>သင့်ဖုန်းထဲမှာပဲ<br />ပြင်ဆင်ပြီး render လုပ်မယ်</p>
            </div>
          </div>
          <div class="profile-chip"><div class="profile-avatar">SG</div><div><strong>Si Gyi creator</strong><small>Free workspace</small></div><span>•••</span></div>
        </div>
      </aside>

      <main class="main-content">
        <header class="topbar">
          <div class="mobile-brand"><div class="brand-mark small"><span></span><span></span><span></span></div><span>SI GYI</span></div>
          <div class="breadcrumb"><span>Studio</span><b>/</b><strong>${steps.find((item) => item.id === state.step)?.en || 'Studio'}</strong></div>
          <div class="top-actions">
            <div class="privacy-chip"><span class="status-dot"></span> Local · Offline capable</div>
            
            <button class="avatar-button" type="button">SG</button>
          </div>
        </header>

        <div class="content-wrap">
          <section class="page-heading">
            <div>
              <div class="eyebrow"><span class="eyebrow-line"></span> BURMESE VIDEO WORKFLOW <span class="beta-pill">BETA</span></div>
              <h1>Upload.<br /><em>Style. Render.</em></h1>
              <p>ဗီဒီယိုတင်ပြီး style ကို ပြင်ဆင်ပါ။ Script ရေးခြင်းနဲ့ အသံထုတ်ခြင်း မပါဘဲ local MP4 ထုတ်နိုင်ပါတယ်။</p>
            </div>
            <div class="heading-actions">
              
              <button class="outline-button" type="button" data-action="load-demo"><span>◉</span> Try a sample</button>
            </div>
          </section>

          <div class="flow-tabs">
            ${steps.map((step) => `
              <button class="flow-tab ${state.step === step.id ? 'active' : ''} ${state.step > step.id ? 'done' : ''}" type="button" data-step="${step.id}">
                <span class="tab-number">${state.step > step.id ? '✓' : `0${step.id + 1}`}</span>
                <span><strong>${step.en}</strong><small>${step.mm}</small></span>
              </button>
            `).join('')}
          </div>

          <div class="workspace-grid">
            <section class="stage-panel">
              ${stageMarkup()}
            </section>
            <aside class="right-rail">
              ${railMarkup()}
            </aside>
          </div>
        </div>
      </main>

      <nav class="mobile-nav"><button class="active">✦<span>Studio</span></button><button>◫<span>Projects</span></button><button>◌<span>Settings</span></button><button>◌<span>Settings</span></button></nav>
      
    </div>
  `;
  bindEvents();
}

function stageMarkup() {
  if (state.step === 0) return sourceStage();
  if (state.step === 3) return styleStage();
  return renderStage();
}


function offlineEditorStage() {
  const files = state.offlineFiles || [];
  return `
    <div class="stage-header"><div><div class="stage-kicker">OFFLINE TOOLBOX / NO INTERNET</div><h2>Auto Video Editor</h2><p>PDF ထဲက လုပ်ဆောင်ချက်များကို ဖုန်းထဲမှာပဲ အင်တာနက်မလိုဘဲ လုပ်ဆောင်နိုင်ရန်။</p></div><div class="stage-badge"><span class="badge-dot"></span> LOCAL PROCESSING</div></div>
    <div class="offline-mode-grid">
      <button class="offline-mode ${state.offlineMode==='cut'?'active':''}" data-offline-mode="cut" type="button"><b>✂ CUT VIDEO</b><small>အချိန်အပိုင်းရွေးဖြတ်ရန်</small></button>
      <button class="offline-mode ${state.offlineMode==='merge'?'active':''}" data-offline-mode="merge" type="button"><b>▣ MERGE VIDEO</b><small>Video များ ပေါင်းရန်</small></button>
      <button class="offline-mode ${state.offlineMode==='split'?'active':''}" data-offline-mode="split" type="button"><b>◫ AUTO SPLITTER</b><small>အပိုင်းတိုများ အလိုအလျောက်ခွဲရန်</small></button>
      <button class="offline-mode ${state.offlineMode==='smart'?'active':''}" data-offline-mode="smart" type="button"><b>✧ SMART HIGHLIGHTS</b><small>Visual scene change ကို အလိုအလျောက်ရှာရန်</small></button>
    </div>
    <section class="offline-card">
      <h3>01 / SELECT VIDEO</h3>
      <label class="offline-pick"><input id="offline-video-input" type="file" accept="video/*" ${state.offlineMode==='merge'?'multiple':''}/><span>＋ Choose ${state.offlineMode==='merge'?'videos':'a video'}</span></label>
      <p class="offline-file-list">${files.length ? files.map(f=>escapeHtml(f.name)).join('<br>') : 'ဗီဒီယို မရွေးရသေးပါ။'}</p>
    </section>
    <section class="offline-card">
      <h3>02 / EDITING MODE</h3>
      ${state.offlineMode==='cut' ? `<div class="offline-fields"><label>Start time (seconds)<input id="offline-start" type="number" min="0" value="${state.offlineStart}"></label><label>End time (seconds)<input id="offline-end" type="number" min="1" value="${state.offlineEnd}"></label></div>` : ''}
      ${['split','smart'].includes(state.offlineMode) ? `<label class="offline-field">Clip length<select id="offline-clip"><option value="15" ${state.offlineClipSeconds===15?'selected':''}>15s Shorts</option><option value="30" ${state.offlineClipSeconds===30?'selected':''}>30s Shorts</option><option value="60" ${state.offlineClipSeconds===60?'selected':''}>60s Shorts</option><option value="180" ${state.offlineClipSeconds===180?'selected':''}>3 minutes</option><option value="300" ${state.offlineClipSeconds===300?'selected':''}>5 minutes</option><option value="600" ${state.offlineClipSeconds===600?'selected':''}>10 minutes</option><option value="900" ${state.offlineClipSeconds===900?'selected':''}>15 minutes</option></select></label>` : ''}
      <div class="offline-fields"><label>Frame<select id="offline-frame"><option value="original" ${state.offlineFrame==='original' ? 'selected' : ''}>Original</option><option value="vertical" ${state.offlineFrame==='vertical' ? 'selected' : ''}>9:16 Shorts</option></select></label><label>Output<select id="offline-output-size"><option value="original" ${state.offlineOutputSize==='original' ? 'selected' : ''}>Original size</option><option value="720p" ${state.offlineOutputSize==='720p' ? 'selected' : ''}>720p</option><option value="1080p" ${state.offlineOutputSize==='1080p' ? 'selected' : ''}>1080p</option></select></label></div>
      <div class="offline-fields"><label>Speed<select id="offline-speed"><option value="0.5" ${state.offlineSpeed===0.5?'selected':''}>0.5x</option><option value="0.75" ${state.offlineSpeed===0.75?'selected':''}>0.75x</option><option value="1" ${state.offlineSpeed===1?'selected':''}>1x</option><option value="1.25" ${state.offlineSpeed===1.25?'selected':''}>1.25x</option><option value="1.5" ${state.offlineSpeed===1.5?'selected':''}>1.5x</option><option value="2" ${state.offlineSpeed===2?'selected':''}>2x</option></select></label><label>Rotate<select id="offline-rotate"><option value="0" ${state.offlineRotate===0?'selected':''}>0°</option><option value="90" ${state.offlineRotate===90?'selected':''}>90°</option><option value="180" ${state.offlineRotate===180?'selected':''}>180°</option><option value="270" ${state.offlineRotate===270?'selected':''}>270°</option></select></label></div>
      <label class="offline-check"><input id="offline-mute" type="checkbox" ${state.offlineMute?'checked':''}> Mute original audio</label>
      <label class="offline-check"><input id="offline-fill" type="checkbox" ${state.offlineFill?'checked':''}> Fill frame (center crop). Off = fit with black bars</label>
      <p class="offline-hint">Smart Highlights သည် ရုပ်ပုံ scene change ကိုသာ စစ်ဆေးသည်။ စကားပြော/ဇာတ်လမ်းအဓိပ္ပာယ်ကို မသိနိုင်ပါ။ အများဆုံး ၁၀ ခု ထုတ်ပေးမည်။</p>
    </section>
    <section class="offline-card">
      <h3>03 / EXPORT</h3>
      <button class="primary-button wide" type="button" data-action="offline-export" ${state.offlineBusy?'disabled':''}>${state.offlineBusy?'PROCESSING…':'EDIT & EXPORT →'}</button>
      <div class="offline-progress"><div style="width:${state.offlineProgress}%"></div></div><p class="offline-hint">${escapeHtml(state.offlineStage)} · ${state.offlineProgress}%</p>
      ${state.offlineOutputs.map((o,i)=>`<a class="offline-download" href="${o.url}" download="${escapeHtml(o.name)}">⬇ Download ${escapeHtml(o.name)}</a>`).join('')}
    </section>
    <div class="offline-foot">✓ Fully offline after app installation　✓ No upload　✓ No subscription　· MP4 output</div>
  `;
}

function sourceStage() {
  const ready = Boolean(state.file || state.demo);
  return `
    <div class="stage-header"><div><div class="stage-kicker">STEP 01 / GEMINI WEBVIEW + VIDEO UPLOAD</div><h2>${ready ? 'Video is ready.' : 'Bring your video in.'}</h2><p>${ready ? 'Video, WAV နဲ့ SRT ကို တစ်နေရာတည်းမှာ စုစည်းပြီး Style & Render ဆီ ဆက်သွားပါ။' : 'Gemini ကို App အတွင်းဖွင့်ပြီး Video / WAV / SRT ရယူပါ။ သို့မဟုတ် ဖုန်းထဲက Video ကို ရွေးပါ။'}</p></div><div class="stage-badge"><span class="badge-dot"></span> ${ready ? 'Local video ready' : 'Local upload'}</div></div>
    <section class="gemini-hub">
      <div class="gemini-brand-row">
        <div class="gemini-brand-icon"><span>✦</span><i></i></div>
        <div class="gemini-brand-copy"><span class="gemini-overline">SI GYI · AI TOOLBOX</span><h3>Gemini Workspace</h3><p>လိုအပ်တဲ့ AI Tool ကို ရွေးပြီး အောက်က WebView ထဲမှာ ဖွင့်ပါ။</p></div>
        <span class="gemini-count">${geminiTools.length} TOOLS</span>
      </div>
      <div class="gemini-url-row"><label class="range-control"><span>Custom Gemini link</span><input id="gemini-url" type="url" placeholder="https://gemini.google.com/..." value="${escapeHtml(state.geminiUrl)}" /></label><button class="outline-button" type="button" data-action="open-gemini">Open link</button></div>
      <div class="gemini-tool-heading"><div><strong>GEMINI SHORTCUTS</strong><small>Tool တစ်ခုကိုနှိပ်ပြီး WebView မှာဖွင့်ပါ</small></div><span>✧</span></div>
      <div class="gemini-tool-grid">${geminiTools.map((tool, index) => `<button class="gemini-tool-card ${state.geminiUrl === tool.url ? 'selected' : ''}" type="button" data-gemini-link="${tool.id}"><span class="gemini-tool-number">${String(index + 1).padStart(2, '0')}</span><span class="gemini-tool-text"><strong>${escapeHtml(tool.title)}</strong><small>${escapeHtml(tool.desc)}</small></span><span class="gemini-tool-arrow">↗</span></button>`).join('')}</div>
      <div class="gemini-view-head"><div><span class="live-dot"></span><strong>GEMINI IN-APP WEBVIEW</strong><small>${state.geminiUrl ? 'ရွေးထားသော Gemini tool' : 'Tool တစ်ခုကို ရွေးပါ'}</small></div></div>
      ${state.geminiUrl ? `<div class="gemini-inapp-card"><div class="gemini-inapp-symbol">✦</div><div class="gemini-inapp-copy"><strong>Gemini ကို Si Gyi App ထဲမှာ ဖွင့်ရန်</strong><span>ဒီခလုတ်က App အတွင်း Native WebView ကို ဖွင့်ပေးပြီး Download လုပ်တဲ့ Video / WAV / SRT ဖိုင်တွေကို Render workspace ဆီ ပြန်ပို့ပေးပါမယ်။</span></div><button class="primary-button" type="button" data-action="open-gemini-webview">Open in App ↗</button></div><div class="gemini-embed-note">ပြင်ပ Browser မဖွင့်ပါ။ Gemini page ကို App ရဲ့ WebView window ထဲမှာပဲ ဖွင့်ထားပါမယ်။</div>` : `<div class="gemini-empty"><div class="gemini-empty-icon">✦</div><strong>Choose your Gemini tool</strong><span>အပေါ်က Shortcut တစ်ခုကို ရွေးပါ။ Gemini ကို App အတွင်းမှာ ဖွင့်နိုင်ပါမယ်။</span></div>`}
      <div class="gemini-import-row"><div><strong>Downloaded files</strong><small>Gemini Download ပြီးတာနဲ့ Video / WAV / SRT ကို Style & Render ထဲ Auto Import လုပ်ရန်</small><p>VIDEO: <b>${escapeHtml(state.file?.name || 'မရှိသေးပါ')}</b><br>WAV: <b>${escapeHtml(state.voiceFile?.name || 'မရှိသေးပါ')}</b>　 SRT: <b>${escapeHtml(state.subtitleFile?.name || 'မရှိသေးပါ')}</b></p></div><div class="gemini-import-buttons"><label class="outline-button">＋ WAV<input id="wav-input" type="file" accept="audio/wav,audio/*" hidden /></label><label class="outline-button">＋ SRT<input id="srt-input" type="file" accept=".srt,text/plain,application/x-subrip" hidden /></label></div></div>
    </section>
    ${ready ? `<div class="source-ready"><div class="video-shell source-video-shell">${state.demo ? '<div class="demo-frame"><div class="demo-glow"></div><div class="demo-door">◈</div><span>SAMPLE SCENE</span></div>' : `<video id="source-video" src="${state.videoUrl}" controls playsinline></video>`}<div class="video-corner-tag">SOURCE · VIDEO</div></div><div class="source-details"><div class="file-title"><span class="file-icon">▣</span><div><strong>${escapeHtml(sourceName())}</strong><span>${fileSize(state.metadata?.size)} · ${escapeHtml(state.metadata?.type || 'video')}</span></div><button class="mini-icon" type="button" data-action="pick-video">↻</button></div><div class="ready-callout"><span>✓</span><div><strong>Local video loaded</strong><p>Browser memory ထဲကနေ style ပြင်ပြီး render လုပ်ပါမယ်။</p></div></div><div class="source-stats"><div><span>FORMAT</span><strong>VIDEO</strong></div><div><span>SIZE</span><strong>${fileSize(state.metadata?.size)}</strong></div><div><span>MODE</span><strong>OFFLINE</strong></div></div><div class="privacy-note"><span>◉</span><span>Video ကို server သို့ မပို့ပါ။ ဒီ browser session ထဲမှာပဲ ထိန်းထားပါတယ်။</span></div></div></div><div class="stage-footer"><button class="secondary-button" type="button" data-action="pick-video">Change video</button><button class="primary-button" type="button" data-step="3">Continue to style <span class="button-arrow">→</span></button></div>` : `<div class="dropzone" id="dropzone"><input id="video-input" type="file" accept="video/*" hidden /><div class="upload-orbit"><div class="upload-arrow">↑</div></div><h3>Drop your video here</h3><p>သို့မဟုတ် ဖုန်းထဲက video file ကို ရွေးပါ</p><button class="primary-button" type="button" data-action="pick-video">Choose video <span class="button-arrow">→</span></button><div class="drop-meta"><span>MP4</span><span>MOV</span><span>WEBM</span><i></i><span>250 MB max</span></div></div><div class="source-feature-row"><div><span class="feature-icon">▣</span><strong>Video upload</strong><small>ဖုန်းထဲက file ကို တိုက်ရိုက်ဖွင့်ပါမယ်။</small></div><div><span class="feature-icon">◌</span><strong>Video style</strong><small>Blur, crop, filter နဲ့ frame ကို ပြင်ပါ။</small></div><div><span class="feature-icon">↓</span><strong>MP4 render</strong><small>FFmpeg WASM နဲ့ local render လုပ်ပါမယ်။</small></div></div>`}
    <div class="tip-banner"><span class="tip-icon">i</span><span><strong>Local workflow:</strong> Script နဲ့ အသံထုတ်ခြင်း မပါဘဲ video ကို style ပြင်ပြီး export လုပ်နိုင်ပါတယ်။</span></div>`;
}

function previewFilter() {
  const preset = state.style.filter === 'vivid' ? 'saturate(1.16) contrast(1.05)' : state.style.filter === 'cinematic' ? 'saturate(0.94) contrast(1.04)' : state.style.filter === 'mono' ? 'grayscale(1) contrast(1.04)' : 'none';
  return `brightness(${100 + state.style.brightness / 2}%) contrast(${100 + state.style.contrast}%) saturate(${100 + state.style.saturation}%) ${preset}`;
}


// update the live preview WITHOUT re-rendering the whole app (keeps slider drags smooth)
function applyPreview() {
  const st = state.style;
  const canvas = document.querySelector('.editor-canvas');
  if (!canvas) return;
  canvas.style.border = st.frameWidth ? `${st.frameWidth}px solid ${st.frameColor}` : 'none';
  const blur = `blur(${Math.max(2, st.blurStrength * 0.45)}px)`;
  const band = (el, css) => { if (!el) return; Object.assign(el.style, css, { background: 'transparent', backdropFilter: blur, webkitBackdropFilter: blur }); };
  band(canvas.querySelector('.blur-band.top'), { height: `${st.topBlur}%`, display: st.topBlur > 0 ? 'block' : 'none', opacity: 1 });
  band(canvas.querySelector('.blur-band.bottom'), { height: `${st.bottomBlur}%`, display: st.bottomBlur > 0 ? 'block' : 'none', opacity: 1 });
  band(canvas.querySelector('.subtitle-blur'), { height: `${st.subtitleBlur}%`, top: `${st.subY}%`, bottom: 'auto', display: st.subtitleBlur > 0 ? 'block' : 'none', opacity: 1 });
  const media = canvas.querySelector('.editor-video, .editor-demo');
  if (media) {
    media.style.filter = previewFilter();
    media.style.transform = `scale(${st.zoom}) ${st.flip ? 'scaleX(-1)' : ''}`;
    media.style.objectFit = st.fitMode === 'fit' ? 'contain' : 'cover';
    media.style.background = '#080a0b';
    media.style.objectPosition = st.crop === 'left' ? '0% 50%' : st.crop === 'right' ? '100% 50%' : '50% 50%';
  }
  const cap = canvas.querySelector('.subtitle-preview');
  if (cap) {
    cap.style.top = `${st.subY + st.subtitleBlur / 2}%`;
    cap.style.bottom = 'auto';
    cap.style.transform = 'translateY(-50%)';
    cap.style.display = state.captionsOn ? 'block' : 'none';
    cap.style.color = st.subtitleColor;
    cap.style.background = `${st.subtitleBg}c7`;
    cap.style.textShadow = '-1px -1px 0 #111, 1px -1px 0 #111, -1px 1px 0 #111, 1px 1px 0 #111';
    cap.style.fontSize = `${12 * (Number(st.subtitleFontSize || 20) / 20)}px`;
    cap.style.padding = '3px 9px';
    cap.style.borderRadius = '6px';
    cap.style.width = 'auto';
    cap.style.maxWidth = '88%';
    cap.style.left = '50%';
    cap.style.marginLeft = '0';
    cap.style.transform = 'translate(-50%, -50%)';
  }
}

function styleStage() {
  const st = state.style;
  return `<div class="stage-header compact"><div><div class="stage-kicker">STEP 02 / VIDEO STYLE</div><h2>Make it yours.</h2><p>Blur, crop, filter, frame နဲ့ logo ကို ပြင်ပါ။ Original video audio ကို ထိန်းထားပြီး Render လုပ်ပါမယ်။</p></div><div class="stage-badge"><span class="badge-dot"></span> Live preview</div></div><div class="style-layout"><div class="editor-canvas-wrap"><div class="canvas-toolbar"><span class="mini-label">9:16 SHORT-FORM PREVIEW</span><div><button class="canvas-icon ${st.flip ? 'active' : ''}" type="button" data-action="flip">⇆</button><button class="canvas-icon" type="button" data-action="fit">⛶</button></div></div><div class="editor-canvas"><div class="blur-band top"></div>${state.demo ? '<div class="demo-frame editor-demo"><div class="demo-glow"></div><div class="demo-door">◈</div><span>SAMPLE SCENE</span></div>' : state.videoUrl ? `<video class="editor-video" src="${state.videoUrl}" controls playsinline muted></video>` : '<div class="empty-preview"><span>▣</span><small>Video ကို အရင်ရွေးပါ</small></div>'}<div class="subtitle-blur"></div><div class="blur-band bottom"></div></div><div class="canvas-footer"><span><i class="green-dot"></i> Preview synced with render</span><span>${state.resolution} · ${formatTime(state.metadata?.duration)}</span></div></div><div class="controls-panel"><div class="control-section"><div class="control-heading"><span>BLUR LAYERS</span><button class="reset-link" type="button" data-action="reset-blur">Reset</button></div>${rangeControl('Top blur height','topBlur',st.topBlur,0,30,'%')} ${rangeControl('Bottom blur height','bottomBlur',st.bottomBlur,0,30,'%')} ${rangeControl('Blur strength','blurStrength',st.blurStrength,4,40,'')}</div><div class="control-section"><div class="control-heading"><span>FILTER</span><span class="control-sub">Subtle look</span></div><label class="range-control"><span>Color preset</span><select data-style-filter><option value="natural" ${st.filter==='natural'?'selected':''}>Natural</option><option value="cinematic" ${st.filter==='cinematic'?'selected':''}>Soft cinematic</option><option value="vivid" ${st.filter==='vivid'?'selected':''}>Vivid</option><option value="mono" ${st.filter==='mono'?'selected':''}>Black & white</option></select></label><label class="range-control"><span>Video framing</span><select data-fit-mode><option value="fit" ${st.fitMode==='fit'?'selected':''}>Fit phone frame</option><option value="fill" ${st.fitMode==='fill'?'selected':''}>Fill frame</option></select></label>${rangeControl('Small zoom','zoom',st.zoom,1,1.12,'×',0.01)} ${rangeControl('Brightness','brightness',st.brightness,-20,20,'%')} ${rangeControl('Contrast','contrast',st.contrast,-20,20,'%')} ${rangeControl('Saturation','saturation',st.saturation,-20,20,'%')}</div><div class="control-section crop-section"><div class="control-heading"><span>CROP FRAMING</span><span class="control-sub">9:16 focus</span></div><div class="crop-buttons"><button class="crop-button ${st.crop==='left'?'active':''}" type="button" data-crop="left">Left</button><button class="crop-button ${st.crop==='center'?'active':''}" type="button" data-crop="center">Center</button><button class="crop-button ${st.crop==='right'?'active':''}" type="button" data-crop="right">Right</button></div></div><div class="control-section resolution-section"><div class="control-heading"><span>EXPORT QUALITY</span><span class="control-sub">MP4 resolution</span></div><label class="range-control"><span>Output resolution</span><select data-resolution><option value="360p" ${state.resolution==='360p'?'selected':''}>360p · Fastest</option><option value="720p" ${state.resolution==='720p'?'selected':''}>720p · Recommended</option><option value="1080p" ${state.resolution==='1080p'?'selected':''}>1080p · High quality</option></select></label></div><div class="control-section color-section"><div class="control-heading"><span>FRAME COLOR</span><span class="control-sub">Customize</span></div><div class="color-grid"><label>Frame color<input type="color" data-style-color="frameColor" value="${st.frameColor}" /></label></div>${rangeControl('Frame width','frameWidth',st.frameWidth,0,18,'px')}</div><div class="control-section logo-section"><div class="control-heading"><span>BRAND LOGO</span><span class="control-sub">Optional image</span></div><input id="logo-input" type="file" accept="image/png,image/jpeg,image/webp,image/*" hidden /><div class="logo-picker-row">${state.logoUrl?`<img class="logo-thumb" src="${state.logoUrl}" alt="Selected logo" />`:'<span class="logo-thumb-placeholder">LOGO</span>'}<div class="logo-picker-copy"><strong>${state.logoFile?escapeHtml(state.logoFile.name):'Logo မရွေးရသေးပါ'}</strong><small>Render video ပေါ်မှာ logo ထည့်နိုင်ပါတယ်။</small></div><button class="outline-button" type="button" data-action="pick-logo">${state.logoFile?'Change':'Choose logo'}</button>${state.logoFile?'<button class="text-button danger" type="button" data-action="remove-logo">×</button>':''}</div><label class="range-control"><span>Logo position</span><select data-logo-position><option value="top-right" ${state.logoPosition==='top-right'?'selected':''}>Top right</option><option value="top-left" ${state.logoPosition==='top-left'?'selected':''}>Top left</option><option value="bottom-right" ${state.logoPosition==='bottom-right'?'selected':''}>Bottom right</option><option value="bottom-left" ${state.logoPosition==='bottom-left'?'selected':''}>Bottom left</option></select></label></div><div class="control-section"><div class="control-heading"><span>GEMINI AUDIO & SUBTITLES</span><span class="control-sub">Auto-import ready</span></div><div class="logo-picker-row"><div class="logo-picker-copy"><strong>${state.voiceFile ? escapeHtml(state.voiceFile.name) : 'WAV မရောက်သေးပါ'}</strong><small>Voice track · ${state.voiceFile ? fileSize(state.voiceFile.size) : 'Waiting'}</small></div><label class="outline-button">${state.voiceFile ? 'Replace WAV' : 'Import WAV'}<input id="wav-style-input" type="file" accept="audio/wav,.wav" hidden /></label></div><div class="logo-picker-row"><div class="logo-picker-copy"><strong>${state.subtitleFile ? escapeHtml(state.subtitleFile.name) : 'SRT မရောက်သေးပါ'}</strong><small>Subtitle track · ${state.segments.length} lines</small></div><label class="outline-button">${state.subtitleFile ? 'Replace SRT' : 'Import SRT'}<input id="srt-style-input" type="file" accept=".srt,text/plain" hidden /></label></div><p class="control-sub">Gemini WebView native download bridge မှ WAV/SRT ရောက်လာပါက ဒီနေရာမှာ အလိုအလျောက်ထည့်ပေးမည်။</p></div></div></div><div class="stage-footer"><button class="secondary-button" type="button" data-step="0">← Change video</button><button class="primary-button render-button" type="button" data-step="4"><span class="button-spark">✦</span> Continue to render <span class="button-arrow">→</span></button></div>`;
}
function rangeControl(label, key, value, min, max, suffix, step = 1) {
  const display = key === 'zoom' ? Number(value).toFixed(2) : Math.round(value);
  return `<label class="range-control"><span>${label}</span><input type="range" data-style="${key}" min="${min}" max="${max}" step="${step}" value="${value}" /><output data-suffix="${suffix}">${display}${suffix}</output></label>`;
}

function renderStage() {
  const complete = state.renderStatus === 'complete'; const working = state.renderStatus === 'working';
  return `<div class="stage-header compact"><div><div class="stage-kicker">STEP 03 / RENDER</div><h2>${complete ? 'Your video is ready.' : 'Ready for the final render?'}</h2><p>${complete ? 'Preview ပြီး download လုပ်နိုင်ပါပြီ။' : 'Style ပြင်ထားတဲ့ video ကို browser ထဲမှာပဲ local MP4 အဖြစ် ထုတ်ပါမယ်။'}</p></div><div class="stage-badge ${complete ? 'success' : ''}"><span class="badge-dot"></span> ${complete ? 'Export complete' : working ? 'Rendering locally' : 'Review ready'}</div></div>${complete ? `<div class="result-layout"><div class="result-video-shell video-shell">${state.outputUrl ? `<video src="${state.outputUrl}" controls playsinline></video>` : '<div class="render-placeholder"><span>✓</span></div>'}<div class="video-corner-tag">FINAL · MP4</div></div><div class="result-details"><div class="result-kicker">FINAL VIDEO</div><h3>${escapeHtml(sourceName().replace(/\.[^/.]+$/, ''))}</h3><p>Local render · Style applied · ${state.voiceFile ? 'Gemini WAV audio synced' : 'Original audio preserved'}${state.logoFile ? ' · Logo overlay' : ''}</p><div class="preview-before-download">▶ Download မလုပ်ခင် preview ကို အရင်ကြည့်ပါ။</div><div class="result-meta"><span><b>9:16</b> format</span><span><b>${state.resolution}</b> export</span><span><b>MP4</b> export</span></div><a class="primary-button wide download-button" href="${state.outputUrl || '#'}" download="sigyi-video.mp4"><span>↓</span> Download MP4 <span class="button-arrow">→</span></a><button class="outline-button wide" type="button" data-action="reset">＋ Start another video</button></div></div>` : `<div class="render-review"><div class="review-card"><div class="review-icon">✦</div><div><strong>Local video render</strong><p>Video နဲ့ original audio ကို ဒီ device ထဲမှာပဲ ထိန်းထားပြီး selected style ကို apply လုပ်ပါမယ်။</p></div><span class="review-check">✓</span></div><div class="review-list"><div><span class="review-thumb">▣</span><span><strong>Source video</strong><small>${escapeHtml(sourceName())}</small></span><b>✓</b></div><div><span class="review-thumb blue">◌</span><span><strong>Visual treatment</strong><small>Blur, crop, filter${state.style.flip ? ', flip' : ''}, frame</small></span><b>✓</b></div><div><span class="review-thumb coral">▣</span><span><strong>${state.voiceFile ? 'Gemini WAV narration' : 'Original audio'}</strong><small>${state.voiceFile ? escapeHtml(state.voiceFile.name) + ' · WAV track replaces source audio' : 'Source audio will be preserved'}</small></span><b>✓</b></div></div>${working ? `<div class="render-progress-card"><div class="progress-head"><span id="render-stage">${escapeHtml(state.renderStage || 'Rendering with ffmpeg.wasm')}</span><strong id="render-pct">${Math.round(state.renderProgress)}%</strong></div><div class="big-progress"><i id="render-bar" style="width:${state.renderProgress}%"></i></div><p>ဖုန်းကို lock မလုပ်ပါနဲ့။ Render ပြီးတဲ့အထိ ဒီ tab ကို ဖွင့်ထားပါ။</p><button class="text-button danger" type="button" data-action="cancel-render">Cancel render</button></div>` : `<button class="primary-button render-button" type="button" data-action="render"><span class="button-spark">✦</span> Render MP4 <span class="button-arrow">→</span></button><div class="render-warning"><span>i</span><span>FFmpeg WASM ကို ပထမဆုံးအကြိမ် load လုပ်ရင် ခဏကြာနိုင်ပါတယ်။ Original audio ကို ထိန်းထားပြီး video style ကိုသာ ပြောင်းပါမယ်။</span></div>`}</div>`}${state.renderError ? `<div class="inline-error large">${escapeHtml(state.renderError)}</div>` : ''}<div class="stage-footer"><button class="secondary-button" type="button" data-step="3">← Back to style</button><span class="footer-hint">Local-only · No account required</span></div>`;
}
function railMarkup() {
  return `<div class="rail-card workflow-card"><div class="rail-heading"><span>YOUR WORKFLOW</span><span class="rail-percent">${state.step === 0 ? 0 : state.step === 3 ? 50 : 100}%</span></div><div class="workflow-list">${steps.map((step) => `<button type="button" class="workflow-row ${state.step === step.id ? 'current' : ''} ${state.step > step.id ? 'complete' : ''}" data-step="${step.id}"><span class="workflow-bullet">${state.step > step.id ? '✓' : step.id === 0 ? '01' : step.id === 3 ? '02' : '03'}</span><span><strong>${step.en}</strong><small>${step.id === 0 ? 'Choose a local video file' : step.id === 3 ? 'Blur, crop & adjust' : 'Export MP4 locally'}</small></span><i>${state.step > step.id ? 'done' : state.step === step.id ? 'now' : ''}</i></button>`).join('')}</div></div><div class="rail-card key-card"><div class="key-card-top"><div class="key-orb">◉</div><div><strong>Local video mode</strong><small>Upload / Style / Render</small></div></div><p>Script ရေးခြင်းနဲ့ အသံထုတ်ခြင်း မပါဘဲ video upload, style နဲ့ render ကိုသာ အသုံးပြုပါမယ်။</p></div><div class="rail-card support-card"><span class="support-mark">◎</span><div><strong>Quick start</strong><p>Short clip တစ်ခုနဲ့ အရင်စမ်းပြီး render speed ကို စစ်ပါ။</p></div></div>`;
}


function parseSrt(raw) {
  const normalized = String(raw || '').replace(/^\uFEFF/, '').replace(/\r/g, '').trim();
  const blocks = normalized.split(/\n\s*\n/);
  const rows = [];
  const toSeconds = (stamp) => { const m = stamp.match(/(\d{2}):(\d{2}):(\d{2})[,\.](\d{1,3})/); if (!m) throw new Error('Timestamp မမှန်ပါ'); return Number(m[1])*3600 + Number(m[2])*60 + Number(m[3]) + Number((m[4]+'00').slice(0,3))/1000; };
  for (const block of blocks) {
    const lines = block.split('\n').map(x => x.trim()).filter(Boolean);
    const ti = lines.findIndex(x => x.includes('-->'));
    if (ti < 0) continue;
    const match = lines[ti].match(/(.+?)\s+-->\s+(.+)/); if (!match) continue;
    const start = toSeconds(match[1]), end = toSeconds(match[2]);
    const text = lines.slice(ti + 1).join(' ').replace(/<[^>]*>/g, '').trim();
    if (text && end > start) rows.push({ start, end, original: '', text });
  }
  if (!rows.length) throw new Error('Subtitle line မတွေ့ပါ');
  return rows;
}

// Native Android WebView download bridge: the Android shell can dispatch a File/Blob
// here after a Gemini download completes. This keeps browser and APK workflows identical.
async function importWavFile(file) {
  if (!file) return;
  if (!/audio\/(wav|x-wav)|\.wav$/i.test(`${file.type} ${file.name}`)) return setToast('WAV audio ဖိုင်ကိုသာ ရွေးပါ။');
  state.voiceFile = file;
  if (state.file) state.step = 3;
  setToast(`WAV auto-import ပြီးပါပြီ: ${file.name}`);
  render();
}

async function importSrtFile(file) {
  if (!file) return;
  try {
    state.segments = parseSrt(await file.text());
    state.subtitleFile = file;
    if (state.file) state.step = 3;
    setToast(`SRT auto-import ပြီးပါပြီ: ${state.segments.length} လိုင်း`);
    render();
  } catch (error) { setToast(`SRT ဖတ်မရပါ: ${error.message}`); }
}

function importBridgePayload(payload) {
  if (!payload) return;
  const name = payload.name || payload.fileName || 'gemini-download';
  const mime = payload.type || payload.mimeType || (name.toLowerCase().endsWith('.srt') ? 'application/x-subrip' : 'audio/wav');
  let file = payload.file || payload.blob || null;
  if (!file && payload.base64) {
    const binary = atob(String(payload.base64).replace(/^data:[^,]*,/, ''));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    file = new File([bytes], name, { type: mime });
  }
  if (!(file instanceof Blob)) return;
  const wrapped = file instanceof File ? file : new File([file], name, { type: mime });
  if (/\.srt$/i.test(name) || /subrip/i.test(mime)) importSrtFile(wrapped);
  else if (/\.wav$/i.test(name) || /audio/i.test(mime)) importWavFile(wrapped);
  else if (/^video\//i.test(mime) || /\.(mp4|mov|m4v|webm)$/i.test(name)) loadVideo(wrapped);
}

window.SiGyiRecapBridge = Object.freeze({ importFile: importBridgePayload });

// Capacitor Android download events are emitted after Gemini/WebView downloads complete.
if (window.Capacitor?.isNativePlatform?.()) {
  const GeminiDownloadBridge = registerPlugin('GeminiDownloadBridge');
  GeminiDownloadBridge.addListener('downloadedFile', (payload) => importBridgePayload(payload));
  GeminiDownloadBridge.addListener('downloadError', (event) => setToast(`Download error: ${event.message || 'မအောင်မြင်ပါ'}`));
  GeminiDownloadBridge.addListener('downloadStarted', (event) => setToast(`Download စတင်ပါပြီ: ${event.name || ''}`));
}
window.addEventListener('sigyi:downloaded-file', (event) => importBridgePayload(event.detail));
window.addEventListener('message', (event) => {
  // Native shell messages only; web page postMessage is not trusted as a file source.
  if (event.data?.source === 'sigyi-android-download' && window.Capacitor?.isNativePlatform?.()) importBridgePayload(event.data.payload);
});

function bindEvents() {
  document.querySelectorAll('[data-step]').forEach((button) => button.addEventListener('click', () => { const next = Number(button.dataset.step); if (next > 0 && next !== 5 && !state.file && !state.demo) return setToast('အရင်ဆုံး video file တစ်ခု ရွေးပါ။'); state.step = next; render(); }));
  document.querySelectorAll('[data-action]').forEach((button) => button.addEventListener('click', () => handleAction(button.dataset.action)));
  document.querySelectorAll('[data-gemini-link]').forEach((button) => button.addEventListener('click', async () => { const tool = geminiTools.find((item) => item.id === button.dataset.geminiLink); if (!tool) return; state.geminiUrl = tool.url; render(); if (window.Capacitor?.isNativePlatform?.()) await openGeminiInApp(tool.url); }));
  document.querySelectorAll('[data-crop]').forEach((button) => button.addEventListener('click', () => { state.style.crop = button.dataset.crop; render(); }));
  document.querySelectorAll('[data-style]').forEach((input) => input.addEventListener('input', () => { const key = input.dataset.style; state.style[key] = Number(input.value); const out = input.parentElement.querySelector('output'); if (out) out.textContent = `${key === 'zoom' ? Number(input.value).toFixed(2) : Math.round(input.value)}${out.dataset.suffix || ''}`; applyPreview(); }));
  document.querySelectorAll('[data-style-color]').forEach((input) => input.addEventListener('input', () => { state.style[input.dataset.styleColor] = input.value; applyPreview(); }));
  document.querySelectorAll('[data-style-filter]').forEach((input) => input.addEventListener('change', () => { state.style.filter = input.value; applyPreview(); }));
  document.querySelectorAll('[data-fit-mode]').forEach((input) => input.addEventListener('change', () => { state.style.fitMode = input.value; applyPreview(); }));
  document.querySelectorAll('[data-resolution]').forEach((input) => input.addEventListener('change', () => { state.resolution = input.value; setToast(`${state.resolution} resolution ကို ရွေးထားပါတယ်။`); }));

  document.querySelectorAll('[data-offline-mode]').forEach((button) => button.addEventListener('click', () => {
    state.offlineMode = button.dataset.offlineMode;
    if (state.offlineMode !== 'merge' && state.offlineFiles.length > 1) state.offlineFiles = state.offlineFiles.slice(0,1);
    render();
  }));
  const offlineInput = document.querySelector('#offline-video-input');
  if (offlineInput) offlineInput.addEventListener('change', (event) => {
    state.offlineFiles = Array.from(event.target.files || []);
    state.offlineOutputs.forEach(o => URL.revokeObjectURL(o.url));
    state.offlineOutputs = [];
    render();
  });
  const bindOfflineValue = (id, key, type='number') => {
    const el = document.querySelector(id);
    if (el) el.addEventListener('change', () => { state[key] = type==='boolean' ? el.checked : type==='string' ? el.value : Number(el.value); });
  };
  bindOfflineValue('#offline-start','offlineStart');
  bindOfflineValue('#offline-end','offlineEnd');
  bindOfflineValue('#offline-clip','offlineClipSeconds');
  bindOfflineValue('#offline-speed','offlineSpeed');
  bindOfflineValue('#offline-frame','offlineFrame','string');
  bindOfflineValue('#offline-output-size','offlineOutputSize','string');
  bindOfflineValue('#offline-rotate','offlineRotate');
  bindOfflineValue('#offline-mute','offlineMute','boolean');
  bindOfflineValue('#offline-fill','offlineFill','boolean');
  const input = document.querySelector('#video-input'); if (input) input.addEventListener('change', (event) => loadVideo(event.target.files?.[0]));
  const wavHandler = (event) => importWavFile(event.target.files?.[0]);
  const srtHandler = (event) => importSrtFile(event.target.files?.[0]);
  ['#wav-input', '#wav-style-input'].forEach((selector) => document.querySelector(selector)?.addEventListener('change', wavHandler));
  ['#srt-input', '#srt-style-input'].forEach((selector) => document.querySelector(selector)?.addEventListener('change', srtHandler));
  const geminiUrl = document.querySelector('#gemini-url'); if (geminiUrl) geminiUrl.addEventListener('change', () => { state.geminiUrl = geminiUrl.value.trim(); });
  const logoInput = document.querySelector('#logo-input'); if (logoInput) logoInput.addEventListener('change', (event) => { const file = event.target.files?.[0]; if (!file || !file.type.startsWith('image/')) return setToast('Logo အတွက် image ဖိုင် ရွေးပေးပါ။'); if (state.logoUrl) URL.revokeObjectURL(state.logoUrl); state.logoFile = file; state.logoUrl = URL.createObjectURL(file); render(); });
  document.querySelectorAll('[data-logo-position]').forEach((input) => input.addEventListener('change', () => { state.logoPosition = input.value; }));
  const dropzone = document.querySelector('#dropzone'); if (dropzone) { dropzone.addEventListener('dragover', (event) => { event.preventDefault(); dropzone.classList.add('dragging'); }); dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragging')); dropzone.addEventListener('drop', (event) => { event.preventDefault(); dropzone.classList.remove('dragging'); loadVideo(event.dataTransfer.files?.[0]); }); }
  if (state.step === 3) applyPreview();
  const video = document.querySelector('#source-video, .editor-video'); if (video) video.addEventListener('loadedmetadata', () => { if (state.metadata) state.metadata.duration = video.duration; });
}
async function openGeminiInApp(url) {
  if (!url) return setToast('အရင်ဆုံး Gemini Tool တစ်ခု ရွေးပါ။');
  try {
    if (window.Capacitor?.isNativePlatform?.()) {
      const GeminiDownloadBridge = registerPlugin('GeminiDownloadBridge');
      await GeminiDownloadBridge.openGeminiWebView({ url });
      return;
    }
    // Browser preview remains inside this app's page; no external-window target is used.
    const host = document.querySelector('.gemini-inapp-card');
    if (host) {
      host.innerHTML = `<iframe class="gemini-iframe" title="Gemini in-app preview" src="${escapeHtml(url)}" allow="clipboard-read; clipboard-write; fullscreen"></iframe><button class="outline-button" type="button" data-action="close-gemini-preview">Close preview</button>`;
      host.querySelector('[data-action="close-gemini-preview"]')?.addEventListener('click', () => render());
    }
  } catch (error) { setToast(`Gemini WebView ဖွင့်မရပါ: ${error?.message || error}`); }
}

async function handleAction(action) {
  if (action === 'pick-video') return document.querySelector('#video-input')?.click();
  if (action === 'offline-export') return runOfflineExport();
  if (action === 'close-gemini-preview') return render();
  if (action === 'open-gemini') { const input = document.querySelector('#gemini-url'); state.geminiUrl = (input?.value || state.geminiUrl).trim(); let host = ''; try { host = new URL(state.geminiUrl).hostname.toLowerCase(); } catch { return setToast('Gemini link မမှန်ပါ။'); } if (!/^https:\/\//i.test(state.geminiUrl) || !(host === 'gemini.google.com' || host.endsWith('.gemini.google.com'))) return setToast('မှန်ကန်တဲ့ https://gemini.google.com link ထည့်ပါ။'); render(); return openGeminiInApp(state.geminiUrl); }
  if (action === 'open-gemini-webview') return openGeminiInApp(state.geminiUrl);
  if (action === 'pick-logo') return document.querySelector('#logo-input')?.click();
  if (action === 'remove-logo') { if (state.logoUrl) URL.revokeObjectURL(state.logoUrl); state.logoFile = null; state.logoUrl = ''; return render(); }
  if (action === 'load-demo') return loadDemo();
  if (action === 'reset') return resetStudio();
  if (action === 'flip') { state.style.flip = !state.style.flip; return render(); }
  if (action === 'fit') { state.style.zoom = 1; state.style.flip = false; return render(); }
  if (action === 'reset-blur') { Object.assign(state.style, { topBlur: 8, bottomBlur: 10, blurStrength: 24 }); return render(); }
  if (action === 'render') { state.step = 4; return renderVideo(); }
  if (action === 'cancel-render') return cancelRender();
}

async function runOfflineExport() {
  if (!state.offlineFiles.length) return setToast('Video ဖိုင် အရင်ရွေးပါ။');
  if (state.offlineMode === 'merge' && state.offlineFiles.length < 2) return setToast('Merge အတွက် Video အနည်းဆုံး ၂ ခု ရွေးပါ။');
  if (state.offlineMode === 'cut' && state.offlineEnd <= state.offlineStart) return setToast('End time က Start time ထက် ကြီးရပါမယ်။');
  state.offlineBusy = true; state.offlineProgress = 0; state.offlineStage = 'စတင်နေပါတယ်…'; render();
  try {
    const outputs = await renderOfflineVideo({
      files: state.offlineFiles, mode: state.offlineMode, start: state.offlineStart, end: state.offlineEnd,
      clipSeconds: state.offlineClipSeconds, speed: state.offlineSpeed, rotate: state.offlineRotate,
      mute: state.offlineMute, fillFrame: state.offlineFill, frameMode: state.offlineFrame, outputSize: state.offlineOutputSize,
      onProgress: p => { state.offlineProgress = Math.round(p); const bar=document.querySelector('.offline-progress > div'); if(bar) bar.style.width=`${state.offlineProgress}%`; },
      onStage: s => { state.offlineStage=s; const label=document.querySelector('.offline-hint'); if(label) label.textContent=`${s} · ${state.offlineProgress}%`; }
    });
    state.offlineOutputs.forEach(o => URL.revokeObjectURL(o.url));
    state.offlineOutputs = outputs.map((o,i) => ({ name: o.name.replace(/^offline_/, 'SiGyi_'), url: URL.createObjectURL(o.blob) }));
    state.offlineStage='Export ပြီးပါပြီ။'; setToast(`${outputs.length} ဖိုင် ထုတ်ပြီးပါပြီ။`);
  } catch (error) { state.offlineStage=error?.message || 'Export မအောင်မြင်ပါ။'; setToast(state.offlineStage); }
  finally { state.offlineBusy=false; render(); }
}

function loadVideo(file) {
  if (!file) return;
  if (!file.type.startsWith('video/')) return setToast('Video file ပဲ ရွေးပေးပါ။');
  if (state.videoUrl) URL.revokeObjectURL(state.videoUrl);
  state.file = file;
  state.demo = false;
  state.videoUrl = URL.createObjectURL(file);
  state.metadata = { name: file.name, size: file.size, type: file.type, duration: 0 };
  state.step = 3;
  render();
  const video = document.querySelector('#source-video, .editor-video');
  if (video) video.addEventListener('loadedmetadata', () => { if (state.metadata) state.metadata.duration = video.duration; }, { once: true });
}

function loadDemo() {
  if (state.videoUrl) URL.revokeObjectURL(state.videoUrl);
  state.file = null;
  state.videoUrl = '';
  state.voiceFile = null; state.subtitleFile = null; state.segments = [];
  state.demo = true;
  state.metadata = { name: 'mystery-door-demo.mp4', size: 24800000, type: 'video/mp4', duration: 16.7 };
  state.step = 0;
  setToast('Sample preview ကို ဖွင့်ထားပါတယ်။ Final render အတွက် ကိုယ့် video file ကို upload လုပ်ပါ။');
}


let lastTermuxRenderLog = { stage: '', pct: -1, at: 0 };
function sendTermuxRenderLog(stage, pct, extra = {}) {
  const now = Date.now();
  const rounded = pct == null ? null : Math.round(pct);
  if (stage === lastTermuxRenderLog.stage && (rounded == null || rounded < lastTermuxRenderLog.pct + 3) && now - lastTermuxRenderLog.at < 5000) return;
  lastTermuxRenderLog = { stage: stage || lastTermuxRenderLog.stage, pct: rounded ?? lastTermuxRenderLog.pct, at: now };
  fetch('/api/render-log', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'progress', stage: stage || state.renderStage, progress: rounded ?? Math.round(state.renderProgress), resolution: state.resolution, time: new Date().toISOString(), ...extra }) }).catch(() => {});
}

function setRenderUI(stage, pct) {
  if (stage) state.renderStage = stage;
  if (pct != null) state.renderProgress = Math.max(state.renderProgress, pct);
  setText('#render-stage', state.renderStage);
  setText('#render-pct', `${Math.round(state.renderProgress)}%`);
  const bar = document.querySelector('#render-bar');
  if (bar) bar.style.width = `${state.renderProgress}%`;
  if (state.renderStatus === 'working') sendTermuxRenderLog(stage, pct);
}

async function renderVideo() {
  if (!state.file) { state.renderError = 'ကိုယ့် video file တစ်ခု upload လုပ်ပြီးမှ render လုပ်ပါ။'; state.step = 0; return render(); }
  state.renderStatus = 'working'; state.renderProgress = 2; state.renderStage = 'ပြင်ဆင်နေပါတယ်…'; state.renderError = ''; if (state.outputUrl) { URL.revokeObjectURL(state.outputUrl); state.outputUrl = ''; } render();
  try {
    const blob = await renderRecap({ file: state.file, segments: state.segments, timeline: state.segments, voiceBlob: state.voiceFile, style: state.style, captionsOn: state.segments.length > 0, resolution: state.resolution, logoFile: state.logoFile, logoPosition: state.logoPosition, onStage: (stage) => setRenderUI(stage), onProgress: (pct) => setRenderUI(null, pct) });
    state.outputUrl = URL.createObjectURL(blob); state.renderProgress = 100; state.renderStatus = 'complete'; state.renderStage = 'ပြီးပါပြီ — video ready'; render();
  } catch (error) { if (state.renderStatus === 'cancelled') return; state.renderStatus = 'error'; const detail = error?.message || String(error); state.renderError = `${detail}\n\nဒီအမှားစာသားကို Copy လုပ်ပြီး ပို့ပေးပါ။`; console.error('[SiGyi Render Diagnostic]', { at: new Date().toISOString(), stage: state.renderStage, progress: state.renderProgress, error: detail, userAgent: navigator.userAgent, ffmpegLogs: getRenderLogs() }, error); try { await fetch('/api/render-log', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: detail, stage: state.renderStage, progress: state.renderProgress }) }); } catch {} terminateFfmpeg(); render(); }
}
function cancelRender() {
  terminateFfmpeg();
  state.renderStatus = 'cancelled';
  state.renderProgress = 0;
  state.renderStage = '';
  state.renderError = 'Render ကို ရပ်လိုက်ပါပြီ။ ပြန်စရန် Render button ကို နှိပ်ပါ။';
  render();
}

function resetStudio() {
  if (state.videoUrl) URL.revokeObjectURL(state.videoUrl);
  if (state.logoUrl) URL.revokeObjectURL(state.logoUrl);
  if (state.outputUrl) URL.revokeObjectURL(state.outputUrl);
  state.step = 0;
  state.file = null;
  state.videoUrl = '';
  state.demo = false;
  state.metadata = null;
  state.renderStatus = 'idle';
  state.renderProgress = 0;
  state.renderStage = '';
  state.renderError = '';
  state.outputUrl = '';
  state.logoFile = null;
  state.logoUrl = '';
  state.logoPosition = 'top-right';
  state.captionsOn = false;
  state.style = { topBlur: 8, bottomBlur: 10, subtitleBlur: 14, subY: 66, blurStrength: 24, flip: false, zoom: 1.02, fitMode: 'fit', subtitleFontSize: 20, brightness: 2, contrast: 5, saturation: 7, crop: 'center', subtitleColor: '#ffdf3d', subtitleBg: '#111111', frameColor: '#d6ef47', frameWidth: 0, filter: 'cinematic' };
  render();
}

function setToast(message) {
  let root = document.querySelector('#toast-root');
  if (!root) { root = document.createElement('div'); root.id = 'toast-root'; document.body.appendChild(root); }
  root.innerHTML = `<div class="toast">${escapeHtml(message)}</div>`;
  window.clearTimeout(window.__sigyiToast);
  window.__sigyiToast = window.setTimeout(() => { root.innerHTML = ''; }, 3600);
}

render();
