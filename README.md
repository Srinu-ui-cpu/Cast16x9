# Cast16x9 V2 Pro — 16:9 Ambient Canvas, Audio Mirroring & Zoom Adjuster

> **Cast your Smartwatch (1:1) or Smartphone (20:9) to any TV in true 16:9 full-screen with ZERO black bars, dynamic ambient wings, real-time zoom controls, and system audio — No TV app or Chromecast needed!**

---

## 🌟 What's New in V2 Pro

### 1. ✨ 16:9 Ambient Canvas (100% Full Phone Screen, Zero Cropping)
- **Problem in Classic 16:9**: Conventional cover/zoom cropped off ~70% of tall (19.5:9 / 20:9) phone screens until a scrollable page or video opened.
- **The V2 Pro Solution**:
  - Displays **100% of the phone screen** from the top notification bar to the bottom navigation buttons with zero cropping.
  - Dynamically projects **hardware-accelerated blurred ambient wings** onto the left and right TV borders.
  - Colors blend smoothly with whatever is on your phone screen in real time — eliminating ugly black bars forever!

### 2. 🔍 Interactive Real-Time Zoom & Scale Adjuster
- Adjust zoom smoothly from **50% to 150%**:
  - **On the TV screen**: Floating on-screen zoom control bar with slider and `−` / `+` / `Reset` buttons.
  - **From the phone app**: Interactive Zoom SeekBar with real-time bidirectional WebSocket synchronization.
  - **TV Remote Shortcuts**: Press `4` to zoom out, `5` to zoom in, and `0` to reset to 100%.

### 3. 🔊 Screen Mirroring Audio & 🎧 Private Listening Mode
- Streams live system audio, gameplay sound, YouTube/streaming audio, and app sound directly to your TV speakers.
- **Private Listening**: 1-tap mobile switch keeps audio playing privately through your phone speakers or Bluetooth headphones while mirroring 4K video to the TV screen.

### 4. ⏹️ Immediate Media Stop & Instant TV Storage Freeing
- Canceling a beamed video or photo from your phone immediately pauses, unloads, and frees the TV browser player and IndexedDB memory without requiring a page refresh!

### 5. 🎬 6GB+ Movie Seeking & Glitch-Free Database Caching
- **Authoritative 64-bit Linux Kernel `fstat` & `lseek`**: Directly queries kernel inodes via `Os.fstat(fd).st_size` and sets offsets with `Os.lseek(fd, start, SEEK_SET)`. Instant `< 15ms` seeking even on massive 6 GB movies.
- **WebSocket `init_state` Guard**: Active playback is protected against background reconnects, permanently preventing glitched restarts back to 00:00.

### 6. 📱 Side-by-Side Coexistence with V1
- Dedicated package namespace: `com.cast16x9.app.v2`.
- Install both **Cast16x9 V1** and **Cast16x9 V2 Pro** simultaneously on your Android device without conflict!

---

## 🚀 Quick Start Guide

1. Connect your **Phone** and **Smart TV** to the same Wi-Fi network.
2. Open **Cast16x9 V2 Pro** on your phone.
3. Open the TV web browser and navigate to the IP address displayed on your phone (e.g. `http://192.168.1.15:8080`) or scan the on-screen QR code.
4. **For Screen Mirroring**:
   - Tap **"Start Screen Mirroring"** in the app.
   - Enjoy full 16:9 Ambient Canvas with dynamic side glow and real-time zoom controls.
5. **For Media Beaming**:
   - Tap any video or photo to beam it to your TV with 0-latency playback and instant seeking.
   - Tap **"Stop Cast"** at any time to instantly stop playback on the TV.

---

## 🛠️ Tech Stack & Architecture

- **Android Service**: Kotlin, `MediaProjection`, `VirtualDisplay`, `AudioPlaybackCapture`, Foreground Service
- **Embedded Web Server**: NanoWSD (HTTP 1.1 + WebSocket binary streaming)
- **Kernel File I/O**: Android POSIX `Os.lseek` & `Os.fstat` for O(1) hardware range streaming
- **TV Receiver**: HTML5 Canvas, Web Audio API, IndexedDB `Cast16x9_MediaDB`, CSS Hardware Compositor
- **CI/CD Pipeline**: GitHub Actions automated APK build & release

---

## 📦 Download

Download the latest release APK directly from the [Releases](https://github.com/Srinu-ui-cpu/Cast16x9-V2-Pro/releases) page or workflow artifacts.
