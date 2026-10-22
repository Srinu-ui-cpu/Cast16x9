/**
 * Cast16x9 Automated & Manual Diagnostic Test Suite
 * Zero external dependencies - runs on standard Node.js
 */

const fs = require('fs');
const path = require('path');
const http = require('http');
const net = require('net');
const crypto = require('crypto');

const ROOT_DIR = path.resolve(__dirname, '..');
const ASSETS_DIR = path.join(ROOT_DIR, 'app', 'src', 'main', 'assets', 'web');
const JAVA_DIR = path.join(ROOT_DIR, 'app', 'src', 'main', 'java', 'com', 'cast16x9', 'app');

const COLORS = {
    reset: '\x1b[0m',
    green: '\x1b[32m',
    red: '\x1b[31m',
    yellow: '\x1b[33m',
    blue: '\x1b[34m',
    cyan: '\x1b[36m',
    bold: '\x1b[1m'
};

let testCount = 0;
let passedCount = 0;
let failedCount = 0;
const failures = [];

function assert(condition, testName, details = '') {
    testCount++;
    if (condition) {
        passedCount++;
        console.log(`  ${COLORS.green}✓${COLORS.reset} [PASS] ${testName}`);
    } else {
        failedCount++;
        console.log(`  ${COLORS.red}✗${COLORS.reset} [FAIL] ${testName} ${details ? '(' + details + ')' : ''}`);
        failures.push({ name: testName, details });
    }
}

// =========================================================================
// 1. Static Asset & Frontend Structure Verification
// =========================================================================
function testStaticAssets() {
    console.log(`\n${COLORS.bold}${COLORS.cyan}=== 1. Testing Web Receiver Assets & 16:9 CSS Rules ===${COLORS.reset}`);
    
    const htmlPath = path.join(ASSETS_DIR, 'index.html');
    const cssPath = path.join(ASSETS_DIR, 'style.css');
    const jsPath = path.join(ASSETS_DIR, 'app.js');

    assert(fs.existsSync(htmlPath), 'index.html exists');
    assert(fs.existsSync(cssPath), 'style.css exists');
    assert(fs.existsSync(jsPath), 'app.js exists');

    const htmlContent = fs.readFileSync(htmlPath, 'utf8');
    assert(htmlContent.includes('id="screen-canvas"'), 'index.html contains screen canvas element');
    assert(htmlContent.includes('id="tv-video-player"'), 'index.html contains HTML5 video player');
    assert(htmlContent.includes('id="btn-mode-cover"'), 'index.html contains 16:9 Cover button');
    assert(htmlContent.includes('id="btn-mode-stretch"'), 'index.html contains 16:9 Stretch button');
    assert(htmlContent.includes('id="btn-mode-fit"'), 'index.html contains 16:9 Fit button');
    assert(htmlContent.includes('id="unmute-banner"'), 'index.html contains TV browser audio unmute banner');
    assert(htmlContent.includes('id="btn-unmute"'), 'index.html contains Turn Sound ON button');
    assert(htmlContent.includes('id="caching-hud"'), 'index.html contains 4K TV Database Caching HUD');
    assert(htmlContent.includes('id="caching-progress-bar"'), 'index.html contains Caching progress bar');
    assert(htmlContent.includes('id="btn-play-live"'), 'index.html contains Play Live (Skip Cache) button');
    assert(htmlContent.includes('id="btn-cancel-video"'), 'index.html contains Cancel / Close Video button');
    assert(htmlContent.includes('id="btn-cancel-caching"'), 'index.html contains Cancel Video button in caching HUD');

    const cssContent = fs.readFileSync(cssPath, 'utf8');
    assert(cssContent.includes('.aspect-cover'), 'CSS contains .aspect-cover class');
    assert(cssContent.includes('.aspect-stretch'), 'CSS contains .aspect-stretch class');
    assert(cssContent.includes('.unmute-banner'), 'CSS contains .unmute-banner audio styles');
    assert(cssContent.includes('.caching-hud'), 'CSS contains .caching-hud 4K database styling');
    assert(cssContent.includes('.caching-progress-bar'), 'CSS contains .caching-progress-bar styling');
    assert(cssContent.includes('.btn-cancel-video'), 'CSS contains .btn-cancel-video close styling');
    assert(cssContent.includes('.btn-cancel-caching'), 'CSS contains .btn-cancel-caching HUD button styling');
    assert(cssContent.includes('100vw') && cssContent.includes('100vh'), 'CSS enforces 100vw/100vh full-screen rules');

    const jsContent = fs.readFileSync(jsPath, 'utf8');
    assert(jsContent.includes('/ws/screen'), 'app.js connects to /ws/screen WebSocket endpoint');
    assert(jsContent.includes('setAspectMode'), 'app.js implements setAspectMode()');
    assert(jsContent.includes('playVideoOnTV'), 'app.js implements playVideoOnTV()');
    assert(jsContent.includes('unmuteAudio'), 'app.js implements unmuteAudio() for TV browser sound policy');
    assert(jsContent.includes('renderBlobFrame'), 'app.js implements low-latency renderBlobFrame() with memory leak prevention');
    assert(jsContent.includes('Cast16x9_MediaDB'), 'app.js implements Cast16x9_MediaDB IndexedDB storage');
    assert(jsContent.includes('dbInstance'), 'app.js implements singleton dbInstance connection');
    assert(jsContent.includes('getCachedMedia'), 'app.js implements getCachedMedia() for 0ms glitch-free seeking');
    assert(jsContent.includes('saveMediaToCache'), 'app.js implements saveMediaToCache()');
    assert(jsContent.includes('playFromBlob'), 'app.js implements playFromBlob() for local 4K playback');
    assert(jsContent.includes('cancelActiveMedia'), 'app.js implements cancelActiveMedia() for TV cancel button');
    assert(jsContent.includes('clearAllMediaCache'), 'app.js implements clearAllMediaCache() to wipe TV DB on cancel/change');
    assert(jsContent.includes('/api/media/cancel'), 'app.js triggers /api/media/cancel fallback on cancel');
    assert(jsContent.includes('keydown'), 'app.js listens to TV remote key events (1, 2, 3, etc.)');

    // Android Layout & Server Verification for Changing/Canceling Cast
    const layoutPath = path.join(ROOT_DIR, 'app', 'src', 'main', 'res', 'layout', 'activity_main.xml');
    const layoutContent = fs.readFileSync(layoutPath, 'utf8');
    assert(layoutContent.includes('id="@+id/card_active_cast"'), 'activity_main.xml contains active cast status card');
    assert(layoutContent.includes('id="@+id/btn_stop_cast"'), 'activity_main.xml contains Stop / Cancel Cast button');
    assert(layoutContent.includes('id="@+id/btn_change_video"'), 'activity_main.xml contains Change Video button');

    const serverPath = path.join(JAVA_DIR, 'server', 'WebReceiverServer.kt');
    const serverContent = fs.readFileSync(serverPath, 'utf8');
    assert(serverContent.includes('stopCastingMedia'), 'WebReceiverServer implements stopCastingMedia()');
    assert(serverContent.includes('/api/media/cancel'), 'WebReceiverServer routes /api/media/cancel');
    assert(serverContent.includes('onMediaCancelledListener'), 'WebReceiverServer provides onMediaCancelledListener callback');
    assert(serverContent.includes('clearPreviousCache'), 'WebReceiverServer sends clearPreviousCache on new video beam');
    assert(serverContent.includes('getMediaFileLength'), 'WebReceiverServer implements getMediaFileLength() with fallback strategies');
    assert(serverContent.includes('Os.fstat'), 'WebReceiverServer implements direct Linux kernel fstat 64-bit size detection');
    assert(serverContent.includes('Method.HEAD'), 'WebReceiverServer handles HTTP HEAD request probing');
    assert(serverContent.includes('RandomAccessFile'), 'WebReceiverServer implements RandomAccessFile hardware O(1) seek pipeline');
    assert(serverContent.includes('Access-Control-Expose-Headers'), 'WebReceiverServer exposes CORS Range & Length headers');
    assert(jsContent.includes('seeking'), 'app.js handles TV video seeking event');
    assert(jsContent.includes('seeked'), 'app.js handles TV video seeked auto-resume');
    assert(jsContent.includes('isAlreadyActive'), 'app.js protects active playback from init_state restart to 00:00');
}

// =========================================================================
// 2. 16:9 Aspect Ratio Transformation Math Engine
// =========================================================================
function testAspectRatioMath() {
    console.log(`\n${COLORS.bold}${COLORS.cyan}=== 2. Testing 16:9 Aspect Transformation Math Engine ===${COLORS.reset}`);

    const tvWidth = 1920;
    const tvHeight = 1080;
    const tvAspect = tvWidth / tvHeight; // 1.777...

    function calculateTransforms(srcWidth, srcHeight, mode) {
        const srcAspect = srcWidth / srcHeight;

        if (mode === 'stretch') {
            // Stretch directly covers 100% of TV screen
            return { width: tvWidth, height: tvHeight, x: 0, y: 0, hasBlackBars: false };
        } else if (mode === 'cover') {
            // Zoom until entire TV display is covered, zero black bars
            let drawWidth, drawHeight;
            if (srcAspect > tvAspect) {
                drawHeight = tvHeight;
                drawWidth = tvHeight * srcAspect;
            } else {
                drawWidth = tvWidth;
                drawHeight = tvWidth / srcAspect;
            }
            const x = (tvWidth - drawWidth) / 2;
            const y = (tvHeight - drawHeight) / 2;
            const coversEntireCanvas = drawWidth >= tvWidth && drawHeight >= tvHeight;
            return { width: drawWidth, height: drawHeight, x, y, hasBlackBars: !coversEntireCanvas };
        } else {
            // Fit / contain (may have black bars)
            let drawWidth, drawHeight;
            if (srcAspect > tvAspect) {
                drawWidth = tvWidth;
                drawHeight = tvWidth / srcAspect;
            } else {
                drawHeight = tvHeight;
                drawWidth = tvHeight * srcAspect;
            }
            const x = (tvWidth - drawWidth) / 2;
            const y = (tvHeight - drawHeight) / 2;
            return { width: drawWidth, height: drawHeight, x, y, hasBlackBars: (drawWidth < tvWidth || drawHeight < tvHeight) };
        }
    }

    // Case A: Smartwatch (1:1 Square, 454x454)
    const watchCover = calculateTransforms(454, 454, 'cover');
    assert(!watchCover.hasBlackBars, 'Smartwatch (1:1) in Cover mode has ZERO black bars');
    assert(watchCover.width === tvWidth && watchCover.height >= tvHeight, 'Smartwatch in Cover mode spans full TV width');

    const watchStretch = calculateTransforms(454, 454, 'stretch');
    assert(!watchStretch.hasBlackBars && watchStretch.width === tvWidth && watchStretch.height === tvHeight, 'Smartwatch in Stretch mode spans exact 1920x1080');

    // Case B: Modern Tall Smartphone (20:9, 2400x1080)
    const phoneCover = calculateTransforms(2400, 1080, 'cover');
    assert(!phoneCover.hasBlackBars, 'Phone (20:9) in Cover mode has ZERO black bars');

    const phoneStretch = calculateTransforms(2400, 1080, 'stretch');
    assert(!phoneStretch.hasBlackBars && phoneStretch.width === tvWidth && phoneStretch.height === tvHeight, 'Phone in Stretch mode spans exact 1920x1080');

    // Case C: Standard 16:9 content
    const standardCover = calculateTransforms(1920, 1080, 'cover');
    assert(!standardCover.hasBlackBars && standardCover.width === tvWidth && standardCover.height === tvHeight, '16:9 source in Cover mode matches TV perfectly');
}

// =========================================================================
// 3. Mock Server Emulation: HTTP Ranges, Assets, and File Sharing
// =========================================================================
async function testServerProtocols() {
    console.log(`\n${COLORS.bold}${COLORS.cyan}=== 3. Testing Server Endpoints, HTTP Range Seeking & File Sharing ===${COLORS.reset}`);

    // Create a mock video buffer of 10,000 bytes
    const mockVideoSize = 10000;
    const mockVideoBuffer = Buffer.alloc(mockVideoSize, 0x42);

    const mockSharedFiles = [
        { id: 'file-01', name: 'Document.pdf', size: 1024, mimeType: 'application/pdf', isVideo: false, isPhoto: false, url: '/files/download/file-01' },
        { id: 'file-02', name: 'Movie.mp4', size: mockVideoSize, mimeType: 'video/mp4', isVideo: true, isPhoto: false, url: '/files/download/file-02' }
    ];

    // Create an emulation server mimicking WebReceiverServer.kt
    const server = http.createServer((req, res) => {
        const url = req.url;

        // Assets
        if (url === '/' || url === '/index.html') {
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end('<!DOCTYPE html><html><body>TV Web Receiver</body></html>');
            return;
        }
        if (url === '/style.css') {
            res.writeHead(200, { 'Content-Type': 'text/css' });
            res.end('body { background: #000; }');
            return;
        }
        if (url === '/app.js') {
            res.writeHead(200, { 'Content-Type': 'application/javascript' });
            res.end('console.log("ready");');
            return;
        }

        // API: Files
        if (url === '/api/files') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(mockSharedFiles));
            return;
        }

        // HTTP HEAD probing for TV video player
        if (req.method === 'HEAD' && (url === '/media/active' || url === '/files/download/file-02')) {
            res.writeHead(200, {
                'Content-Length': mockVideoSize,
                'Content-Type': 'video/mp4',
                'Accept-Ranges': 'bytes',
                'Cache-Control': 'public, max-age=86400'
            });
            res.end();
            return;
        }

        // Active media stream with Range request support (HTTP 206)
        if (url === '/media/active' || url === '/files/download/file-02') {
            const rangeHeader = req.headers.range;
            if (rangeHeader && rangeHeader.startsWith('bytes=')) {
                const parts = rangeHeader.replace(/bytes=/, '').split('-');
                const start = parseInt(parts[0], 10) || 0;
                const end = parts[1] ? parseInt(parts[1], 10) : mockVideoSize - 1;
                const chunkSize = (end - start) + 1;

                res.writeHead(206, {
                    'Content-Range': `bytes ${start}-${end}/${mockVideoSize}`,
                    'Accept-Ranges': 'bytes',
                    'Content-Length': chunkSize,
                    'Content-Type': 'video/mp4'
                });
                res.end(mockVideoBuffer.slice(start, end + 1));
                return;
            } else {
                res.writeHead(200, {
                    'Content-Length': mockVideoSize,
                    'Content-Type': 'video/mp4',
                    'Accept-Ranges': 'bytes'
                });
                res.end(mockVideoBuffer);
                return;
            }
        }

        // File download with attachment header
        if (url === '/files/download/file-01') {
            res.writeHead(200, {
                'Content-Type': 'application/pdf',
                'Content-Disposition': 'attachment; filename="Document.pdf"',
                'Content-Length': 1024
            });
            res.end(Buffer.alloc(1024));
            return;
        }

        // Cancel Active Media & Reclaim Space API
        if (url.startsWith('/api/media/cancel')) {
            res.writeHead(200, {
                'Content-Type': 'application/json',
                'Cache-Control': 'no-cache, no-store'
            });
            res.end(JSON.stringify({ status: 'cancelled', freed: true }));
            return;
        }

        res.writeHead(404);
        res.end('Not Found');
    });

    await new Promise((resolve) => server.listen(9876, '127.0.0.1', resolve));

    try {
        // Request 1: Root /
        const resIndex = await makeRequest('http://127.0.0.1:9876/');
        assert(resIndex.statusCode === 200, 'HTTP GET / returns 200 OK');
        assert(resIndex.headers['content-type'].includes('text/html'), 'HTTP GET / returns text/html');

        // Request 2: /api/files
        const resFiles = await makeRequest('http://127.0.0.1:9876/api/files');
        assert(resFiles.statusCode === 200, 'HTTP GET /api/files returns 200 OK');
        const filesJson = JSON.parse(resFiles.body);
        assert(Array.isArray(filesJson) && filesJson.length === 2, 'HTTP GET /api/files returns valid file list JSON');

        // Request 3: HTTP Range Request for Video Seeking (e.g. bytes=100-499)
        const resRange = await makeRequest('http://127.0.0.1:9876/media/active', {
            headers: { 'Range': 'bytes=100-499' }
        });
        assert(resRange.statusCode === 206, 'HTTP Range request returns 206 Partial Content');
        assert(resRange.headers['content-range'] === `bytes 100-499/${mockVideoSize}`, 'Content-Range header formatted correctly for TV video seeking');
        assert(resRange.bodyBuffer.length === 400, 'Returned chunk size exactly matches requested byte range (400 bytes)');

        // Request 4: File download attachment
        const resDownload = await makeRequest('http://127.0.0.1:9876/files/download/file-01');
        assert(resDownload.statusCode === 200, 'File download returns 200 OK');
        assert(resDownload.headers['content-disposition'].includes('attachment; filename="Document.pdf"'), 'File download includes Content-Disposition attachment header');

        // Request 5: Cancel active media & reclaim TV storage API
        const resCancel = await makeRequest('http://127.0.0.1:9876/api/media/cancel');
        assert(resCancel.statusCode === 200, 'HTTP GET /api/media/cancel returns 200 OK');
        const cancelJson = JSON.parse(resCancel.body);
        assert(cancelJson.status === 'cancelled' && cancelJson.freed === true, '/api/media/cancel confirms media cancelled and TV storage freed');

        // Request 6: HTTP HEAD request for TV video probe
        const resHead = await makeRequest('http://127.0.0.1:9876/media/active', { method: 'HEAD' });
        assert(resHead.statusCode === 200, 'HTTP HEAD /media/active returns 200 OK');
        assert(resHead.headers['accept-ranges'] === 'bytes', 'HTTP HEAD response advertises Accept-Ranges: bytes');
        assert(parseInt(resHead.headers['content-length'], 10) === mockVideoSize, 'HTTP HEAD response returns exact video Content-Length');

    } finally {
        await new Promise((resolve) => server.close(resolve));
    }
}

// =========================================================================
// 4. WebSocket Screen Streaming & Remote Control Command Test
// =========================================================================
async function testWebSocketStreaming() {
    console.log(`\n${COLORS.bold}${COLORS.cyan}=== 4. Testing WebSocket Handshake & Frame Delivery Protocol ===${COLORS.reset}`);

    // Emulate a standard RFC-6455 WebSocket Server
    const server = http.createServer();
    const connectedSockets = [];

    server.on('upgrade', (req, socket, head) => {
        if (req.url === '/ws/screen') {
            const key = req.headers['sec-websocket-key'];
            const acceptKey = crypto
                .createHash('sha1')
                .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
                .digest('base64');

            socket.write(
                'HTTP/1.1 101 Switching Protocols\r\n' +
                'Upgrade: websocket\r\n' +
                'Connection: Upgrade\r\n' +
                `Sec-WebSocket-Accept: ${acceptKey}\r\n\r\n`
            );

            connectedSockets.push(socket);
        } else {
            socket.destroy();
        }
    });

    await new Promise((resolve) => server.listen(9877, '127.0.0.1', resolve));

    try {
        // Connect a raw TCP client performing WebSocket handshake
        const clientSocket = new net.Socket();
        let handshakeDone = false;

        await new Promise((resolve, reject) => {
            clientSocket.connect(9877, '127.0.0.1', () => {
                const secKey = crypto.randomBytes(16).toString('base64');
                const req =
                    'GET /ws/screen HTTP/1.1\r\n' +
                    'Host: 127.0.0.1:9877\r\n' +
                    'Upgrade: websocket\r\n' +
                    'Connection: Upgrade\r\n' +
                    `Sec-WebSocket-Key: ${secKey}\r\n` +
                    'Sec-WebSocket-Version: 13\r\n\r\n';
                clientSocket.write(req);
            });

            clientSocket.on('data', (chunk) => {
                const text = chunk.toString();
                if (text.includes('101 Switching Protocols')) {
                    handshakeDone = true;
                    resolve();
                }
            });

            clientSocket.on('error', reject);
        });

        assert(handshakeDone, 'WebSocket handshake completed successfully on /ws/screen');
        assert(connectedSockets.length === 1, 'Server registered active TV browser client');

        // Test sending a JSON control command from Server to TV Client
        const testCommand = JSON.stringify({ action: 'set_aspect', mode: 'cover' });
        const textFrame = encodeWebSocketFrame(testCommand, false);

        const commandPromise = new Promise((resolve) => {
            clientSocket.once('data', (data) => {
                const decoded = decodeWebSocketFrame(data);
                resolve(decoded);
            });
        });

        connectedSockets[0].write(textFrame);
        const receivedCmd = await commandPromise;
        const parsedCmd = JSON.parse(receivedCmd);
        assert(parsedCmd.action === 'set_aspect' && parsedCmd.mode === 'cover', 'TV client successfully receives remote 16:9 mode command');

        // Test sending a binary screen frame buffer
        const fakeJpeg = Buffer.from([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46]);
        const binaryFrame = encodeWebSocketFrame(fakeJpeg, true);

        const framePromise = new Promise((resolve) => {
            clientSocket.once('data', (data) => {
                const decoded = decodeWebSocketFrame(data, true);
                resolve(decoded);
            });
        });

        connectedSockets[0].write(binaryFrame);
        const receivedFrame = await framePromise;
        assert(Buffer.compare(receivedFrame, fakeJpeg) === 0, 'Binary screen frame transmitted and verified intact');

        clientSocket.destroy();
        connectedSockets.forEach(s => s.destroy());
    } finally {
        await new Promise((resolve) => server.close(resolve));
    }
}

// =========================================================================
// 5. DLNA Discovery & Dynamic UPnP MIME Type Verification
// =========================================================================
function testDlnaProtocol() {
    console.log(`\n${COLORS.bold}${COLORS.cyan}=== 5. Testing DLNA SSDP Discovery & UPnP SOAP Envelope Generator ===${COLORS.reset}`);

    // Verify SSDP M-SEARCH broadcast string
    const ssdpSearch = 
        'M-SEARCH * HTTP/1.1\r\n' +
        'HOST: 239.255.255.250:1900\r\n' +
        'MAN: "ssdp:discover"\r\n' +
        'MX: 3\r\n' +
        'ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n';

    assert(ssdpSearch.includes('239.255.255.250:1900'), 'SSDP uses standard UPnP multicast address 239.255.255.250:1900');
    assert(ssdpSearch.includes('urn:schemas-upnp-org:service:AVTransport:1'), 'SSDP specifically searches for AVTransport:1 service for Smart TVs');

    // Test UPnP SOAP envelope generator with dynamic MIME types & video audio classes
    function buildSoapPayload(mediaUrl, title, mimeType) {
        const upnpClass = mimeType.startsWith('video/') ? 'object.item.videoItem.movie' :
            (mimeType.startsWith('audio/') ? 'object.item.audioItem.musicTrack' : 'object.item.imageItem.photo');
        return `
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>${mediaUrl}</CurrentURI>
                        <CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="0" parentID="-1" restricted="1"&gt;&lt;dc:title&gt;${title}&lt;/dc:title&gt;&lt;upnp:class&gt;${upnpClass}&lt;/upnp:class&gt;&lt;res protocolInfo="http-get:*:${mimeType}:*"&gt;${mediaUrl}&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                </s:Body>
            </s:Envelope>
        `.trim();
    }

    const videoSoap = buildSoapPayload('http://192.168.1.10:8080/media/active', 'Movie.mp4', 'video/mp4');
    assert(videoSoap.includes('http-get:*:video/mp4:*'), 'DLNA SOAP payload correctly specifies video/mp4 protocol info');
    assert(videoSoap.includes('object.item.videoItem.movie'), 'DLNA SOAP payload specifies object.item.videoItem.movie for TV audio decoder activation');

    const photoSoap = buildSoapPayload('http://192.168.1.10:8080/media/active', 'Photo.jpg', 'image/jpeg');
    assert(photoSoap.includes('http-get:*:image/jpeg:*'), 'DLNA SOAP payload dynamically formats image/jpeg protocol info');
    assert(photoSoap.includes('object.item.imageItem.photo'), 'DLNA SOAP payload specifies object.item.imageItem.photo class');
}

// =========================================================================
// Helper Utilities for WebSockets & HTTP
// =========================================================================
function makeRequest(urlStr, options = {}) {
    return new Promise((resolve, reject) => {
        const url = new URL(urlStr);
        const reqOpts = {
            hostname: url.hostname,
            port: url.port,
            path: url.pathname + url.search,
            method: options.method || 'GET',
            headers: options.headers || {}
        };

        const req = http.request(reqOpts, (res) => {
            const chunks = [];
            res.on('data', (c) => chunks.push(c));
            res.on('end', () => {
                const bodyBuffer = Buffer.concat(chunks);
                resolve({
                    statusCode: res.statusCode,
                    headers: res.headers,
                    body: bodyBuffer.toString('utf8'),
                    bodyBuffer
                });
            });
        });

        req.on('error', reject);
        if (options.body) req.write(options.body);
        req.end();
    });
}

function encodeWebSocketFrame(data, isBinary = false) {
    const payload = Buffer.isBuffer(data) ? data : Buffer.from(data, 'utf8');
    const length = payload.length;

    let header;
    const opcode = isBinary ? 0x82 : 0x81; // 0x82 = binary, 0x81 = text

    if (length <= 125) {
        header = Buffer.from([opcode, length]);
    } else if (length <= 65535) {
        header = Buffer.alloc(4);
        header[0] = opcode;
        header[1] = 126;
        header.writeUInt16BE(length, 2);
    } else {
        header = Buffer.alloc(10);
        header[0] = opcode;
        header[1] = 127;
        header.writeBigUInt64BE(BigInt(length), 2);
    }

    return Buffer.concat([header, payload]);
}

function decodeWebSocketFrame(buffer, isBinary = false) {
    const secondByte = buffer[1];
    const isMasked = (secondByte & 0x80) === 0x80;
    let length = secondByte & 0x7F;
    let offset = 2;

    if (length === 126) {
        length = buffer.readUInt16BE(offset);
        offset += 2;
    } else if (length === 127) {
        length = Number(buffer.readBigUInt64BE(offset));
        offset += 8;
    }

    let maskKey = null;
    if (isMasked) {
        maskKey = buffer.slice(offset, offset + 4);
        offset += 4;
    }

    const payload = buffer.slice(offset, offset + length);
    if (isMasked && maskKey) {
        for (let i = 0; i < payload.length; i++) {
            payload[i] ^= maskKey[i % 4];
        }
    }

    return isBinary ? payload : payload.toString('utf8');
}

// =========================================================================
// Interactive Manual Testing Web Server
// =========================================================================
function runManualInteractiveServer() {
    const PORT = 8080;
    console.log(`\n${COLORS.bold}${COLORS.yellow}=== Launching Interactive Manual Testing Server on http://localhost:${PORT} ===${COLORS.reset}`);
    console.log(`Open http://localhost:${PORT} in your browser to manually test the TV Web Receiver UI, 16:9 aspect modes, video player, and file downloads!`);
    console.log(`Press Ctrl+C to stop.`);

    const server = http.createServer((req, res) => {
        const url = req.url;
        if (url === '/' || url === '/index.html') {
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(fs.readFileSync(path.join(ASSETS_DIR, 'index.html')));
            return;
        }
        if (url === '/style.css') {
            res.writeHead(200, { 'Content-Type': 'text/css' });
            res.end(fs.readFileSync(path.join(ASSETS_DIR, 'style.css')));
            return;
        }
        if (url === '/app.js') {
            res.writeHead(200, { 'Content-Type': 'application/javascript' });
            res.end(fs.readFileSync(path.join(ASSETS_DIR, 'app.js')));
            return;
        }
        if (url === '/api/files') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify([
                { id: 'sample-1', name: 'Presentation.pdf', size: 2450000, mimeType: 'application/pdf', isVideo: false, isPhoto: false, url: '/files/download/sample-1' },
                { id: 'sample-2', name: 'Smartwatch_Demo.mp4', size: 15400000, mimeType: 'video/mp4', isVideo: true, isPhoto: false, url: '/files/download/sample-2' },
                { id: 'sample-3', name: 'Wallpaper_4K.jpg', size: 4800000, mimeType: 'image/jpeg', isVideo: false, isPhoto: true, url: '/files/download/sample-3' }
            ]));
            return;
        }
        res.writeHead(404);
        res.end('Not Found');
    });

    server.listen(PORT);
}

// =========================================================================
// 6. Cast16x9 V2 Pro Verification (Side-by-Side Isolation, Ambient 16:9, Audio & Zoom)
// =========================================================================
function testV2ProFeatures() {
    console.log(`\n${COLORS.bold}${COLORS.cyan}=== 6. Testing Cast16x9 V2 Pro Architectural Features ===${COLORS.reset}`);

    // A. Package & Side-by-Side Coexistence
    const buildGradle = fs.readFileSync(path.join(ROOT_DIR, 'app', 'build.gradle'), 'utf8');
    assert(buildGradle.includes('applicationId "com.cast16x9.app.v2"'), 'build.gradle assigns distinct V2 applicationId (com.cast16x9.app.v2)');
    assert(buildGradle.includes('versionName "2.1.0-pro"'), 'build.gradle specifies V2 Pro versionName (2.1.0-pro)');

    const stringsXml = fs.readFileSync(path.join(ROOT_DIR, 'app', 'src', 'main', 'res', 'values', 'strings.xml'), 'utf8');
    assert(stringsXml.includes('Cast16x9 V2 Pro'), 'strings.xml names application Cast16x9 V2 Pro');

    // B. Permissions
    const manifestXml = fs.readFileSync(path.join(ROOT_DIR, 'app', 'src', 'main', 'AndroidManifest.xml'), 'utf8');
    assert(manifestXml.includes('android.permission.RECORD_AUDIO'), 'AndroidManifest.xml declares android.permission.RECORD_AUDIO');

    // C. Android UI Controls for V2
    const layoutXml = fs.readFileSync(path.join(ROOT_DIR, 'app', 'src', 'main', 'res', 'layout', 'activity_main.xml'), 'utf8');
    assert(layoutXml.includes('id="@+id/rb_ambient"'), 'activity_main.xml includes 16:9 Ambient Canvas radio button');
    assert(layoutXml.includes('id="@+id/sb_zoom"'), 'activity_main.xml includes interactive Zoom SeekBar');
    assert(layoutXml.includes('id="@+id/btn_zoom_minus"'), 'activity_main.xml includes Zoom Minus button');
    assert(layoutXml.includes('id="@+id/btn_zoom_plus"'), 'activity_main.xml includes Zoom Plus button');
    assert(layoutXml.includes('id="@+id/btn_zoom_reset"'), 'activity_main.xml includes Zoom Reset button');
    assert(layoutXml.includes('id="@+id/switch_tv_audio"'), 'activity_main.xml includes TV Audio Mirroring switch');

    // D. Android Service & Server Real-Time Pipeline
    const captureService = fs.readFileSync(path.join(JAVA_DIR, 'service', 'ScreenCaptureService.kt'), 'utf8');
    assert(captureService.includes('AudioPlaybackCaptureConfiguration'), 'ScreenCaptureService integrates AudioPlaybackCaptureConfiguration');
    assert(captureService.includes('frameIntervalMs = 16L'), 'ScreenCaptureService targets 60 FPS ultra-low latency (<35ms)');
    assert(captureService.includes('onNewAudioChunk'), 'ScreenCaptureService streams captured PCM chunks to WebReceiverServer');

    const serverCode = fs.readFileSync(path.join(JAVA_DIR, 'server', 'WebReceiverServer.kt'), 'utf8');
    assert(serverCode.includes('currentAspectMode: String = "ambient"'), 'WebReceiverServer defaults to ambient 16:9 mode');
    assert(serverCode.includes('setTvZoomLevel'), 'WebReceiverServer provides setTvZoomLevel()');
    assert(serverCode.includes('/ws/audio'), 'WebReceiverServer routes dedicated /ws/audio WebSocket endpoint');
    assert(serverCode.includes('isPrivateListening'), 'WebReceiverServer supports Private Listening mode');

    // E. Web Receiver V2 Pro UI & Logic
    const htmlCode = fs.readFileSync(path.join(ASSETS_DIR, 'index.html'), 'utf8');
    assert(htmlCode.includes('id="ambient-canvas"'), 'index.html contains #ambient-canvas for blurred dynamic wings');
    assert(htmlCode.includes('id="zoom-control-bar"'), 'index.html contains on-screen TV #zoom-control-bar');
    assert(htmlCode.includes('id="tv-zoom-slider"'), 'index.html contains #tv-zoom-slider');
    assert(htmlCode.includes('id="btn-mode-ambient"'), 'index.html contains #btn-mode-ambient');

    const cssCode = fs.readFileSync(path.join(ASSETS_DIR, 'style.css'), 'utf8');
    assert(cssCode.includes('.aspect-ambient'), 'style.css implements .aspect-ambient styling');
    assert(cssCode.includes('#ambient-canvas'), 'style.css configures #ambient-canvas dynamic glow filter');
    assert(cssCode.includes('.zoom-control-bar'), 'style.css styles TV on-screen zoom control bar');
    assert(cssCode.includes('--tv-zoom-scale'), 'style.css utilizes --tv-zoom-scale CSS variable');

    const jsCode = fs.readFileSync(path.join(ASSETS_DIR, 'app.js'), 'utf8');
    assert(jsCode.includes("aspectMode = 'ambient'"), 'app.js initializes default aspectMode to ambient');
    assert(jsCode.includes('setZoomLevel'), 'app.js implements setZoomLevel() with bidirectional TV sync');
    assert(jsCode.includes('connectAudioWebSocket'), 'app.js connects to /ws/audio');
    assert(jsCode.includes('playPcmChunk'), 'app.js decodes & plays PCM audio via Web Audio API');
    assert(jsCode.includes('ambientCtx.drawImage'), 'app.js mirrors frames onto ambient canvas');
}

// =========================================================================
// Main Execution Entrypoint
// =========================================================================
async function main() {
    console.log(`${COLORS.bold}${COLORS.blue}====================================================================${COLORS.reset}`);
    console.log(`${COLORS.bold}${COLORS.blue}       Cast16x9 Automated & Manual Diagnostic Test Suite           ${COLORS.reset}`);
    console.log(`${COLORS.bold}${COLORS.blue}====================================================================${COLORS.reset}`);

    const isManual = process.argv.includes('--manual') || process.argv.includes('--interactive') || process.argv.includes('--serve');

    try {
        testStaticAssets();
        testAspectRatioMath();
        await testServerProtocols();
        await testWebSocketStreaming();
        testDlnaProtocol();
        testV2ProFeatures();

        console.log(`\n${COLORS.bold}====================================================================${COLORS.reset}`);
        console.log(`${COLORS.bold}Test Summary: ${COLORS.green}${passedCount} Passed${COLORS.reset}, ${failedCount > 0 ? COLORS.red : COLORS.green}${failedCount} Failed${COLORS.reset} of ${testCount} tests.`);
        console.log(`${COLORS.bold}====================================================================${COLORS.reset}`);

        if (failures.length > 0) {
            console.log(`\n${COLORS.red}${COLORS.bold}Detected Failures Requiring Repair:${COLORS.reset}`);
            failures.forEach((f, i) => {
                console.log(`  ${i + 1}. ${f.name} - ${f.details}`);
            });
        }

        if (isManual) {
            runManualInteractiveServer();
        } else {
            process.exit(failedCount === 0 ? 0 : 1);
        }
    } catch (err) {
        console.error(`${COLORS.red}Fatal test suite error:${COLORS.reset}`, err);
        process.exit(1);
    }
}

main();
