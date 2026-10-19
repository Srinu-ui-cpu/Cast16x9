# Cast16x9 - Full Screen 16:9 TV Mirror & Media Beamer

> **Cast your Smartwatch (1:1) or Smartphone (20:9) to any TV in true full screen 16:9 with ZERO black bars — No TV APK required!**

---

## 🌟 Key Features

1. **📺 Zero Black Bars 16:9 Screen Mirroring:**
   - Whether you mirror from a **Smartwatch (1:1 aspect ratio)** or a **tall Android phone (19.5:9 / 20:9)**, standard casting creates huge black bars.
   - **Cast16x9** eliminates black bars using hardware-accelerated **16:9 Zoom/Cover** and **16:9 Stretch/Fill** modes.
   - Controlled dynamically from your phone or using the TV remote (`Key 1`, `Key 2`, `Key 3`).

2. **🎬 Direct Media Beaming (Videos & Photos):**
   - Play 1080p and 4K videos stored on your phone directly on your TV.
   - Dual casting support:
     - **TV Web Receiver:** Native HTML5 video player with HTTP Range (byte seeking) support.
     - **Native DLNA / UPnP:** Instant playback on Samsung Tizen, LG webOS, Sony, Mi TV, and Fire TV without opening any browser.

3. **📁 Over-The-Air File Sharing:**
   - Share any documents, APKs, videos, or archives from your phone.
   - Instant download or stream from your TV or PC browser.

4. **🚀 No TV App Needed:**
   - Any device with a modern browser (TV, PC, Mac, Tablet) can receive the stream by opening the local IP (e.g., `http://192.168.1.15:8080`) or scanning the on-screen QR Code.

---

## 🛠️ Project Architecture

```
Cast16x9/
├── .github/workflows/
│   └── build-apk.yml           # Automated CI/CD: Builds APK & creates GitHub Release
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml # Permissions & ScreenCaptureService
│   │   ├── assets/web/         # TV Web Receiver (HTML5 / CSS3 / WebSocket)
│   │   │   ├── index.html      # 16:9 TV Display UI
│   │   │   ├── style.css       # Aspect ratio transformation rules
│   │   │   └── app.js          # Low-latency screen receiver & media player
│   │   └── java/com/cast16x9/app/
│   │       ├── MainActivity.kt # Dashboard, QR Code, DLNA scan, 16:9 switcher
│   │       ├── service/        # ScreenCaptureService (MediaProjection + VirtualDisplay)
│   │       ├── server/         # Embedded NanoWSD (HTTP + WebSocket server)
│   │       ├── dlna/           # SSDP M-SEARCH & UPnP AVTransport controller
│   │       └── media/          # MediaScanner for Videos & Photos
└── build.gradle
```

---

## 📱 How to Use

1. Connect your **Phone / Smartwatch** and **TV** to the same Wi-Fi network.
2. Open the **Cast16x9** app on your phone.
3. Open the TV browser and enter the address shown on your phone (e.g. `http://192.168.1.15:8080`) or scan the QR code.
4. **For Screen Mirroring:**
   - Tap **"Start Screen Mirroring"** in the app.
   - Select your desired mode:
     - 🟢 **16:9 Cover / Zoom:** Eliminates black bars while preserving natural proportions.
     - 🔵 **16:9 Stretch / Fill:** Stretches edge-to-edge across the entire TV screen.
5. **For Videos & Photos:**
   - Go to the **Media Beam** tab $\to$ tap any video $\to$ tap **Play on TV**.

---

## ☁️ Automated GitHub APK Build & Hosting

This repository includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`).

### To Build the APK on GitHub:
1. Initialize git and push this repository to your GitHub account:
   ```bash
   git init
   git add .
   git commit -m "Initial commit of Cast16x9"
   git branch -M main
   git remote add origin https://github.com/<YOUR-USERNAME>/<YOUR-REPO-NAME>.git
   git push -u origin main
   ```
2. Navigate to your repository on GitHub $\to$ click the **Actions** tab.
3. The **"Build & Release Cast16x9 APK"** workflow runs automatically on Ubuntu with JDK 17 and Android SDK.
4. Once completed (approx. 2 minutes), download the built **`Cast16x9-FullTV-ScreenMirror.apk`** directly from the **Artifacts** section or **Releases**!
