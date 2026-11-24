import './styles.css';
import { renderRecap, terminateFfmpeg, getRenderLogs } from './lib/render.js';
import { Capacitor, registerPlugin } from '@capacitor/core';
const NativeRender = registerPlugin('NativeRender');
const isNativeAndroid = Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';

const app = document.querySelector('#app');

const steps = [
  { id: 0, en: 'Upload video', mm: 'ဗီဒီယိုတင်ရန်' },
  { id: 3, en: 'Video style', mm: 'ပုံစံပြင်ရန်' },
  { id: 4, en: 'Render', mm: 'ဗီဒီယိုထုတ်ရန်' }
];

const state = {
  step: 0,
  captionsOn: true,
  resolution: '720p',
  file: null,
  audioFile: null,
  audioUrl: '',
  videoUrl: '',
  demo: false,
  metadata: null,
  renderStatus: 'idle',
  renderProgress: 0,
  renderStage: '',
  renderError: '',
  outputUrl: '',
  nativeSavedUri: null,
  logoFile: null,
  logoUrl: '',
  logoNativePath: null,
  logoPosition: 'top-right',
  isLandscape: false, // ✅ 16:9 / 9:16 ရွေးချယ်မှု (မူရင်းက 9:16)
  elapsedTime: '00:00', // ✅ ကြာချိန်
  remainingTime: '00:00', // ✅ ခန့်မှန်းချိန်
  toast: '',
  modal: '',
  style: {
    blurEnabled: false,
    blurX: 8, blurY: 72, blurW: 84, blurH: 12, blurStrength: 18,
    logoSize: 16,
    titleText: '', titleX: 8, titleY: 12, titleSize: 34, titleColor: '#ffffff',
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
            <div class="privacy-chip"><span class="status-dot"></span> ${isNativeAndroid ? 'Android native processing' : 'Local browser processing'}</div>
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

      <nav class="mobile-nav"><button class="active">✦<span>Studio</span></button><button>◫<span>Projects</span></button><button>◌<span>Settings</span></button></nav>
    </div>
  `;
  bindEvents();
}

function stageMarkup() {
  if (state.step === 0) return sourceStage();
  if (state.step === 3) return styleStage();
  return renderStage();
}

function sourceStage() {
  const ready = Boolean(state.file || state.demo);
  return `
    <div class="stage-header"><div><div class="stage-kicker">STEP 01 / UPLOAD VIDEO</div><h2>${ready ? 'Video is ready.' : 'Bring your video in.'}</h2><p>${ready ? 'Preview ကြည့်ပြီး Video style ဆီ ဆက်သွားပါ။' : 'ဖုန်းထဲက video file ကို ရွေးပါ။ Cloud upload၊ script ရေးခြင်းနဲ့ အသံထုတ်ခြင်း မလိုပါ။'}</p></div><div class="stage-badge"><span class="badge-dot"></span> ${ready ? 'Local video ready' : 'Local upload'}</div></div>
    ${ready ? `<div class="source-ready"><div class="video-shell source-video-shell">${state.demo ? '<div class="demo-frame"><div class="demo-glow"></div><div class="demo-door">◈</div><span>SAMPLE SCENE</span></div>' : `<video id="source-video" src="${state.videoUrl}" controls playsinline></video>`}<div class="video-corner-tag">SOURCE · VIDEO</div></div><div class="source-details"><div class="file-title"><span class="file-icon">▣</span><div><strong>${escapeHtml(sourceName())}</strong><span>${fileSize(state.metadata?.size)} · ${escapeHtml(state.metadata?.type || 'video')}</span></div><button class="mini-icon" type="button" data-action="pick-video">↻</button></div><div class="ready-callout"><span>✓</span><div><strong>Local video loaded</strong><p>Android native storage ထဲမှာ ဖိုင်ကို ထိန်းပြီး render လုပ်ပါမယ်။</p></div></div><div class="source-stats"><div><span>FORMAT</span><strong>VIDEO</strong></div><div><span>SIZE</span><strong>${fileSize(state.metadata?.size)}</strong></div><div><span>MODE</span><strong>OFFLINE</strong></div></div><div class="privacy-note"><span>◉</span><span>Video ကို server သို့ မပို့ပါ။ ဒီ browser session ထဲမှာပဲ ထိန်းထားပါတယ်။</span></div></div></div><div class="stage-footer"><button class="secondary-button" type="button" data-action="pick-video">Change video</button><button class="primary-button" type="button" data-step="3">Continue to style <span class="button-arrow">→</span></button></div>` : `<div class="dropzone" id="dropzone"><input id="video-input" type="file" accept="video/*" hidden /><div class="upload-orbit"><div class="upload-arrow">↑</div></div><h3>Drop your video here</h3><p>သို့မဟုတ် ဖုန်းထဲက video file ကို ရွေးပါ</p><button class="primary-button" type="button" data-action="pick-video">Choose video <span class="button-arrow">→</span></button><div class="drop-meta"><span>MP4</span><span>MOV</span><span>WEBM</span><i></i><span>${isNativeAndroid ? 'Native storage' : '250 MB max'}</span></div></div><div class="source-feature-row"><div><span class="feature-icon">▣</span><strong>Video upload</strong><small>ဖုန်းထဲက file ကို တိုက်ရိုက်ဖွင့်ပါမယ်။</small></div><div><span class="feature-icon">◌</span><strong>Video style</strong><small>စာတန်း Blur, Logo, Title, crop နဲ့ filter ကို ပြင်ပါ။</small></div><div><span class="feature-icon">↓</span><strong>MP4 render</strong><small>Android Native FFmpeg နဲ့ local render လုပ်ပါမယ်။</small></div></div>`}
    <div class="audio-upload-card"><div><strong>🎧 Dubbing audio (optional)</strong><small>MP3 / WAV ဖိုင်ကို ထည့်နိုင်ပါတယ်။ ထည့်ထားလျှင် မူရင်းအသံအစား ဒီအသံကို အသုံးပြုပါမယ်။</small></div><input id="audio-input" type="file" accept=".mp3,.wav,audio/mpeg,audio/mp3,audio/wav,audio/x-wav" hidden /><button class="outline-button" type="button" data-action="pick-audio">${state.audioFile ? 'Change audio' : 'Choose MP3 / WAV'}</button>${state.audioFile ? `<span class="audio-file-name">${escapeHtml(state.audioFile.name)}</span><button class="text-button danger" type="button" data-action="remove-audio">×</button>` : ''}</div>
    <div class="tip-banner"><span class="tip-icon">i</span><span><strong>Local workflow:</strong> Video နဲ့ MP3/WAV အသံဖိုင်ကို ဖုန်းထဲမှာပဲ ထည့်ပြီး chunk အလိုက် render လုပ်ပါမယ်။</span></div>`;
}

function previewFilter() {
  const preset = state.style.filter === 'vivid' ? 'saturate(1.16) contrast(1.05)' : state.style.filter === 'cinematic' ? 'saturate(0.94) contrast(1.04)' : state.style.filter === 'mono' ? 'grayscale(1) contrast(1.04)' : 'none';
  return `brightness(${100 + state.style.brightness / 2}%) contrast(${100 + state.style.contrast}%) saturate(${100 + state.style.saturation}%) ${preset}`;
}

function applyPreview() {
  const st = state.style;
  const canvas = document.querySelector('.editor-canvas');
  if (!canvas) return;
  canvas.style.border = st.frameWidth ? `${st.frameWidth}px solid ${st.frameColor}` : 'none';
  const blur = canvas.querySelector('.subtitle-blur');
  if (blur) Object.assign(blur.style, { display: st.blurEnabled ? 'block' : 'none', left: `${st.blurX}%`, top: `${st.blurY}%`, width: `${st.blurW}%`, height: `${st.blurH}%`, backdropFilter: `blur(${Math.max(2, st.blurStrength * 0.45)}px)`, WebkitBackdropFilter: `blur(${Math.max(2, st.blurStrength * 0.45)}px)` });
  const logo = canvas.querySelector('.logo-preview');
  if (logo) Object.assign(logo.style, { display: state.logoUrl ? 'block' : 'none', width: `${st.logoSize}%`, right: '3%', top: '3%' });
  const title = canvas.querySelector('.title-preview');
  if (title) { Object.assign(title.style, { display: st.titleText ? 'block' : 'none', fontSize: `${Math.max(10, st.titleSize * 0.45)}px`, color: st.titleColor }); title.style.left = `${Math.max(0, canvas.clientWidth - title.offsetWidth) * st.titleX / 100}px`; title.style.top = `${Math.max(0, canvas.clientHeight - title.offsetHeight) * st.titleY / 100}px`; }
  const media = canvas.querySelector('.editor-video, .editor-demo');
  if (media) {
    media.style.filter = previewFilter();
    media.style.transform = `scale(${st.zoom}) ${st.flip ? 'scaleX(-1)' : ''}`;
    media.style.objectFit = st.fitMode === 'fit' ? 'contain' : 'cover';
    media.style.background = '#080a0b';
    media.style.objectPosition = st.crop === 'left' ? '0% 50%' : st.crop === 'right' ? '100% 50%' : '50% 50%';
  }
}

function styleStage() {
  const st = state.style;
  return `<div class="stage-header compact"><div><div class="stage-kicker">STEP 02 / VIDEO STYLE</div><h2>Make it yours.</h2><p>စာတန်းရှိတဲ့နေရာကိုသာ Blur လုပ်ပါ။ Logo ကို ညာဘက်အပေါ်မှာထားပြီး Title ကို ကိုယ်ကြိုက်တဲ့နေရာ၊ အရွယ်အစား၊ အရောင်နဲ့ ထည့်နိုင်ပါတယ်။</p></div><div class="stage-badge"><span class="badge-dot"></span> Live preview</div></div>
  <div class="style-layout"><div class="editor-canvas-wrap"><div class="canvas-toolbar"><span class="mini-label">${state.isLandscape ? '16:9 LANDSCAPE PREVIEW' : '9:16 SHORT-FORM PREVIEW'}</span><div><button class="canvas-icon ${st.flip ? 'active' : ''}" type="button" data-action="flip">⇆</button><button class="canvas-icon" type="button" data-action="fit">⛶</button></div></div>
  <div class="editor-canvas">${state.demo ? '<div class="demo-frame editor-demo"><div class="demo-glow"></div><div class="demo-door">◈</div><span>SAMPLE SCENE</span></div>' : state.videoUrl ? `<video class="editor-video" src="${state.videoUrl}" controls playsinline muted></video>` : '<div class="empty-preview"><span>▣</span><small>Video ကို အရင်ရွေးပါ</small></div>'}<div class="subtitle-blur"></div>${state.logoUrl ? `<img class="logo-preview" src="${state.logoUrl}" alt="Logo preview" />` : ''}<div class="title-preview">${escapeHtml(st.titleText)}</div></div>
  <div class="canvas-footer"><span><i class="green-dot"></i> Preview synced with render</span><span>${state.resolution} · ${formatTime(state.metadata?.duration)}</span></div></div>
  <div class="controls-panel">
  <div class="control-section"><div class="control-heading"><span>SUBTITLE-ONLY BLUR</span><label class="toggle-control"><input type="checkbox" data-style-check="blurEnabled" ${st.blurEnabled ? 'checked' : ''}/> Enable</label></div><p class="control-sub">မူရင်း video ထဲက စာတန်းရှိတဲ့ ဧရိယာကိုပဲ ဖုံးပါမယ်။ အပေါ်/အောက် တစ်ခုလုံး blur မလုပ်ပါ။</p>
  ${rangeControl('Blur X position','blurX',st.blurX,0,90,'%')} ${rangeControl('Blur Y position','blurY',st.blurY,0,90,'%')} ${rangeControl('Blur width','blurW',st.blurW,5,100,'%')} ${rangeControl('Blur height','blurH',st.blurH,3,40,'%')} ${rangeControl('Blur strength','blurStrength',st.blurStrength,2,40,'')}</div>
  <div class="control-section"><div class="control-heading"><span>BRAND LOGO</span><span class="control-sub">Top right</span></div><input id="logo-input" type="file" accept="image/png,image/jpeg,image/webp,image/*" hidden /><div class="logo-picker-row">${state.logoUrl ? `<img class="logo-thumb" src="${state.logoUrl}" alt="Selected logo" />` : '<span class="logo-thumb-placeholder">LOGO</span>'}<div class="logo-picker-copy"><strong>${state.logoFile ? escapeHtml(state.logoFile.name) : 'Logo မရွေးရသေးပါ'}</strong><small>Video ညာဘက်အပေါ်ထောင့်မှာ ထည့်ပါမယ်။</small></div><button class="outline-button" type="button" data-action="pick-logo">${state.logoFile ? 'Change' : 'Choose logo'}</button>${state.logoFile ? '<button class="text-button danger" type="button" data-action="remove-logo">×</button>' : ''}</div>${rangeControl('Logo size','logoSize',st.logoSize,5,35,'%')}</div>
  <div class="control-section"><div class="control-heading"><span>CUSTOM TITLE</span><span class="control-sub">Text overlay</span></div><label class="range-control title-input-control"><span>Title text</span><textarea data-style-text="titleText" rows="2" maxlength="120" placeholder="ကိုယ်ထည့်ချင်တဲ့ ခေါင်းစဉ်ရေးပါ">${escapeHtml(st.titleText)}</textarea></label>${rangeControl('Title X position','titleX',st.titleX,0,90,'%')} ${rangeControl('Title Y position','titleY',st.titleY,0,90,'%')} ${rangeControl('Title size','titleSize',st.titleSize,16,100,'px')}<div class="color-grid"><label>Title color<input type="color" data-style-color="titleColor" value="${st.titleColor}" /></label></div></div>
  <div class="control-section"><div class="control-heading"><span>FILTER & FRAMING</span><span class="control-sub">Visual treatment</span></div><label class="range-control"><span>Color preset</span><select data-style-filter><option value="natural" ${st.filter==='natural'?'selected':''}>Natural</option><option value="cinematic" ${st.filter==='cinematic'?'selected':''}>Soft cinematic</option><option value="vivid" ${st.filter==='vivid'?'selected':''}>Vivid</option><option value="mono" ${st.filter==='mono'?'selected':''}>Black & white</option></select></label><label class="range-control"><span>Video framing</span><select data-fit-mode><option value="fit" ${st.fitMode==='fit'?'selected':''}>Fit phone frame</option><option value="fill" ${st.fitMode==='fill'?'selected':''}>Fill frame</option></select></label>${rangeControl('Zoom','zoom',st.zoom,1,1.12,'×',0.01)} ${rangeControl('Brightness','brightness',st.brightness,-20,20,'%')} ${rangeControl('Contrast','contrast',st.contrast,-20,20,'%')} ${rangeControl('Saturation','saturation',st.saturation,-20,20,'%')}<div class="crop-buttons"><button class="crop-button ${st.crop==='left'?'active':''}" type="button" data-crop="left">Left</button><button class="crop-button ${st.crop==='center'?'active':''}" type="button" data-crop="center">Center</button><button class="crop-button ${st.crop==='right'?'active':''}" type="button" data-crop="right">Right</button></div><label class="range-control"><span>Output resolution</span><select data-resolution><option value="360p" ${state.resolution==='360p'?'selected':''}>360p</option><option value="720p" ${state.resolution==='720p'?'selected':''}>720p</option><option value="1080p" ${state.resolution==='1080p'?'selected':''}>1080p</option></select></label><div class="color-grid"><label>Frame color<input type="color" data-style-color="frameColor" value="${st.frameColor}" /></label></div>${rangeControl('Frame width','frameWidth',st.frameWidth,0,18,'px')}</div>
  ${!isNativeAndroid ? '<div class="copyright-note">Preview controls only: subtitle-area blur and custom title export are available in the Android APK native renderer. Browser export does not yet apply these two overlays.</div>' : ''}
  <div class="copyright-note">သတိပြုရန် — Blur, Logo, Zoom, Crop သို့မဟုတ် ဘာသာပြန် Voice-over ထည့်ခြင်းက YouTube Copyright Claim ကို အလိုအလျောက် မကာကွယ်နိုင်ပါ။</div>
  </div></div><div class="stage-footer"><button class="secondary-button" type="button" data-step="0">← Change video</button><button class="primary-button render-button" type="button" data-step="4"><span class="button-spark">✦</span> Continue to render <span class="button-arrow">→</span></button></div>`;
}

function rangeControl(label, key, value, min, max, suffix, step = 1) {
  const display = key === 'zoom' ? Number(value).toFixed(2) : Math.round(value);
  return `<label class="range-control"><span>${label}</span><input type="range" data-style="${key}" min="${min}" max="${max}" step="${step}" value="${value}" /><output data-suffix="${suffix}">${display}${suffix}</output></label>`;
}

function renderStage() {
  const complete = state.renderStatus === 'complete'; const working = state.renderStatus === 'working';
  return `<div class="stage-header compact"><div><div class="stage-kicker">STEP 03 / RENDER</div><h2>${complete ? 'Your video is ready.' : 'Ready for the final render?'}</h2><p>${complete ? 'Preview ပြီး download လုပ်နိုင်ပါပြီ။' : '5 မိနစ်စီ Part ခွဲပြီး Local MP4 အဖြစ် Render / Merge လုပ်ပါမယ်။'}</p></div><div class="stage-badge ${complete ? 'success' : ''}"><span class="badge-dot"></span> ${complete ? 'Export complete' : working ? 'Rendering locally' : 'Review ready'}</div></div>${complete ? `<div class="result-layout"><div class="result-video-shell video-shell">${state.outputUrl ? `<video src="${state.outputUrl}" controls playsinline></video>` : '<div class="render-placeholder"><span>✓</span></div>'}<div class="video-corner-tag">FINAL · MP4</div></div><div class="result-details"><div class="result-kicker">FINAL VIDEO</div><h3>${escapeHtml(sourceName().replace(/\.[^/.]+$/, ''))}</h3><p>Local render · Style applied · ${state.audioFile ? 'Uploaded dubbing audio' : 'Original audio preserved'}</p><div class="preview-before-download">▶ Download မလုပ်ခင် preview ကို အရင်ကြည့်ပါ။</div><div class="result-meta"><span><b>${state.isLandscape ? '16:9' : '9:16'}</b> format</span><span><b>${state.resolution}</b> export</span><span><b>MP4</b> export</span></div>${state.nativeSavedUri ? '<div class="ready-callout"><span>✓</span><div><strong>ဖုန်းထဲ သိမ်းပြီးပါပြီ</strong><p>Movies / SiGyi Recap Studio folder ထဲမှာ MP4 ရှိပါတယ်။</p></div></div>' : `<a class="primary-button wide download-button" href="${state.outputUrl || '#'}" download="sigyi-video.mp4"><span>↓</span> Download MP4 <span class="button-arrow">→</span></a>`}<button class="outline-button wide" type="button" data-action="reset">＋ Start another video</button></div></div>` : `<div class="render-review"><div class="review-card"><div class="review-icon">✦</div><div><strong>Local video render</strong><p>Video ကို 5 မိနစ်စီ Part ခွဲ Render လုပ်ပြီး နောက်ဆုံး MP4 တစ်ဖိုင်အဖြစ် Merge လုပ်ပါမယ်။</p></div><span class="review-check">✓</span></div><div class="review-list"><div><span class="review-thumb">▣</span><span><strong>Source video</strong><small>${escapeHtml(sourceName())}</small></span><b>✓</b></div><div><span class="review-thumb blue">◌</span><span><strong>Visual treatment</strong><small>${isNativeAndroid ? 'Subtitle mask, logo, title, crop, zoom, color, frame' : 'Subtitle mask, logo, title, crop, filter, frame'}</small></span><b>✓</b></div><div><span class="review-thumb coral">▣</span><span><strong>Original audio</strong><small>${state.audioFile ? 'Uploaded MP3/WAV dubbing audio' : 'Original video audio'}</small></span><b>✓</b></div></div>${working ? `<div class="render-progress-card"><div class="progress-head"><span id="render-stage">${escapeHtml(state.renderStage || (isNativeAndroid ? 'Native FFmpeg rendering' : 'Rendering with ffmpeg.wasm'))}</span><strong id="render-pct">${Math.round(state.renderProgress)}%</strong></div><div class="big-progress"><i id="render-bar" style="width:${state.renderProgress}%"></i></div><p style="font-size:14px; color:#666; margin-top:10px;">⏱️ ကြာချိန်: ${state.elapsedTime} | ⏳ ခန့်မှန်း: ${state.remainingTime}</p><p>ဖုန်းကို lock မလုပ်ပါနဲ့။ Render ပြီးတဲ့အထိ ဒီ tab ကို ဖွင့်ထားပါ။</p><button class="text-button danger" type="button" data-action="cancel-render">Cancel render</button></div>` : `<button class="primary-button render-button" type="button" data-action="render"><span class="button-spark">✦</span> Render MP4 <span class="button-arrow">→</span></button><div class="render-warning"><span>i</span><span>5 မိနစ်စီ Part Render + Merge လုပ်ပါမယ်။ ${isNativeAndroid ? 'Native renderer က part တစ်ခုချင်းစီကို disk ပေါ်ရေးပြီး merge လုပ်ပါမယ်။ Storage လွတ်နေရာ လုံလောက်အောင်ထားပါ။' : 'Browser memory ကန့်သတ်ချက်ကြောင့် 1 နာရီစာ Video ကို browser တိုင်းမှာ အာမခံမရပါ။ 360p နဲ့ အရင်စမ်းပါ။'}</span></div>`}</div>`}${state.renderError ? `<div class="inline-error large">${escapeHtml(state.renderError)}</div>` : ''}<div class="stage-footer"><button class="secondary-button" type="button" data-step="3">← Back to style</button><span class="footer-hint">Local-only · No account required</span></div>`;
}

function railMarkup() {
  return `<div class="rail-card workflow-card"><div class="rail-heading"><span>YOUR WORKFLOW</span><span class="rail-percent">${state.step === 0 ? 0 : state.step === 3 ? 50 : 100}%</span></div><div class="workflow-list">${steps.map((step) => `<button type="button" class="workflow-row ${state.step === step.id ? 'current' : ''} ${state.step > step.id ? 'complete' : ''}" data-step="${step.id}"><span class="workflow-bullet">${state.step > step.id ? '✓' : step.id === 0 ? '01' : step.id === 3 ? '02' : '03'}</span><span><strong>${step.en}</strong><small>${step.id === 0 ? 'Choose a local video file' : step.id === 3 ? 'Blur, crop & adjust' : 'Export MP4 locally'}</small></span><i>${state.step > step.id ? 'done' : state.step === step.id ? 'now' : ''}</i></button>`).join('')}</div></div><div class="rail-card key-card"><div class="key-card-top"><div class="key-orb">◉</div><div><strong>Local video mode</strong><small>Upload / Style / Render</small></div></div><p>Script ရေးခြင်းနဲ့ အသံထုတ်ခြင်း မပါဘဲ video upload, style နဲ့ render ကိုသာ အသုံးပြုပါမယ်။</p></div><div class="rail-card support-card"><span class="support-mark">◎</span><div><strong>Quick start</strong><p>Short clip တစ်ခုနဲ့ အရင်စမ်းပြီး render speed ကို စစ်ပါ။</p></div></div>`;
}

function bindEvents() {
  document.querySelectorAll('[data-step]').forEach((button) => button.addEventListener('click', () => { const next = Number(button.dataset.step); if (next > 0 && !state.file && !state.demo) return setToast('အရင်ဆုံး video file တစ်ခု ရွေးပါ။'); state.step = next; render(); }));
  document.querySelectorAll('[data-action]').forEach((button) => button.addEventListener('click', () => handleAction(button.dataset.action)));
  document.querySelectorAll('[data-crop]').forEach((button) => button.addEventListener('click', () => { state.style.crop = button.dataset.crop; render(); }));
  document.querySelectorAll('[data-style]').forEach((input) => input.addEventListener('input', () => { const key = input.dataset.style; state.style[key] = Number(input.value); const out = input.parentElement.querySelector('output'); if (out) out.textContent = `${key === 'zoom' ? Number(input.value).toFixed(2) : Math.round(input.value)}${out.dataset.suffix || ''}`; applyPreview(); }));
  document.querySelectorAll('[data-style-color]').forEach((input) => input.addEventListener('input', () => { state.style[input.dataset.styleColor] = input.value; applyPreview(); }));
  document.querySelectorAll('[data-style-check]').forEach((input) => input.addEventListener('change', () => { state.style[input.dataset.styleCheck] = input.checked; applyPreview(); }));
  document.querySelectorAll('[data-style-text]').forEach((input) => input.addEventListener('input', () => { state.style[input.dataset.styleText] = input.value; applyPreview(); }));
  document.querySelectorAll('[data-style-filter]').forEach((input) => input.addEventListener('change', () => { state.style.filter = input.value; applyPreview(); }));
  document.querySelectorAll('[data-fit-mode]').forEach((input) => input.addEventListener('change', () => { state.style.fitMode = input.value; applyPreview(); }));
  document.querySelectorAll('[data-resolution]').forEach((input) => input.addEventListener('change', () => { state.resolution = input.value; setToast(`${state.resolution} resolution ကို ရွေးထားပါတယ်။`); }));
  
  // ✅ Ratio ရွေးချယ်မှု (16:9 / 9:16)
  document.querySelectorAll('[data-ratio]').forEach((input) => input.addEventListener('change', () => { 
      state.isLandscape = input.value === '16:9'; 
      setToast(state.isLandscape ? '16:9 (အလျားလိုက်) ရွေးထားပါတယ်' : '9:16 (ဒေါင်လိုက်) ရွေးထားပါတယ်'); 
      render(); 
  }));

  const input = document.querySelector('#video-input'); if (input) input.addEventListener('change', (event) => loadVideo(event.target.files?.[0]));
  const audioInput = document.querySelector('#audio-input');
  if (audioInput) audioInput.addEventListener('change', (event) => loadAudio(event.target.files?.[0]));
  const logoInput = document.querySelector('#logo-input'); if (logoInput) logoInput.addEventListener('change', (event) => { const file = event.target.files?.[0]; if (!file || !file.type.startsWith('image/')) return setToast('Logo အတွက် image ဖိုင် ရွေးပေးပါ။'); if (state.logoUrl) URL.revokeObjectURL(state.logoUrl); state.logoFile = file; state.logoUrl = URL.createObjectURL(file); state.logoNativePath = null; render(); });
  
  const dropzone = document.querySelector('#dropzone'); if (dropzone) { dropzone.addEventListener('dragover', (event) => { event.preventDefault(); dropzone.classList.add('dragging'); }); dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragging')); dropzone.addEventListener('drop', (event) => { event.preventDefault(); dropzone.classList.remove('dragging'); loadVideo(event.dataTransfer.files?.[0]); }); }
  if (state.step === 3) applyPreview();
  const video = document.querySelector('#source-video'); if (video) video.addEventListener('loadedmetadata', () => { state.metadata.duration = video.duration; });
}

async function handleAction(action) {
  if (action === 'pick-video') return isNativeAndroid ? pickNativeVideo() : document.querySelector('#video-input')?.click();
  if (action === 'pick-audio') return isNativeAndroid ? pickNativeAudio() : document.querySelector('#audio-input')?.click();
  if (action === 'remove-audio') { state.audioFile = null; if (state.audioUrl) URL.revokeObjectURL(state.audioUrl); state.audioUrl = ''; return render(); }
  if (action === 'pick-logo') return isNativeAndroid ? pickNativeLogo() : document.querySelector('#logo-input')?.click();
  if (action === 'remove-logo') { if (state.logoUrl) URL.revokeObjectURL(state.logoUrl); state.logoFile = null; state.logoUrl = ''; state.logoNativePath = null; return render(); }
  if (action === 'load-demo') return loadDemo();
  if (action === 'reset') return resetStudio();
  if (action === 'flip') { state.style.flip = !state.style.flip; return render(); }
  if (action === 'fit') { state.style.zoom = 1; state.style.flip = false; return render(); }
  if (action === 'reset-blur') { Object.assign(state.style, { blurEnabled: false, blurX: 8, blurY: 72, blurW: 84, blurH: 12, blurStrength: 18 }); return render(); }
  if (action === 'render') { state.step = 4; return renderVideo(); }
  if (action === 'cancel-render') return cancelRender();
}

async function pickNativeVideo() {
  try {
    const picked = await NativeRender.pickVideo();
    if (state.videoUrl) URL.revokeObjectURL(state.videoUrl);
    state.file = { nativePath: picked.path, name: picked.name, size: picked.size, type: picked.mimeType || 'video/mp4' };
    state.demo = false;
    state.videoUrl = Capacitor.convertFileSrc(picked.path);
    state.metadata = { name: picked.name, size: picked.size, type: picked.mimeType || 'video/mp4', duration: 0 };
    state.step = 0; render();
  } catch (error) { if (!String(error?.message || error).toLowerCase().includes('cancel')) setToast(error?.message || 'Video ရွေးမရပါ။'); }
}
async function pickNativeAudio() {
  try {
    const picked = await NativeRender.pickAudio();
    if (state.audioUrl) URL.revokeObjectURL(state.audioUrl);
    state.audioFile = { nativePath: picked.path, name: picked.name, size: picked.size, type: picked.mimeType || 'audio/mpeg' };
    state.audioUrl = ''; render();
  } catch (error) { if (!String(error?.message || error).toLowerCase().includes('cancel')) setToast(error?.message || 'Audio ရွေးမရပါ။'); }
}

async function pickNativeLogo() {
  try {
    const picked = await NativeRender.pickLogo();
    if (state.logoUrl) URL.revokeObjectURL(state.logoUrl);
    state.logoNativePath = picked.path;
    state.logoFile = { name: picked.name, type: picked.mimeType || 'image/png' };
    state.logoUrl = Capacitor.convertFileSrc(picked.path);
    render();
  } catch (error) { if (!String(error?.message || error).includes('ပယ်ဖျက်')) setToast(error?.message || 'Logo ကို ဖတ်မရပါ။'); }
}

function loadVideo(file) {
  if (!file) return;
  if (!file.type.startsWith('video/')) return setToast('Video file ပဲ ရွေးပေးပါ။');
  if (state.videoUrl) URL.revokeObjectURL(state.videoUrl);
  state.file = file;
  state.demo = false;
  state.videoUrl = URL.createObjectURL(file);
  state.metadata = { name: file.name, size: file.size, type: file.type, duration: 0 };
  state.step = 0;
  render();
  const video = document.querySelector('#source-video');
  if (video) video.addEventListener('loadedmetadata', () => { state.metadata.duration = video.duration; }, { once: true });
}

function loadAudio(file) {
  if (!file) return;
  const valid = /\.(mp3|wav)$/i.test(file.name) || ['audio/mpeg','audio/mp3','audio/wav','audio/x-wav'].includes(file.type);
  if (!valid) return setToast('MP3 သို့မဟုတ် WAV ဖိုင်ပဲ ရွေးပါ။');
  if (state.audioUrl) URL.revokeObjectURL(state.audioUrl);
  state.audioFile = file;
  state.audioUrl = URL.createObjectURL(file);
  render();
}

function loadDemo() {
  if (state.videoUrl) URL.revokeObjectURL(state.videoUrl);
  if (state.audioUrl) URL.revokeObjectURL(state.audioUrl);
  state.file = null;
  state.audioFile = null;
  state.audioUrl = '';
  state.videoUrl = '';
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

function setRenderUI(stage, pct, elapsed = null, remaining = null) {
  if (stage) state.renderStage = stage;
  if (pct != null) state.renderProgress = Math.max(state.renderProgress, pct);
  if (elapsed) state.elapsedTime = elapsed;
  if (remaining) state.remainingTime = remaining;
  
  setText('#render-stage', state.renderStage);
  setText('#render-pct', `${Math.round(state.renderProgress)}%`);
  const bar = document.querySelector('#render-bar');
  if (bar) bar.style.width = `${state.renderProgress}%`;
  
  // ✅ အချိန်နဲ့ ခန့်မှန်းချိန်ကို Update လုပ်မယ်
  const timeEl = document.querySelector('.render-time-info');
  if (timeEl) timeEl.innerHTML = `⏱️ ကြာချိန်: ${state.elapsedTime} | ⏳ ခန့်မှန်း: ${state.remainingTime}`;

  if (state.renderStatus === 'working') sendTermuxRenderLog(stage, pct);
}

async function renderVideo() {
  if (!state.file) { state.renderError = 'ကိုယ့် video file တစ်ခု upload လုပ်ပြီးမှ render လုပ်ပါ။'; state.step = 0; return render(); }
  state.renderStatus = 'working'; state.renderProgress = 2; state.renderStage = 'ပြင်ဆင်နေပါတယ်…'; state.renderError = ''; if (state.outputUrl) { URL.revokeObjectURL(state.outputUrl); state.outputUrl = ''; } render();
  try {
    if (isNativeAndroid) {
      // ✅ Native ကနေ ပို့လိုက်တဲ့ အချိန်၊ ခန့်မှန်းချိန်တွေကို ဖမ်းယူမယ်
      const listener = await NativeRender.addListener('renderProgress', (event) => setRenderUI(event.stage, event.progress, event.elapsed, event.remaining));
      try {
        const result = await NativeRender.render({ 
            videoPath: state.file.nativePath, 
            audioPath: state.audioFile?.nativePath || null, 
            style: state.style, 
            resolution: state.resolution, 
            landscape: state.isLandscape, // ✅ 16:9 ဟုတ်မဟုတ် ပို့မယ်
            logoPath: state.logoNativePath || null 
        });
        state.outputUrl = Capacitor.convertFileSrc(result.previewPath); state.renderProgress = 100; state.renderStatus = 'complete'; state.renderStage = 'ပြီးပါပြီ — Movies/SiGyi Recap Studio ထဲ သိမ်းပြီးပါပြီ'; state.nativeSavedUri = result.savedUri;
      } finally { await listener.remove(); }
    } else {
      const blob = await renderRecap({ file: state.file, segments: [], timeline: [], voiceBlob: state.audioFile, style: state.style, captionsOn: false, resolution: state.resolution, logoFile: state.logoFile, logoPosition: state.logoPosition, onStage: (stage) => setRenderUI(stage), onProgress: (pct) => setRenderUI(null, pct) });
      state.outputUrl = URL.createObjectURL(blob); state.renderProgress = 100; state.renderStatus = 'complete'; state.renderStage = 'ပြီးပါပြီ — video ready';
    }
    render();
  } catch (error) { if (state.renderStatus === 'cancelled') return; state.renderStatus = 'error'; const detail = error?.message || String(error); state.renderError = `${detail}\n\nဒီအမှားစာသားကို Copy လုပ်ပြီး ပို့ပေးပါ။`; console.error('[SiGyi Render Diagnostic]', { at: new Date().toISOString(), stage: state.renderStage, progress: state.renderProgress, error: detail, userAgent: navigator.userAgent, ffmpegLogs: getRenderLogs() }, error); try { await fetch('/api/render-log', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: detail, stage: state.renderStage, progress: state.renderProgress }) }); } catch {} terminateFfmpeg(); render(); }
}

function cancelRender() {
  if (isNativeAndroid) NativeRender.cancel().catch(() => {});
  terminateFfmpeg();
  state.renderStatus = 'cancelled';
  state.renderProgress = 0;
  state.renderStage = '';
  state.renderError = 'Render ကို ရပ်လိုက်ပါပြီ။ ပြန်စရန် Render button ကို နှိပ်ပါ။';
  render();
}

function resetStudio() {
  if (state.videoUrl) URL.revokeObjectURL(state.videoUrl);
  if (state.audioUrl) URL.revokeObjectURL(state.audioUrl);
  if (state.logoUrl) URL.revokeObjectURL(state.logoUrl);
  if (state.outputUrl) URL.revokeObjectURL(state.outputUrl);
  state.step = 0;
  state.file = null;
  state.audioFile = null;
  state.audioUrl = '';
  state.videoUrl = '';
  state.demo = false;
  state.metadata = null;
  state.renderStatus = 'idle';
  state.renderProgress = 0;
  state.renderStage = '';
  state.renderError = '';
  state.outputUrl = '';
  state.nativeSavedUri = null;
  state.logoFile = null;
  state.logoUrl = '';
  state.logoNativePath = null;
  state.logoPosition = 'top-right';
  state.captionsOn = false;
  state.isLandscape = false; // ✅ Reset လုပ်ရင် 9:16 ပြန်ဖြစ်မယ်
  state.style = { blurEnabled: false, blurX: 8, blurY: 72, blurW: 84, blurH: 12, blurStrength: 18, logoSize: 16, titleText: '', titleX: 8, titleY: 12, titleSize: 34, titleColor: '#ffffff', flip: false, zoom: 1.02, fitMode: 'fit', brightness: 2, contrast: 5, saturation: 7, crop: 'center', frameColor: '#d6ef47', frameWidth: 0, filter: 'cinematic' };
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
