# OrbixTV Changelog

## v1.3.0 — 2026-03-02

### ✨ Fitur Baru

#### ① Auto-Retry Stream
- Saat stream gagal, player otomatis mencoba ulang hingga 2 kali (delay 2–4 detik) sebelum menampilkan error overlay ke user. Mengurangi false-error akibat timeout sementara.

#### ② Cache Logo Channel
- Glide dikonfigurasi dengan `DiskCacheStrategy.ALL` sehingga logo channel di-cache ke disk. Scrolling daftar channel jauh lebih mulus karena tidak ada network request berulang.

#### ③ Indikator Status Channel
- Setiap item channel menampilkan dot status di sudut kanan bawah logo: hijau (online), merah (offline), abu (belum dicek). Hasil ping di-cache selama 2 menit per channel agar tidak membebani jaringan.

#### ④ Lock Orientasi di Player
- Tombol lock di top bar player untuk mengunci landscape. Mencegah gangguan saat menonton sambil rebahan.

#### ⑤ Gestur Volume & Brightness
- Swipe vertikal di sisi kanan layar = volume; sisi kiri = brightness. Overlay teks muncul saat gestur aktif dan auto-fade setelah 800ms.

#### ⑥ Pencarian di Halaman Favorit
- Search bar muncul di atas daftar favorit saat jumlah favorit lebih dari 5. Filter real-time berdasarkan nama atau grup.

#### ⑦ Sort & Filter Channel
- Tombol filter di header HomeFragment membuka dialog 2-langkah: pilih urutan (Default / A–Z / Z–A / Tipe), lalu pilih filter stream (Semua / HLS / DASH / RTMP).

#### ⑧ Leanback Launcher
- App terdaftar sebagai `LEANBACK_LAUNCHER` sehingga muncul di halaman utama Android TV.

#### ⑨ Pre-buffering Channel (Zapping Cepat)
- Setelah 1 detik stream aktif, ExoPlayer kedua secara diam-diam mulai mem-buffer channel berikutnya di background. Saat user navigasi channel up/down, player langsung swap ke yang sudah di-buffer — perpindahan channel terasa instan.

#### ⑩ Home Screen Widget
- Widget 2×1 yang menampilkan channel terakhir ditonton beserta tombol play langsung ke MainActivity.

#### ⑪ Export & Import Favorit
- Tombol export/import di header halaman Favorit. Export menghasilkan file JSON di folder Documents; import baca file JSON dan tambahkan channel yang cocok. Mendukung share via Intent.

#### ⑫ Notifikasi Playlist Gagal (WorkManager)
- `PlaylistCheckWorker` berjalan setiap 6 jam via WorkManager. Jika URL playlist eksternal tidak bisa diakses, notifikasi dikirim ke system tray. Tidak aktif jika menggunakan playlist bawaan.

---


## v1.2.2 — 2026-03-01

### 🐛 UI / UX Fixes

#### 1. Konten tidak tertutup BottomNavigationView
- `rv_favorites` dan `rv_recent` kini memiliki `paddingBottom="72dp"` + `clipToPadding="false"` sehingga item terakhir tidak tersembunyi di belakang bottom nav.

#### 2. Konsistensi corner radius pada card
- Dibuat drawable baru `bg_card_rounded.xml` (radius 10dp) dan diterapkan ke seluruh card di `activity_playlist_settings.xml` (`tv_current_source`, `tv_error`, tips card) agar konsisten dengan desain `item_channel.xml`.

#### 3. Sleep timer badge — ruang teks lebih cukup
- `tv_sleep_timer` kini memiliki `minWidth="24dp"`, `gravity="center"`, `paddingHorizontal="5dp"`, dan `paddingVertical="2dp"` sehingga teks 2 digit (misal "90m") tidak terpotong.

#### 4. Panel search tidak lagi overlap dengan panel grup
- Di `fragment_home.xml`, `rv_groups` dan `rv_search` sekarang dikelola dalam satu `LinearLayout` container (`search_panel`) yang di-toggle visibility-nya secara atomik — mencegah bug keduanya tampil bersamaan.

#### 5. Aksesibilitas: `contentDescription` pada panah grup
- `iv_arrow` di `item_group.xml` kini memiliki `contentDescription="@string/expand_group"` agar TalkBack dapat membacanya dengan benar.

#### 6. Tombol prev/next channel di player controls
- `custom_player_controls.xml` kini memiliki `btn_prev_channel` (kiri play) dan `btn_next_channel` (kanan play) sehingga pengguna dapat berpindah saluran tanpa kembali ke HomeFragment.

#### 7. String hardcoded dipindahkan ke `strings.xml`
- Seluruh string literal di layout (label, hint, contentDescription, teks UI) kini menggunakan `@string/` reference untuk memudahkan lokalisasi dan A/B test copy di masa depan.


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
