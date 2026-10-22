const http = require('http');
const assert = require('assert');

// Virtual 5 GB movie size
const MOVIE_5GB = 5 * 1024 * 1024 * 1024; // 5,368,709,120 bytes (~5.37 GB)

console.log('===========================================================');
console.log('       5 GB Movie Streaming & Fast-Forward Simulation      ');
console.log('===========================================================');
console.log('Virtual Movie Size:', (MOVIE_5GB / (1024 * 1024 * 1024)).toFixed(2), 'GB (', MOVIE_5GB, 'bytes)\n');

// Server emulating WebReceiverServer.kt logic
const server = http.createServer((req, res) => {
    const rangeHeader = req.headers['range'];
    const fileLength = MOVIE_5GB;
    const mimeType = 'video/mp4';

    if (req.method === 'HEAD') {
        res.writeHead(200, {
            'Content-Type': mimeType,
            'Accept-Ranges': 'bytes',
            'Content-Length': fileLength,
            'Cache-Control': 'public, max-age=86400',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Expose-Headers': 'Content-Range, Content-Length, Accept-Ranges, ETag'
        });
        res.end();
        return;
    }

    if (rangeHeader && rangeHeader.startsWith('bytes=')) {
        let start = 0;
        let end = fileLength - 1;
        const rangeVal = rangeHeader.replace('bytes=', '').trim();

        if (rangeVal.startsWith('-')) {
            const suffix = parseInt(rangeVal.substring(1), 10);
            start = Math.max(0, fileLength - suffix);
            end = fileLength - 1;
        } else {
            const parts = rangeVal.split('-');
            start = parseInt(parts[0], 10) || 0;
            end = parts[1] ? parseInt(parts[1], 10) : fileLength - 1;
        }

        if (end >= fileLength) end = fileLength - 1;

        if (start > end || start >= fileLength) {
            res.writeHead(416, {
                'Content-Range': 'bytes */' + fileLength,
                'Accept-Ranges': 'bytes'
            });
            res.end();
            return;
        }

        const contentLength = end - start + 1;

        res.writeHead(206, {
            'Content-Type': mimeType,
            'Content-Range': 'bytes ' + start + '-' + end + '/' + fileLength,
            'Accept-Ranges': 'bytes',
            'Content-Length': contentLength,
            'Cache-Control': 'public, max-age=86400',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Expose-Headers': 'Content-Range, Content-Length, Accept-Ranges, ETag'
        });

        // Continuous streaming loop matching NanoHTTPD
        const chunkSize = 64 * 1024;
        const buf = Buffer.alloc(chunkSize, 0x42);
        let bytesRemaining = contentLength;

        function pushChunk() {
            if (bytesRemaining <= 0 || res.destroyed) {
                try { res.end(); } catch (e) {}
                return;
            }
            const toSend = Math.min(bytesRemaining, chunkSize);
            bytesRemaining -= toSend;
            const ok = res.write(buf.slice(0, toSend));
            if (ok) {
                setImmediate(pushChunk);
            } else {
                res.once('drain', pushChunk);
            }
        }
        pushChunk();
    } else {
        res.writeHead(200, {
            'Content-Type': mimeType,
            'Content-Length': fileLength,
            'Accept-Ranges': 'bytes'
        });
        res.end();
    }
});

server.listen(9999, async () => {
    console.log('✓ Simulation server active on port 9999\n');

    // Simulates an HTML5 video player buffer reader:
    // It requests a range, reads the required rolling buffer (e.g. 256 KB), then closes or seeks
    function simulatePlayerSeek(range, bytesToBuffer = 256 * 1024) {
        return new Promise((resolve, reject) => {
            const t0 = Date.now();
            const req = http.request({
                hostname: '127.0.0.1',
                port: 9999,
                path: '/media/active?id=sim5gb',
                method: 'GET',
                headers: { 'Range': range }
            }, (res) => {
                let bytesReceived = 0;
                res.on('data', chunk => {
                    bytesReceived += chunk.length;
                    if (bytesReceived >= bytesToBuffer) {
                        // Browser decoded the frame and has enough rolling buffer!
                        req.destroy(); // TV browser closes/pauses socket
                        resolve({
                            status: res.statusCode,
                            contentRange: res.headers['content-range'],
                            contentLength: res.headers['content-length'],
                            bytesBuffered: bytesReceived,
                            latencyMs: Date.now() - t0
                        });
                    }
                });
                res.on('end', () => {
                    resolve({
                        status: res.statusCode,
                        contentRange: res.headers['content-range'],
                        contentLength: res.headers['content-length'],
                        bytesBuffered: bytesReceived,
                        latencyMs: Date.now() - t0
                    });
                });
            });
            req.on('error', (err) => {
                // Socket destruction is normal when browser seeks away
                if (err.code === 'ECONNRESET') {
                    // Handled
                } else {
                    reject(err);
                }
            });
            req.end();
        });
    }

    try {
        console.log('--- Test 1: TV Video Probe (HEAD Request) ---');
        const r1 = await new Promise((resolve) => {
            http.request({
                hostname: '127.0.0.1', port: 9999, path: '/media/active?id=sim5gb', method: 'HEAD'
            }, res => {
                console.log('  HTTP Status:', res.statusCode);
                console.log('  Accept-Ranges:', res.headers['accept-ranges']);
                console.log('  Total File Size:', (parseInt(res.headers['content-length'], 10) / (1024 * 1024 * 1024)).toFixed(2), 'GB');
                console.log('  CORS Exposed Headers:', res.headers['access-control-expose-headers']);
                assert(res.statusCode === 200);
                resolve(true);
            }).end();
        });

        console.log('\n--- Test 2: Reading Moov Box at End of 5 GB File (Last 64 KB) ---');
        const r2 = await simulatePlayerSeek('bytes=-65536', 64 * 1024);
        console.log('  HTTP Status:', r2.status);
        console.log('  Content-Range:', r2.contentRange);
        console.log('  Buffered:', r2.bytesBuffered, 'bytes in', r2.latencyMs, 'ms');
        assert(r2.status === 206);

        console.log('\n--- Test 3: Fast Forward to Minute 30 (~1.5 GB into Movie) ---');
        const r3 = await simulatePlayerSeek('bytes=1500000000-', 256 * 1024);
        console.log('  HTTP Status:', r3.status);
        console.log('  Content-Range:', r3.contentRange);
        console.log('  Content-Length remaining:', (parseInt(r3.contentLength, 10) / (1024 * 1024 * 1024)).toFixed(2), 'GB');
        console.log('  Stream Latency to First Frames:', r3.latencyMs, 'ms');
        assert(r3.status === 206);

        console.log('\n--- Test 4: Fast Forward Near the End of Movie (~5.1 GB mark) ---');
        const r4 = await simulatePlayerSeek('bytes=5100000000-', 256 * 1024);
        console.log('  HTTP Status:', r4.status);
        console.log('  Content-Range:', r4.contentRange);
        console.log('  Stream Latency to First Frames:', r4.latencyMs, 'ms');
        assert(r4.status === 206);

        console.log('\n--- Test 5: Rapid-Fire Seeks (Simulating user scrubbing slider back and forth) ---');
        const seekOffsets = [
            500000000,   // ~0.5 GB (10m)
            2500000000,  // ~2.5 GB (50m)
            1200000000,  // ~1.2 GB (25m - seek backwards)
            4200000000,  // ~4.2 GB (1h 30m)
            5300000000   // ~5.3 GB (End credits)
        ];

        for (let i = 0; i < seekOffsets.length; i++) {
            const offset = seekOffsets[i];
            const gb = (offset / (1024 * 1024 * 1024)).toFixed(2);
            const res = await simulatePlayerSeek('bytes=' + offset + '-', 128 * 1024);
            console.log(`  Scrub ${i + 1} -> ${gb} GB: Status ${res.status}, Range: ${res.contentRange}, First Frame Latency: ${res.latencyMs} ms`);
            assert(res.status === 206);
        }

        console.log('\n===========================================================');
        console.log('✓ 5 GB Movie Range Streaming Simulation 100% SUCCESSFUL!');
        console.log('===========================================================');
    } catch (e) {
        console.error('Simulation Failed:', e);
    } finally {
        server.close();
    }
});
