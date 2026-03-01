# OrbixTV Changelog

## v1.1.0 — 2026-03-01

### 🐛 Bug Fixes (Patches)

#### 1. Stable Channel ID (hash-based)
- **Sebelum:** `channel.id` berupa `Int` sequential yang di-generate dari urutan parsing (`channelId++`)
- **Sesudah:** `channel.id` berupa `String` hash dari `(name + url).hashCode().toString()`
- **Dampak:** Favorit dan riwayat tontonan tidak lagi kacau saat playlist di-update atau channel ditambah/dihapus

#### 2. Private Repository di ViewModel
- **Sebelum:** `val repository = ChannelRepository(...)` bersifat publik → Fragment bisa bypass ViewModel
- **Sesudah:** `private val repository = ChannelRepository(...)` — enkapsulasi MVVM terjaga

#### 3. Deteksi Tipe Stream Lebih Akurat
- **Sebelum:** Deteksi HLS/DASH hanya via `url.contains("playlist")` — rawan false positive
- **Sesudah:** Logika dipindah ke `M3uParser.detectStreamType()` dengan pengecekan ekstensi dan path yang lebih ketat (`.mpd`, `/dash/`, `.m3u8`, `/hls/`, `rtmp://`)

#### 4. Memory Leak Fix di PlayerActivity (Retry)
- **Sebelum:** Saat retry, player lama belum dirilis sempurna sebelum player baru dibuat → potensi listener menumpuk
- **Sesudah:** `player?.release(); player = null` eksplisit sebelum `setupPlayer()` baru dipanggil

#### 5. Network Security Config
- **Sebelum:** `android:usesCleartextTraffic="true"` global di Manifest — terlalu permisif
- **Sesudah:** Ditambahkan `res/xml/network_security_config.xml` dengan konfigurasi yang tepat

---

### ✨ Fitur Baru

#### 6. Load Playlist dari URL Eksternal
- Tombol ⚙️ di header HomeFragment membuka `PlaylistSettingsActivity`
- User bisa input URL `.m3u` / `.m3u8` dari internet
- Jika URL gagal dimuat → otomatis fallback ke playlist bawaan (assets)
- Banner peringatan muncul di HomeFragment jika sedang menggunakan fallback
- URL tersimpan di SharedPreferences, di-load otomatis saat app restart

#### 7. Picture-in-Picture (PiP)
- Tombol PiP di top bar player
- Auto-enter PiP saat user menekan tombol Home (jika stream sedang playing)
- UI overlay disembunyikan saat mode PiP aktif
- Aspect ratio 16:9
- Requires Android 8.0+ (API 26)

#### 8. Sleep Timer
- Tombol jam di top bar player
- Pilihan: 15, 30, 45, 60, 90 menit
- Badge countdown muncul di atas tombol
- Player otomatis berhenti dan Activity ditutup saat timer habis
- Bisa dibatalkan kapan saja

#### 9. Riwayat Tontonan Diperluas
- Kapasitas naik dari 20 → 30 channel terakhir
- Tombol "Hapus Riwayat" tersedia di ViewModel

---

### 📦 Technical

- `versionCode` 1 → 2
- `versionName` 1.0.0 → 1.1.0
- `Channel.id: Int` → `Channel.id: String`
- `ChannelRepository.getLastWatched()` return `List<String>` (was `List<Int>`)
- `M3uParser.parseFromAssets()` dan `parseFromUrl()` menggantikan `parse()`
- `M3uParser.parseContent()` sebagai core logic yang bisa ditest secara independen
