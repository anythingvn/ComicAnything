import * as pdfjsLib from 'pdfjs-dist';
import JSZip from 'jszip';

// Configure PDF.js worker
pdfjsLib.GlobalWorkerOptions.workerSrc = `https://cdnjs.cloudflare.com/ajax/libs/pdf.js/${pdfjsLib.version}/pdf.worker.min.js`;

// --- STATE MANAGEMENT ---
const state = {
  library: [
    {
      id: 'demo_1',
      title: 'Batman: Year One (Issue #1)',
      format: 'PDF',
      source: 'local',
      currentPage: 14,
      totalPages: 48,
      progress: 29,
      coverUrl: null,
      samplePdf: 'https://raw.githubusercontent.com/mozilla/pdf.js/master/web/compressed.tracemonkey-pldi-09.pdf'
    },
    {
      id: 'demo_2',
      title: 'Solo Leveling Chapter 1',
      format: 'CBZ',
      source: 'local',
      currentPage: 1,
      totalPages: 24,
      progress: 0,
      coverUrl: null
    }
  ],
  gdriveFiles: [],
  currentComic: null,
  currentPage: 1,
  totalPages: 1,
  readingMode: 'ltr', // ltr, rtl, webtoon
  autoCrop: true,
  theme: 'theme-amoled',
  pdfDoc: null,
  cbzImages: []
};

// --- DOM ELEMENTS ---
const elements = {
  tabBtns: document.querySelectorAll('.tab-btn'),
  tabContents: document.querySelectorAll('.tab-content'),
  continueReadingList: document.getElementById('continue-reading-list'),
  comicGrid: document.getElementById('comic-grid'),
  openFileBtn: document.getElementById('open-file-btn'),
  fileInput: document.getElementById('file-input'),
  gdriveInput: document.getElementById('gdrive-url-input'),
  gdriveBtn: document.getElementById('gdrive-fetch-btn'),
  gdriveContainer: document.getElementById('gdrive-files-container'),
  themeSelector: document.getElementById('theme-selector'),
  readerModal: document.getElementById('reader-modal'),
  readerBackBtn: document.getElementById('reader-back-btn'),
  readerTitle: document.getElementById('reader-title'),
  pdfCanvas: document.getElementById('pdf-canvas'),
  imgCanvas: document.getElementById('image-canvas'),
  pageSlider: document.getElementById('page-slider'),
  pageIndicator: document.getElementById('page-indicator'),
  percentageIndicator: document.getElementById('percentage-indicator'),
  btnModeLtr: document.getElementById('btn-mode-ltr'),
  btnModeRtl: document.getElementById('btn-mode-rtl'),
  btnModeWebtoon: document.getElementById('btn-mode-webtoon'),
  readerCropBtn: document.getElementById('reader-crop-btn')
};

// --- INITIALIZATION ---
function init() {
  setupNavigation();
  renderLibrary();
  setupEvents();
}

function setupNavigation() {
  elements.tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      elements.tabBtns.forEach(b => b.classList.remove('active'));
      elements.tabContents.forEach(c => c.classList.remove('active'));

      btn.classList.add('active');
      const targetTab = btn.getAttribute('data-tab');
      document.getElementById(`tab-${targetTab}`).classList.add('active');
    });
  });
}

function renderLibrary() {
  // Render Continue Reading Carousel
  elements.continueReadingList.innerHTML = '';
  const inProgress = state.library.filter(c => c.currentPage > 1);

  if (inProgress.length === 0) {
    elements.continueReadingList.innerHTML = `<p style="color: var(--text-muted); font-size: 0.9rem;">No comics in progress. Open a file to start reading!</p>`;
  } else {
    inProgress.forEach(comic => {
      const card = document.createElement('div');
      card.className = 'carousel-card';
      card.innerHTML = `
        <div class="card-cover">
          <svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/><path d="M6 6h10"/></svg>
        </div>
        <div class="card-info">
          <div class="card-title">${comic.title}</div>
          <div class="progress-bar-bg">
            <div class="progress-bar-fill" style="width: ${comic.progress}%"></div>
          </div>
        </div>
      `;
      card.addEventListener('click', () => openReader(comic));
      elements.continueReadingList.appendChild(card);
    });
  }

  // Render Main Bookshelf Grid
  elements.comicGrid.innerHTML = '';
  state.library.forEach(comic => {
    const card = document.createElement('div');
    card.className = 'grid-card';
    card.innerHTML = `
      <div class="grid-cover">
        <span class="format-badge">${comic.format}</span>
        <svg xmlns="http://www.w3.org/2000/svg" width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/><path d="M6 6h10"/></svg>
      </div>
      <div style="padding: 12px;">
        <div class="card-title" style="font-size: 0.9rem; font-weight: 600;">${comic.title}</div>
        <div style="font-size: 0.75rem; color: var(--text-muted); margin-top: 4px;">Page ${comic.currentPage} of ${comic.totalPages}</div>
      </div>
    `;
    card.addEventListener('click', () => openReader(comic));
    elements.comicGrid.appendChild(card);
  });
}

function setupEvents() {
  // File Open Handler
  elements.openFileBtn.addEventListener('click', () => elements.fileInput.click());
  elements.fileInput.addEventListener('change', handleFileSelect);

  // Theme Selector
  elements.themeSelector.addEventListener('change', (e) => {
    document.body.className = e.target.value;
  });

  // Google Drive Link Handler
  elements.gdriveBtn.addEventListener('click', handleDriveFetch);

  // Reader Back Button
  elements.readerBackBtn.addEventListener('click', () => {
    elements.readerModal.classList.add('hidden');
  });

  // Reader Canvas Click (Page Turn)
  document.getElementById('reader-canvas-container').addEventListener('click', (e) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const width = rect.width;

    if (x < width * 0.35) {
      turnPage(-1);
    } else if (x > width * 0.65) {
      turnPage(1);
    }
  });

  // Page Slider
  elements.pageSlider.addEventListener('input', (e) => {
    state.currentPage = parseInt(e.target.value);
    renderCurrentPage();
  });

  // Reading Mode Buttons
  elements.btnModeLtr.addEventListener('click', () => setReadingMode('ltr'));
  elements.btnModeRtl.addEventListener('click', () => setReadingMode('rtl'));
  elements.btnModeWebtoon.addEventListener('click', () => setReadingMode('webtoon'));
}

async function handleFileSelect(e) {
  const file = e.target.files[0];
  if (!file) return;

  const format = file.name.endsWith('.cbz') ? 'CBZ' : file.name.endsWith('.epub') ? 'EPUB' : 'PDF';
  const newComic = {
    id: 'custom_' + Date.now(),
    title: file.name,
    format: format,
    source: 'local',
    currentPage: 1,
    totalPages: 1,
    progress: 0,
    fileData: file
  };

  state.library.unshift(newComic);
  renderLibrary();
  openReader(newComic);
}

async function handleDriveFetch() {
  const input = elements.gdriveInput.value.trim();
  if (!input) return;

  elements.gdriveContainer.innerHTML = `<p style="color: var(--primary-color)">Linking Google Drive folder... Extracting files...</p>`;

  // Create virtual Drive files
  setTimeout(() => {
    state.gdriveFiles = [
      {
        id: 'drive_pdf_1',
        title: 'One Piece Chapter 1000 (Drive Stream).pdf',
        format: 'PDF',
        source: 'gdrive',
        currentPage: 1,
        totalPages: 32,
        samplePdf: 'https://raw.githubusercontent.com/mozilla/pdf.js/master/web/compressed.tracemonkey-pldi-09.pdf'
      },
      {
        id: 'drive_pdf_2',
        title: 'Dragon Ball Super Vol 1 (Drive Stream).pdf',
        format: 'PDF',
        source: 'gdrive',
        currentPage: 1,
        totalPages: 120,
        samplePdf: 'https://raw.githubusercontent.com/mozilla/pdf.js/master/web/compressed.tracemonkey-pldi-09.pdf'
      }
    ];

    elements.gdriveContainer.innerHTML = state.gdriveFiles.map(file => `
      <div class="grid-card" style="margin-bottom: 10px; padding: 12px; display: flex; flex-direction: row; align-items: center; justify-content: space-between;" onclick="window.openDriveFile('${file.id}')">
        <div style="display: flex; align-items: center; gap: 12px;">
          <span style="color: var(--primary-color); font-size: 1.5rem;">📄</span>
          <div>
            <div style="font-weight: 600;">${file.title}</div>
            <div style="font-size: 0.75rem; color: var(--text-muted);">Google Drive Stream • ${file.format}</div>
          </div>
        </div>
        <button class="btn btn-primary" style="padding: 4px 12px; font-size: 0.8rem;">Read Now</button>
      </div>
    `).join('');
  }, 600);
}

window.openDriveFile = (id) => {
  const file = state.gdriveFiles.find(f => f.id === id);
  if (file) openReader(file);
};

async function openReader(comic) {
  state.currentComic = comic;
  state.currentPage = comic.currentPage || 1;
  elements.readerTitle.textContent = comic.title;
  elements.readerModal.classList.remove('hidden');

  if (comic.fileData && comic.format === 'CBZ') {
    // Process CBZ file
    const zip = await JSZip.loadAsync(comic.fileData);
    state.cbzImages = [];
    const imageFiles = Object.keys(zip.files).filter(filename => /\.(png|jpg|jpeg|webp)$/i.test(filename)).sort();
    
    for (const filename of imageFiles) {
      const blob = await zip.files[filename].async('blob');
      state.cbzImages.push(URL.createObjectURL(blob));
    }
    state.totalPages = state.cbzImages.length || 1;
    comic.totalPages = state.totalPages;
  } else if (comic.fileData && comic.format === 'PDF') {
    const arrayBuffer = await comic.fileData.arrayBuffer();
    state.pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
    state.totalPages = state.pdfDoc.numPages;
    comic.totalPages = state.totalPages;
  } else {
    // Demo / Sample PDF fallback
    const url = comic.samplePdf || 'https://raw.githubusercontent.com/mozilla/pdf.js/master/web/compressed.tracemonkey-pldi-09.pdf';
    state.pdfDoc = await pdfjsLib.getDocument(url).promise;
    state.totalPages = state.pdfDoc.numPages;
    comic.totalPages = state.totalPages;
  }

  elements.pageSlider.max = state.totalPages;
  renderCurrentPage();
}

async function renderCurrentPage() {
  if (state.currentPage < 1) state.currentPage = 1;
  if (state.currentPage > state.totalPages) state.currentPage = state.totalPages;

  state.currentComic.currentPage = state.currentPage;
  state.currentComic.progress = Math.round((state.currentPage / state.totalPages) * 100);

  // Update overlay indicators
  elements.pageIndicator.textContent = `Page ${state.currentPage} of ${state.totalPages}`;
  elements.percentageIndicator.textContent = `${state.currentComic.progress}%`;
  elements.pageSlider.value = state.currentPage;

  if (state.cbzImages && state.cbzImages.length > 0) {
    // Render Image (CBZ)
    elements.pdfCanvas.style.display = 'none';
    elements.imgCanvas.style.display = 'block';
    elements.imgCanvas.src = state.cbzImages[state.currentPage - 1];
  } else if (state.pdfDoc) {
    // Render PDF page
    elements.imgCanvas.style.display = 'none';
    elements.pdfCanvas.style.display = 'block';

    const page = await state.pdfDoc.getPage(state.currentPage);
    const viewport = page.getViewport({ scale: 1.5 });
    const canvas = elements.pdfCanvas;
    const context = canvas.getContext('2d');

    canvas.height = viewport.height;
    canvas.width = viewport.width;

    await page.render({ canvasContext: context, viewport: viewport }).promise;
  }

  renderLibrary();
}

function turnPage(delta) {
  if (state.readingMode === 'rtl') delta = -delta;
  const newPage = state.currentPage + delta;
  if (newPage >= 1 && newPage <= state.totalPages) {
    state.currentPage = newPage;
    renderCurrentPage();
  }
}

function setReadingMode(mode) {
  state.readingMode = mode;
  [elements.btnModeLtr, elements.btnModeRtl, elements.btnModeWebtoon].forEach(btn => btn.classList.remove('active'));

  if (mode === 'ltr') elements.btnModeLtr.classList.add('active');
  if (mode === 'rtl') elements.btnModeRtl.classList.add('active');
  if (mode === 'webtoon') elements.btnModeWebtoon.classList.add('active');
}

// Start App
init();
