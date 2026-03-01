# GitHub Actions — Setup Guide

## Workflows yang tersedia

| Workflow | File | Trigger |
|----------|------|---------|
| **CI — Build Check** | `ci.yml` | Push / PR ke `main`, `master`, `develop` |
| **Build & Release** | `release.yml` | Push tag `v*.*.*` atau manual dari Actions tab |

---

## 1. Persiapan Keystore

Jika belum punya keystore, buat dengan perintah berikut:

```bash
keytool -genkey -v \
  -keystore orbixtv.jks \
  -alias orbixtv \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Encode keystore ke Base64 untuk disimpan sebagai GitHub Secret:

```bash
# macOS / Linux
base64 -i orbixtv.jks | tr -d '\n'

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("orbixtv.jks"))
```

---

## 2. Setup GitHub Repository Secrets

Buka repo GitHub → **Settings → Secrets and variables → Actions → New repository secret**

Tambahkan 4 secret berikut:

| Secret Name | Value |
|-------------|-------|
| `KEYSTORE_BASE64` | Output Base64 dari keystore di atas |
| `STORE_PASSWORD` | Password keystore |
| `KEY_ALIAS` | Alias key (contoh: `orbixtv`) |
| `KEY_PASSWORD` | Password key (biasanya sama dengan store password) |

> ⚠️ **Jangan pernah commit file `.jks` ke repository!**
> Pastikan `*.jks` dan `*.keystore` sudah ada di `.gitignore`.

---

## 3. Cara Publish Release

### Otomatis via Git Tag (recommended)

```bash
# Pastikan semua commit sudah di-push ke main
git checkout main
git pull

# Buat tag versi baru
git tag v1.0.0

# Push tag — ini akan memicu workflow release.yml secara otomatis
git push origin v1.0.0
```

Workflow akan:
1. Build APK + AAB yang sudah di-sign
2. Generate changelog otomatis dari commit sejak tag sebelumnya
3. Membuat GitHub Release dengan kedua file terlampir

### Manual via Actions Tab

1. Buka tab **Actions** di GitHub
2. Pilih workflow **Build & Release**
3. Klik **Run workflow**
4. Pilih branch dan tipe release

---

## 4. Naming Konvensi Tag

| Tag | Jenis Release |
|-----|---------------|
| `v1.0.0` | Stable release |
| `v1.1.0-beta.1` | Beta release (marked as pre-release) |
| `v2.0.0-alpha.1` | Alpha release (marked as pre-release) |
| `v1.0.1-rc.1` | Release candidate (marked as pre-release) |

Tag yang mengandung `-beta`, `-alpha`, atau `-rc` akan otomatis ditandai sebagai **pre-release** di GitHub.

---

## 5. Artifacts yang Dihasilkan

| File | Kegunaan |
|------|----------|
| `OrbixTV-vX.X.X.apk` | Install langsung di Android (sideload) |
| `OrbixTV-vX.X.X.aab` | Upload ke Google Play Store |

Artifacts juga tersimpan di tab Actions selama 30 hari meski bukan tag release.
