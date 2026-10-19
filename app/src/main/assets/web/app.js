// Cast16x9 TV Web Receiver Logic
(function () {
    'use strict';

    // State
    let activeTab = 'mirror';
    let aspectMode = 'cover'; // 'cover' (16:9 zoom no bars), 'stretch' (16:9 fill no bars), 'fit' (contain)
    let ws = null;
    let reconnectTimer = null;
    let hudTimer = null;

    // DOM Elements
    const statusBadge = document.getElementById('connection-status');
    const mirrorWrapper = document.getElementById('mirror-wrapper');
    const canvas = document.getElementById('screen-canvas');
    const fallbackImg = document.getElementById('screen-fallback');
    const mirrorPlaceholder = document.getElementById('mirror-placeholder');
    const activeModeName = document.getElementById('active-mode-name');
    const hudOverlay = document.getElementById('hud-overlay');
    const hudText = document.getElementById('hud-text');

    const videoPlayer = document.getElementById('tv-video-player');
    const photoViewer = document.getElementById('photo-viewer');
    const photoDisplay = document.getElementById('tv-photo-display');
    const mediaPlaceholder = document.getElementById('media-placeholder');

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

    const ctx = canvas.getContext('2d');

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

    // Bind tab clicks
    Object.keys(tabs).forEach(key => {
        tabs[key].btn.addEventListener('click', () => switchTab(key));
    });

    // 2. Aspect Ratio Transformation (16:9 Zero Black Bars)
    function setAspectMode(mode, showNotification = true) {
        aspectMode = mode;
        mirrorWrapper.className = `aspect-${mode}`;

        // Update active button state
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

    // Bind mode buttons
    Object.keys(modeButtons).forEach(mode => {
        modeButtons[mode].addEventListener('click', () => setAspectMode(mode));
    });

    // 3. WebSocket Connection for Live Screen Stream and Remote Commands
    function connectWebSocket() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.host;
        const wsUrl = `${protocol}//${host}/ws/screen`;

        console.log(`Connecting to WebSocket at ${wsUrl}...`);
        ws = new WebSocket(wsUrl);
        ws.binaryType = 'blob';

        ws.onopen = function () {
            console.log('Connected to Cast16x9 phone/smartwatch stream!');
            statusBadge.textContent = 'Connected (Live)';
            statusBadge.className = 'badge badge-connected';
            clearTimeout(reconnectTimer);
        };

        ws.onmessage = function (event) {
            if (typeof event.data === 'string') {
                // JSON Control message from Phone App
                try {
                    const message = JSON.parse(event.data);
                    handleControlMessage(message);
                } catch (e) {
                    console.error('Invalid JSON message:', event.data);
                }
            } else if (event.data instanceof Blob) {
                // Incoming Screen Frame (JPEG / PNG / WebP)
                renderScreenFrame(event.data);
            }
        };

        ws.onclose = function () {
            console.log('Stream disconnected. Reconnecting in 3s...');
            statusBadge.textContent = 'Disconnected (Reconnecting...)';
            statusBadge.className = 'badge badge-disconnected';
            mirrorPlaceholder.classList.remove('hidden');
            reconnectTimer = setTimeout(connectWebSocket, 3000);
        };

        ws.onerror = function (err) {
            console.error('WebSocket Error:', err);
            ws.close();
        };
    }

    // Render binary frame to canvas / screen
    function renderScreenFrame(blob) {
        mirrorPlaceholder.classList.add('hidden');

        const url = URL.createObjectURL(blob);
        const img = new Image();
        img.onload = function () {
            // Resize canvas to match incoming frame
            if (canvas.width !== img.width || canvas.height !== img.height) {
                canvas.width = img.width;
                canvas.height = img.height;
            }
            ctx.drawImage(img, 0, 0);
            URL.revokeObjectURL(url);
        };
        img.src = url;
    }

    // Handle remote commands sent from phone app
    function handleControlMessage(msg) {
        console.log('Received remote command:', msg);
        switch (msg.action) {
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

    // 4. Media Player Handling (Direct 16:9 full TV screen playback)
    function playVideoOnTV(url, title) {
        photoViewer.classList.add('hidden');
        mediaPlaceholder.classList.add('hidden');
        videoPlayer.classList.remove('hidden');

        videoPlayer.src = url;
        videoPlayer.play().catch(err => {
            console.warn('Autoplay blocked, requesting user interaction:', err);
        });

        showHUD(`Playing: ${title || 'Video'}`);
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
            case 'play': videoPlayer.play(); break;
            case 'pause': videoPlayer.pause(); break;
            case 'seek': if (value !== undefined) videoPlayer.currentTime = value; break;
            case 'volume': if (value !== undefined) videoPlayer.volume = value; break;
        }
    }

    // 5. File Sharing Portal
    function loadSharedFiles() {
        filesGrid.innerHTML = '<div class="loading-spinner">Fetching files from phone...</div>';

        fetch('/api/files')
            .then(res => res.json())
            .then(files => {
                if (!files || files.length === 0) {
                    filesGrid.innerHTML = '<p class="empty-state">No files currently shared. Share files from the Cast16x9 app on your phone.</p>';
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
                            <a href="${file.url}" target="_blank" class="btn-file" tabindex="${100 + index}">⬇️ Download</a>
                            ${file.isVideo ? `<button class="btn-file btn-play" data-url="${file.url}" data-name="${escapeHtml(file.name)}" tabindex="${200 + index}">▶ Play</button>` : ''}
                        </div>
                    `;

                    filesGrid.appendChild(card);
                });

                // Attach play button listeners
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
                filesGrid.innerHTML = '<p class="error-state">Failed to connect to phone file service.</p>';
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

    // 6. TV Remote Control Key Handling (Keyboard / D-Pad)
    window.addEventListener('keydown', function (e) {
        switch (e.key) {
            case '1':
                setAspectMode('cover');
                break;
            case '2':
                setAspectMode('stretch');
                break;
            case '3':
                setAspectMode('fit');
                break;
            case 'f':
            case 'F':
                toggleFullscreen();
                break;
        }
    });

    function toggleFullscreen() {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(err => console.log(err));
        } else {
            document.exitFullscreen().catch(err => console.log(err));
        }
    }

    // Auto-enter fullscreen on first click/remote press
    document.addEventListener('click', function () {
        if (!document.fullscreenElement && window.innerHeight < screen.height - 20) {
            document.documentElement.requestFullscreen().catch(() => {});
        }
    }, { once: true });

    // Initialize
    setAspectMode('cover', false);
    connectWebSocket();

})();
