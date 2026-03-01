# 📺 OrbixTV — IPTV Player Android

**OrbixTV** adalah aplikasi Android IPTV Player berbasis Kotlin yang siap pakai dengan playlist Indonesia dan dunia.

---

## ✨ Fitur Utama

- 🔴 **Live Streaming** — HLS (`.m3u8`) dan DASH (`.mpd`) dengan ExoPlayer/Media3
- 🔐 **ClearKey DRM** — Support stream terenkripsi (VisionPlus, IndiHome, dll.)
- 🗂️ **Grup per Negara** — Indonesia, Malaysia, Singapura, Jepang, Italia, dll.
- 🔍 **Pencarian Cepat** — Cari nama saluran atau grup
- ❤️ **Favorit** — Simpan saluran favorit dengan satu tap
- 🕐 **Riwayat Tontonan** — 20 saluran terakhir yang ditonton
- 🎨 **UI Dark Mode** — Desain elegan bertema gelap

---

## 🏗️ Arsitektur

```
app/
├── data/
│   ├── Channel.kt           # Model data
│   ├── ChannelRepository.kt # Repository pattern
│   └── M3uParser.kt         # Parser M3U playlist
├── ui/
│   ├── MainViewModel.kt     # ViewModel shared
│   ├── home/
│   │   ├── HomeFragment.kt       # Layar utama + search
│   │   ├── FavoritesFragment.kt  # Daftar favorit
│   │   ├── RecentFragment.kt     # Riwayat tontonan
│   │   ├── ChannelAdapter.kt     # RecyclerView adapter
│   │   └── GroupAdapter.kt       # Expandable group adapter
│   ├── player/
│   │   └── PlayerActivity.kt    # ExoPlayer landscape
│   └── splash/
│       └── SplashActivity.kt    # Splash screen
└── assets/
    └── playlist.m3u             # Playlist 1000+ saluran
```

---

## 🚀 Cara Menjalankan

### Prasyarat
- **Android Studio** Hedgehog (2023.1.1) atau lebih baru
- **Android SDK** API 23+
- **JDK 11+**

### Langkah

1. **Buka project** di Android Studio:
   ```
   File → Open → pilih folder OrbixTV
   ```

2. **Sync Gradle:**
   ```
   Tools → Android → Sync Project with Gradle Files
   ```

3. **Run** ke emulator atau device:
   - Minimum Android 6.0 (API 23)
   - Koneksi internet diperlukan

---

## 📦 Dependencies Utama

| Library | Versi | Kegunaan |
|---------|-------|----------|
| Media3 ExoPlayer | 1.2.1 | Video player HLS/DASH |
| Glide | 4.16.0 | Load logo channel |
| Navigation Component | 2.7.6 | Bottom navigation |
| Material Components | 1.11.0 | UI components |
| Coroutines | 1.7.3 | Async parsing |
| OkHttp | 4.12.0 | HTTP requests |

---

## 📡 Format Stream yang Didukung

| Format | Ekstensi | DRM |
|--------|----------|-----|
| HLS | `.m3u8` | Tidak ada / Token |
| MPEG-DASH | `.mpd` | ClearKey ✅ |

### Parameter URL dalam M3U:
```
URL|User-Agent=...&license_type=clearkey&license_key=kid:key&referrer=...
```

---

## 📝 Menambah Playlist Kustom

Ganti file `app/src/main/assets/playlist.m3u` dengan playlist M3U kamu sendiri, lalu rebuild.

---

## ⚠️ Disclaimer

Playlist ini bersumber dari sumber publik. Penggunaan harus sesuai dengan kebijakan masing-masing penyedia layanan. Developer tidak bertanggung jawab atas konten yang diakses.

---

**Made with ❤️ in Kotlin**
