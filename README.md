# DriveMirror MVP

Screen mirroring via USB per Android Auto.  
**Versione: 1.0.0-MVP** — Solo video, 720p, 30fps, USB.

---

## Struttura progetto

```
DriveMirror/
├── app/src/main/
│   ├── java/com/drivemirror/
│   │   ├── DriveMirrorApp.kt              # Application class
│   │   ├── ui/
│   │   │   └── MainActivity.kt            # Entry point, permessi, UI controllo
│   │   ├── service/
│   │   │   ├── ScreenMirrorService.kt     # Foreground service: cattura + encoding pipeline
│   │   │   ├── DriveMirrorAutoService.kt  # Android Auto MediaBrowserService
│   │   │   └── AutoRenderActivity.kt      # Decoder + SurfaceView sul head unit
│   │   ├── encoder/
│   │   │   └── H264Encoder.kt             # MediaCodec H.264 hardware encoder
│   │   └── transport/
│   │       ├── UsbTransportLayer.kt       # Sender USB (lato telefono)
│   │       └── UsbReceiver.kt             # Receiver USB (lato head unit)
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml
│   │   │   └── activity_auto_render.xml
│   │   └── xml/
│   │       ├── automotive_app_desc.xml    # Dichiarazione Android Auto
│   │       └── device_filter.xml          # Filtro vendor USB
│   └── AndroidManifest.xml
├── build.gradle
└── settings.gradle
```

---

## Come compilare

### Prerequisiti
- Android Studio Hedgehog (2023.1.1) o superiore
- JDK 17
- Android SDK 34
- Dispositivo con Android 10+ (API 29+)

### Build

```bash
# Clone / apri il progetto in Android Studio
# Oppure via terminale:

./gradlew assembleDebug

# APK si trova in:
# app/build/outputs/apk/debug/app-debug.apk
```

### Installazione sideload

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Setup telefono

1. **Attiva Modalità Sviluppatore**  
   Impostazioni → Informazioni → Tocca "Numero build" 7 volte

2. **Attiva USB Debugging**  
   Impostazioni → Opzioni sviluppatore → Debug USB

3. **Attiva Android Auto per sviluppatori**  
   Android Auto → Menu → Versione → tocca 10 volte → Developer mode

4. **Installa DriveMirror APK**

5. **Collega al Head Unit via USB**

6. **Apri DriveMirror** → Accetta disclaimer → Avvia Mirroring

---

## Pipeline tecnica

```
[Schermo telefono]
       ↓ MediaProjection API
[VirtualDisplay @ 1280x720]
       ↓ Surface
[H264Encoder — MediaCodec HW]
       ↓ NAL Units (callback)
[UsbTransportLayer]
       ↓ USB Bulk Transfer
       ↓ Frame header: DMIR|length|flags
[UsbReceiver — head unit]
       ↓ parse + queue
[AutoRenderActivity]
       ↓ MediaCodec decoder
[SurfaceView — display auto]
```

**Latenza target:** < 120ms  
**Composizione tipica:** ~20ms encode + ~5ms USB + ~20ms decode = ~45ms in condizioni ottimali.

---

## Roadmap

| Fase | Feature | Stato |
|------|---------|-------|
| MVP (v1) | Video 720p, USB, nessun touch | ✅ Implementato |
| v2 | Touch injection via AccessibilityService | 🔜 Prossimo |
| v2 | Adaptive bitrate | 🔜 Prossimo |
| v3 | UI avanzata con statistiche | 📋 Pianificato |
| v3 | Modalità wireless (WiFi Direct) | 📋 Pianificato |

---

## Avvertenze legali

> ⚠️ Questo software è destinato esclusivamente a uso privato, durante la sosta o da parte del passeggero.  
> L'uso durante la guida è illegale in molte giurisdizioni.  
> L'autore non si assume responsabilità per usi impropri.  
> Non distribuibile su Google Play Store come screen mirroring tool.

---

## Distribuzione

- **APK sideload** (principale)
- Nessuna pubblicazione su Play Store per questa versione
- Richiede firma debug o firma con keystore privato
