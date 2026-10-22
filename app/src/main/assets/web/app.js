// Cast16x9 TV Web Receiver Logic v1.4.0 (Zero Lag & Full Audio Engine)
(function () {
    'use strict';

    // State
    let activeTab = 'mirror';
    let aspectMode = 'cover'; // 'cover' (16:9 zoom no bars), 'stretch' (16:9 fill no bars), 'fit' (contain)
    let ws = null;
    let reconnectTimer = null;
    let pingInterval = null;
    let statusSyncInterval = null;
    let hudTimer = null;
    let isMirroringActive = false;
    let isFramePending = false;
    let wsStreaming = false;
    let httpPollingActive = false;
    let currentBlobUrl = null;

    // DOM Elements
    const statusBadge = document.getElementById('connection-status');
    const mirrorWrapper = document.getElementById('mirror-wrapper');
    const screenStream = document.getElementById('screen-stream');
    const mirrorPlaceholder = document.getElementById('mirror-placeholder');
    const activeModeName = document.getElementById('active-mode-name');
    const hudOverlay = document.getElementById('hud-overlay');
    const hudText = document.getElementById('hud-text');

    const videoPlayer = document.getElementById('tv-video-player');
    const photoViewer = document.getElementById('photo-viewer');
    const photoDisplay = document.getElementById('tv-photo-display');
    const mediaPlaceholder = document.getElementById('media-placeholder');
    const unmuteBanner = document.getElementById('unmute-banner');
    const btnUnmute = document.getElementById('btn-unmute');

    // 4K Fast TV Database Caching Elements
    const cachingHud = document.getElementById('caching-hud');
    const cachingProgressBar = document.getElementById('caching-progress-bar');
    const cachingStats = document.getElementById('caching-stats');
    const btnPlayLive = document.getElementById('btn-play-live');

    const filesGrid = document.getElementById('files-grid');
    const btnRefreshFiles = document.getElementById('btn-refresh-files');

    const tabs = {
        mirror: { btn: document.getElementById('tab-mirror'), section: document.getElementById('section-mirror') },
        media: { btn: document.getElementById('tab-media'), section: document.getElementById('section-media') },
        files: { btn: document.getElementById('tab-files'), section: document.getElementById('section-files') },
        settings: { btn: document.getElementById('tab-settings'), section: document.getElementById('section-settings') }
    };

    const modeButtons = {
        cover: document.getElementById('btn-mode-cover'),
        stretch: document.getElementById('btn-mode-stretch'),
        fit: document.getElementById('btn-mode-fit')
    };

    // 1. Navigation / Tab Switching
    function switchTab(tabKey) {
        if (!tabs[tabKey]) return;
        activeTab = tabKey;

        Object.keys(tabs).forEach(key => {
            const isTarget = (key === tabKey);
            tabs[key].btn.classList.toggle('active', isTarget);
            tabs[key].section.classList.toggle('active', isTarget);
        });

        if (tabKey === 'files') {
            loadSharedFiles();
        }
    }

    Object.keys(tabs).forEach(key => {
        tabs[key].btn.addEventListener('click', () => switchTab(key));
    });

    // 2. Aspect Ratio Transformation (16:9 Zero Black Bars)
    function setAspectMode(mode, showNotification = true) {
        aspectMode = mode;
        mirrorWrapper.className = `aspect-${mode}`;
        screenStream.className = `aspect-${mode}`;
        videoPlayer.className = `aspect-${mode}`;

        Object.keys(modeButtons).forEach(m => {
            modeButtons[m].classList.toggle('active', m === mode);
        });

        const descriptions = {
            cover: '16:9 Cover / Zoom (No Black Bars, Original Proportions)',
            stretch: '16:9 Stretch / Fill (No Black Bars, Edge-to-Edge)',
            fit: 'Original Aspect Ratio (With Black Bars)'
        };

        if (activeModeName) {
            activeModeName.textContent = descriptions[mode] || mode;
        }

        if (showNotification) {
            showHUD(`Aspect Ratio: ${descriptions[mode] || mode}`);
        }
    }

    function showHUD(message) {
        hudText.textContent = message;
        hudOverlay.classList.remove('hidden');
        clearTimeout(hudTimer);
        hudTimer = setTimeout(() => {
            hudOverlay.classList.add('hidden');
        }, 3000);
    }

    Object.keys(modeButtons).forEach(mode => {
        modeButtons[mode].addEventListener('click', () => setAspectMode(mode));
    });

    // 3. Low-Latency Screen Mirroring Pipeline (Sub-35ms WebSocket Push with Zero-Leak Rendering)
    function startScreenStream() {
        if (isMirroringActive) return;
        isMirroringActive = true;
        mirrorPlaceholder.classList.add('hidden');
        screenStream.classList.remove('hidden');

        // Only start HTTP polling if WebSocket binary push stream is not already flowing
        if (!wsStreaming) {
            startHttpPolling();
        }
    }

    function stopScreenStream() {
        isMirroringActive = false;
        isFramePending = false;
        httpPollingActive = false;
        screenStream.classList.add('hidden');
        mirrorPlaceholder.classList.remove('hidden');

        if (currentBlobUrl) {
            URL.revokeObjectURL(currentBlobUrl);
            currentBlobUrl = null;
        }
    }

    // High-performance direct Blob rendering (Eliminates GC lag & TV browser memory leaks)
    function renderBlobFrame(blob) {
        wsStreaming = true;
        if (httpPollingActive) {
            // Immediately stop competing HTTP polling once WebSocket stream is active
            httpPollingActive = false;
            isFramePending = false;
        }

        if (!isMirroringActive) {
            isMirroringActive = true;
            mirrorPlaceholder.classList.add('hidden');
            screenStream.classList.remove('hidden');
        }

        const newUrl = URL.createObjectURL(blob);
        const oldUrl = currentBlobUrl;
        currentBlobUrl = newUrl;
        screenStream.src = newUrl;

        // Revoke previous blob URL to prevent TV browser heap exhaustion
        if (oldUrl) {
            setTimeout(() => {
                try { URL.revokeObjectURL(oldUrl); } catch (e) {}
            }, 80);
        }
    }

    // HTTP Polling Fallback (Activated ONLY if WebSocket binary push is unavailable)
    function startHttpPolling() {
        if (httpPollingActive || wsStreaming || !isMirroringActive) return;
        httpPollingActive = true;
        fetchNextFrame();
    }

    function fetchNextFrame() {
        if (!isMirroringActive || !httpPollingActive || isFramePending || wsStreaming) {
            httpPollingActive = false;
            isFramePending = false;
            return;
        }
        isFramePending = true;

        const nextImg = new Image();
        nextImg.onload = function () {
            if (isMirroringActive && !wsStreaming) {
                screenStream.src = nextImg.src;
                mirrorPlaceholder.classList.add('hidden');
                screenStream.classList.remove('hidden');
            }
            isFramePending = false;
            if (isMirroringActive && httpPollingActive && !wsStreaming) {
                requestAnimationFrame(fetchNextFrame);
            }
        };

        nextImg.onerror = function () {
            isFramePending = false;
            if (isMirroringActive && httpPollingActive && !wsStreaming) {
                setTimeout(fetchNextFrame, 150);
            }
        };

        nextImg.src = `/api/frame?t=${Date.now()}`;
    }

    // 4. WebSocket for Instant Remote Control & Ultra Low-Latency Screen Push
    function connectWebSocket() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.host;
        const wsUrl = `${protocol}//${host}/ws/screen`;

        try {
            ws = new WebSocket(wsUrl);
        } catch (e) {
            scheduleReconnect();
            return;
        }

        ws.onopen = function () {
            statusBadge.textContent = 'Connected (Live)';
            statusBadge.className = 'badge badge-connected';
            clearTimeout(reconnectTimer);

            // Heartbeat
            clearInterval(pingInterval);
            pingInterval = setInterval(() => {
                if (ws && ws.readyState === WebSocket.OPEN) {
                    ws.send('ping');
                }
            }, 10000);
        };

        ws.onmessage = function (event) {
            if (typeof event.data === 'string') {
                if (event.data === 'pong') return;
                try {
                    const message = JSON.parse(event.data);
                    handleControlMessage(message);
                } catch (e) {}
            } else if (event.data instanceof Blob) {
                renderBlobFrame(event.data);
            }
        };

        ws.onclose = function () {
            wsStreaming = false;
            clearInterval(pingInterval);
            scheduleReconnect();
            if (isMirroringActive) {
                startHttpPolling();
            }
        };

        ws.onerror = function () {
            wsStreaming = false;
            try { ws.close(); } catch (e) {}
        };
    }

    function scheduleReconnect() {
        clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(connectWebSocket, 3000);
    }

    // Audio Unmute & Remote Control Handler (Bypasses TV Browser Autoplay Muting)
    function unmuteAudio() {
        if (videoPlayer) {
            videoPlayer.muted = false;
            videoPlayer.volume = 1.0;
            videoPlayer.removeAttribute('muted');
            if (unmuteBanner) unmuteBanner.classList.add('hidden');
        }
    }

    if (btnUnmute) {
        btnUnmute.addEventListener('click', function (e) {
            e.stopPropagation();
            unmuteAudio();
            videoPlayer.play().catch(() => {});
        });
    }

    // Auto-unmute TV sound on any interaction (Remote OK, Enter, Space, Up, Down, 1, 2, 3)
    window.addEventListener('click', unmuteAudio);
    window.addEventListener('touchstart', unmuteAudio);
    window.addEventListener('keydown', function (e) {
        if (['Enter', ' ', 'MediaPlayPause', 'ArrowUp', 'ArrowDown', '1', '2', '3'].includes(e.key)) {
            unmuteAudio();
        }
    });

    // Handle incoming commands
    function handleControlMessage(msg) {
        console.log('Remote control command:', msg);
        switch (msg.action) {
            case 'init_state':
                if (msg.aspectMode) setAspectMode(msg.aspectMode, false);
                if (msg.isMirroring) startScreenStream();
                if (msg.hasMedia && msg.mediaUrl) {
                    switchTab('media');
                    if (msg.isVideo) playVideoOnTV(msg.mediaUrl, msg.mediaTitle);
                    else showPhotoOnTV(msg.mediaUrl, msg.mediaTitle);
                }
                break;
            case 'mirror_state':
                if (msg.active) {
                    switchTab('mirror');
                    startScreenStream();
                } else {
                    stopScreenStream();
                }
                break;
            case 'set_aspect':
                if (msg.mode) setAspectMode(msg.mode);
                break;
            case 'play_video':
                switchTab('media');
                playVideoOnTV(msg.url, msg.title);
                break;
            case 'show_photo':
                switchTab('media');
                showPhotoOnTV(msg.url, msg.title);
                break;
            case 'video_control':
                handleVideoControl(msg.command, msg.value);
                break;
            case 'switch_tab':
                if (msg.tab) switchTab(msg.tab);
                break;
        }
    }

    // 5. Browser Database & 4K Smart Cache Engine (IndexedDB + Blob Memory)
    // Eliminates all seek glitches, prevents restarting from 0, and enables full-bandwidth 4K playback.
    const DB_NAME = 'Cast16x9_MediaDB';
    const DB_VERSION = 1;
    const STORE_NAME = 'media_cache';
    const inMemoryMediaCache = new Map();
    let currentDownloadAbort = null;
    let activeBlobUrl = null;

    function openDB() {
        return new Promise((resolve) => {
            if (!window.indexedDB) {
                resolve(null);
                return;
            }
            try {
                const req = indexedDB.open(DB_NAME, DB_VERSION);
                req.onupgradeneeded = function (e) {
                    const db = e.target.result;
                    if (!db.objectStoreNames.contains(STORE_NAME)) {
                        db.createObjectStore(STORE_NAME, { keyPath: 'key' });
                    }
                };
                req.onsuccess = function (e) {
                    resolve(e.target.result);
                };
                req.onerror = function () {
                    resolve(null);
                };
            } catch (e) {
                resolve(null);
            }
        });
    }

    async function getCachedMedia(key) {
        if (inMemoryMediaCache.has(key)) {
            return inMemoryMediaCache.get(key);
        }
        const db = await openDB();
        if (!db) return null;
        return new Promise((resolve) => {
            try {
                const tx = db.transaction(STORE_NAME, 'readonly');
                const store = tx.objectStore(STORE_NAME);
                const req = store.get(key);
                req.onsuccess = function () {
                    const res = req.result;
                    if (res && res.blob) {
                        inMemoryMediaCache.set(key, res);
                        resolve(res);
                    } else {
                        resolve(null);
                    }
                };
                req.onerror = function () {
                    resolve(null);
                };
            } catch (e) {
                resolve(null);
            }
        });
    }

    async function saveMediaToCache(key, blob, title, mimeType) {
        const record = {
            key,
            blob,
            title: title || 'Video',
            mimeType: mimeType || 'video/mp4',
            size: blob.size,
            timestamp: Date.now()
        };
        inMemoryMediaCache.set(key, record);

        const db = await openDB();
        if (!db) return;
        try {
            const tx = db.transaction(STORE_NAME, 'readwrite');
            const store = tx.objectStore(STORE_NAME);
            store.put(record);

            // Auto-clean: keep up to 4 recent videos to protect TV storage
            const allReq = store.getAll();
            allReq.onsuccess = function () {
                const items = allReq.result || [];
                if (items.length > 4) {
                    items.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
                    while (items.length > 4) {
                        const oldest = items.shift();
                        store.delete(oldest.key);
                        inMemoryMediaCache.delete(oldest.key);
                    }
                }
            };
        } catch (e) {
            console.warn('IndexedDB write warning:', e);
        }
    }

    async function playVideoOnTV(url, title) {
        photoViewer.classList.add('hidden');
        mediaPlaceholder.classList.add('hidden');
        videoPlayer.classList.remove('hidden');

        // Cancel previous background caching download if any
        if (currentDownloadAbort) {
            currentDownloadAbort.abort();
            currentDownloadAbort = null;
        }

        const cacheKey = (title || 'video') + '_' + url.split('?')[0];

        // 1. Instant Playback if already cached in TV database
        const cached = await getCachedMedia(cacheKey);
        if (cached && cached.blob) {
            if (cachingHud) cachingHud.classList.add('hidden');
            playFromBlob(cached.blob, title);
            showHUD('⚡ Loaded from TV Database: Glitch-Free 4K Seeking Enabled');
            return;
        }

        // 2. High-Speed Cache Download into TV Storage
        startFastCachingAndPlay(url, title, cacheKey);
    }

    function playFromBlob(blob, title) {
        if (activeBlobUrl) {
            try { URL.revokeObjectURL(activeBlobUrl); } catch (e) {}
        }
        activeBlobUrl = URL.createObjectURL(blob);
        videoPlayer.src = activeBlobUrl;
        unmuteAudio();

        const playPromise = videoPlayer.play();
        if (playPromise !== undefined) {
            playPromise.then(() => {
                if (unmuteBanner) unmuteBanner.classList.add('hidden');
            }).catch(err => {
                videoPlayer.muted = true;
                videoPlayer.play().then(() => {
                    if (unmuteBanner) unmuteBanner.classList.remove('hidden');
                    showHUD('🔇 Audio Muted by TV: Press OK on Remote to Turn Sound ON');
                }).catch(e => {
                    console.error('Video play error:', e);
                });
            });
        }

        showHUD(`Playing in 4K: ${title || 'Video'}`);
    }

    function startFastCachingAndPlay(url, title, cacheKey) {
        if (cachingHud) {
            cachingHud.classList.remove('hidden');
            if (cachingProgressBar) cachingProgressBar.style.width = '0%';
            if (cachingStats) cachingStats.textContent = 'Connecting to phone... 0%';
        }

        const controller = new AbortController();
        currentDownloadAbort = controller;
        const startTime = Date.now();

        fetch(url, { signal: controller.signal })
            .then(async (response) => {
                if (!response.ok) throw new Error('HTTP ' + response.status);
                const totalBytes = +response.headers.get('Content-Length') || 0;
                const reader = response.body.getReader();
                let receivedBytes = 0;
                const chunks = [];

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    chunks.push(value);
                    receivedBytes += value.length;

                    if (totalBytes > 0 && cachingProgressBar && cachingStats) {
                        const pct = Math.min(100, Math.round((receivedBytes / totalBytes) * 100));
                        cachingProgressBar.style.width = pct + '%';

                        const elapsedSec = (Date.now() - startTime) / 1000;
                        const speedMbps = elapsedSec > 0.3 ? ((receivedBytes * 8) / (elapsedSec * 1024 * 1024)).toFixed(1) : 0;
                        const mbReceived = (receivedBytes / (1024 * 1024)).toFixed(1);
                        const mbTotal = (totalBytes / (1024 * 1024)).toFixed(1);
                        cachingStats.textContent = `⚡ Caching 4K Video: ${pct}% (${mbReceived} MB / ${mbTotal} MB at ${speedMbps} Mbps)`;
                    }
                }

                const blob = new Blob(chunks, { type: 'video/mp4' });
                await saveMediaToCache(cacheKey, blob, title, 'video/mp4');

                if (cachingHud) cachingHud.classList.add('hidden');
                currentDownloadAbort = null;

                playFromBlob(blob, title);
                showHUD('⚡ 4K Video Cached in TV Database: Glitch-free Seeking Ready');
            })
            .catch((err) => {
                if (err.name === 'AbortError') {
                    return;
                }
                console.warn('Fast caching fallback to live stream:', err);
                if (cachingHud) cachingHud.classList.add('hidden');
                playLiveStreamDirect(url, title);
            });
    }

    function playLiveStreamDirect(url, title) {
        if (cachingHud) cachingHud.classList.add('hidden');
        if (activeBlobUrl) {
            try { URL.revokeObjectURL(activeBlobUrl); } catch (e) {}
            activeBlobUrl = null;
        }
        videoPlayer.src = url;
        unmuteAudio();
        videoPlayer.play().catch(() => {});
        showHUD(`Live Stream: ${title || 'Video'}`);
    }

    if (btnPlayLive) {
        btnPlayLive.addEventListener('click', function (e) {
            e.stopPropagation();
            if (currentDownloadAbort) {
                currentDownloadAbort.abort();
                currentDownloadAbort = null;
            }
            playLiveStreamDirect('/media/active', 'Live Stream');
        });
    }

    function showPhotoOnTV(url, title) {
        videoPlayer.pause();
        videoPlayer.classList.add('hidden');
        mediaPlaceholder.classList.add('hidden');
        photoViewer.classList.remove('hidden');

        photoDisplay.src = url;
        showHUD(`Photo: ${title || 'Image'}`);
    }

    function handleVideoControl(command, value) {
        if (!videoPlayer) return;
        switch (command) {
            case 'play':
                unmuteAudio();
                videoPlayer.play().catch(() => {});
                break;
            case 'pause':
                videoPlayer.pause();
                break;
            case 'unmute':
                unmuteAudio();
                break;
            case 'seek':
                if (value !== undefined) videoPlayer.currentTime = value;
                break;
            case 'volume':
                if (value !== undefined) {
                    videoPlayer.volume = value;
                    videoPlayer.muted = (value === 0);
                    if (value > 0 && unmuteBanner) unmuteBanner.classList.add('hidden');
                }
                break;
        }
    }

    // 6. State Synchronization Safety Net (Polls /api/status every 1.5s)
    function syncServerStatus() {
        fetch('/api/status?t=' + Date.now())
            .then(res => {
                if (!res.ok) throw new Error('Status HTTP ' + res.status);
                return res.json();
            })
            .then(data => {
                statusBadge.textContent = 'Connected (Live)';
                statusBadge.className = 'badge badge-connected';

                if (data.isMirroring && !isMirroringActive) {
                    startScreenStream();
                } else if (!data.isMirroring && isMirroringActive) {
                    stopScreenStream();
                }

                if (data.aspectMode && data.aspectMode !== aspectMode) {
                    setAspectMode(data.aspectMode, false);
                }

                if (data.hasMedia && data.activeMedia && videoPlayer.paused && videoPlayer.classList.contains('hidden')) {
                    if (data.activeMedia.isVideo) {
                        switchTab('media');
                        playVideoOnTV(data.activeMedia.url, data.activeMedia.title);
                    }
                }
            })
            .catch(() => {
                // Only show reconnecting if we haven't seen the server
                if (!isMirroringActive) {
                    statusBadge.textContent = 'Connecting...';
                    statusBadge.className = 'badge badge-disconnected';
                }
            });
    }

    // 7. File Sharing Portal (Robust Loading & Instant Downloads)
    function loadSharedFiles() {
        filesGrid.innerHTML = '<div class="loading-spinner">Fetching files from phone...</div>';

        fetch('/api/files?t=' + Date.now())
            .then(res => {
                if (!res.ok) throw new Error('Files HTTP ' + res.status);
                return res.json();
            })
            .then(files => {
                if (!files || files.length === 0) {
                    filesGrid.innerHTML = '<div class="empty-state"><h3>📁 No files currently shared</h3><p>Open the Cast16x9 app on your phone, navigate to the <strong>File Share</strong> tab, and tap <strong>"Add Files to Share"</strong> to download or stream them here.</p></div>';
                    return;
                }

                filesGrid.innerHTML = '';
                files.forEach((file, index) => {
                    const card = document.createElement('div');
                    card.className = 'file-card';
                    card.tabIndex = 10 + index;

                    const icon = getFileIcon(file.name);
                    const sizeFormatted = formatFileSize(file.size);

                    card.innerHTML = `
                        <div>
                            <div class="file-icon">${icon}</div>
                            <div class="file-name">${escapeHtml(file.name)}</div>
                            <div class="file-meta">${sizeFormatted}</div>
                        </div>
                        <div class="file-actions">
                            <a href="${file.url}" download="${escapeHtml(file.name)}" class="btn-file" tabindex="${100 + index}">⬇️ Download</a>
                            ${file.isVideo ? `<button class="btn-file btn-play" data-url="${file.url}" data-name="${escapeHtml(file.name)}" tabindex="${200 + index}">▶ Play</button>` : ''}
                        </div>
                    `;

                    filesGrid.appendChild(card);
                });

                document.querySelectorAll('.btn-play').forEach(btn => {
                    btn.addEventListener('click', function () {
                        const url = this.getAttribute('data-url');
                        const name = this.getAttribute('data-name');
                        switchTab('media');
                        playVideoOnTV(url, name);
                    });
                });
            })
            .catch(err => {
                console.error('Error fetching files:', err);
                filesGrid.innerHTML = '<div class="empty-state"><h3>⚠️ Could not load shared files</h3><p>Ensure your phone is running the Cast16x9 app and connected to the same Wi-Fi.</p></div>';
            });
    }

    btnRefreshFiles.addEventListener('click', loadSharedFiles);

    function getFileIcon(fileName) {
        const ext = fileName.split('.').pop().toLowerCase();
        if (['mp4', 'mkv', 'avi', 'mov', 'webm'].includes(ext)) return '🎬';
        if (['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(ext)) return '🖼️';
        if (['mp3', 'wav', 'flac', 'aac', 'm4a'].includes(ext)) return '🎵';
        if (['apk'].includes(ext)) return '📦';
        if (['pdf', 'doc', 'docx', 'txt'].includes(ext)) return '📄';
        if (['zip', 'rar', '7z', 'tar'].includes(ext)) return '🗜️';
        return '📁';
    }

    function formatFileSize(bytes) {
        if (!bytes || bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // 8. TV Remote Control Key Handling (16:9 Aspect, Smooth 4K Seeking, & Audio Controls)
    window.addEventListener('keydown', function (e) {
        // TV Remote Seeking & Playback Shortcuts
        if (activeTab === 'media' && videoPlayer && !videoPlayer.classList.contains('hidden')) {
            switch (e.key) {
                case 'ArrowRight':
                case 'l':
                case 'L':
                    e.preventDefault();
                    videoPlayer.currentTime = Math.min(videoPlayer.duration || 999999, videoPlayer.currentTime + 10);
                    showHUD(`⏩ Forward +10s (${formatTime(videoPlayer.currentTime)})`);
                    return;
                case 'ArrowLeft':
                case 'j':
                case 'J':
                    e.preventDefault();
                    videoPlayer.currentTime = Math.max(0, videoPlayer.currentTime - 10);
                    showHUD(`⏪ Rewind -10s (${formatTime(videoPlayer.currentTime)})`);
                    return;
                case ' ':
                case 'k':
                case 'K':
                    e.preventDefault();
                    if (videoPlayer.paused) { videoPlayer.play(); showHUD('▶ Playing'); }
                    else { videoPlayer.pause(); showHUD('⏸ Paused'); }
                    return;
            }
        }

        switch (e.key) {
            case '1': setAspectMode('cover'); break;
            case '2': setAspectMode('stretch'); break;
            case '3': setAspectMode('fit'); break;
            case 'f':
            case 'F': toggleFullscreen(); break;
        }
    });

    function formatTime(sec) {
        if (isNaN(sec)) return '0:00';
        const m = Math.floor(sec / 60);
        const s = Math.floor(sec % 60);
        return `${m}:${s < 10 ? '0' : ''}${s}`;
    }

    function toggleFullscreen() {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(() => {});
        } else {
            document.exitFullscreen().catch(() => {});
        }
    }

    // Initialize
    setAspectMode('cover', false);
    connectWebSocket();
    syncServerStatus();
    statusSyncInterval = setInterval(syncServerStatus, 1500);

})();
