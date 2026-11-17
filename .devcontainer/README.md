# Dev Container FinResolve

Repositori ini menyediakan dua profil Dev Container yang terpisah:

| Profil | Konfigurasi | Isi |
| --- | --- | --- |
| **FinResolve** | `.devcontainer/standard/devcontainer.json` | Java 21, Node.js 24, npm, Maven, Gradle, GitHub CLI, dan Docker outside-of-Docker. Tidak memerlukan akun AI. |
| **FinResolve AI** | `.devcontainer/ai/devcontainer.json` | Seluruh tool profil standar, ditambah Codex dan OpenCode. Wajib memiliki direktori screenshot di host. |

Kedua profil memakai satu `.devcontainer/Dockerfile` dengan target build `standard`
dan `ai`. Tidak ada konfigurasi `devcontainer.json` di root `.devcontainer`, jadi
pilihan profil selalu eksplisit.

## Memilih profil

Di VS Code:

1. Buka **Command Palette**.
2. Jalankan **Dev Containers: Open Container Configuration File...** dan pilih
   `.devcontainer/standard/devcontainer.json` atau
   `.devcontainer/ai/devcontainer.json`.
3. Jalankan **Dev Containers: Rebuild and Reopen in Container**.

Dengan Dev Containers CLI dari root proyek:

```bash
# Profil standar
devcontainer up --workspace-folder . --config .devcontainer/standard/devcontainer.json

# Profil AI (lihat bagian screenshot untuk nilai variabel yang benar)
FINRESOLVE_SCREENSHOTS_DIR="$HOME/Pictures/Screenshots" \
  devcontainer up --workspace-folder . --config .devcontainer/ai/devcontainer.json
```

Untuk masuk ke container dari CLI:

```bash
devcontainer exec --workspace-folder . --config .devcontainer/standard/devcontainer.json bash
```

## Mengganti profil dan rebuild

Hentikan atau tutup container yang sedang berjalan, pilih konfigurasi profil
lain, lalu jalankan **Dev Containers: Rebuild and Reopen in Container**. Dengan
CLI, rebuild eksplisit dapat dilakukan memakai `--remove-existing-container`:

```bash
devcontainer up --workspace-folder . \
  --config .devcontainer/standard/devcontainer.json \
  --remove-existing-container
```

Setiap profil memiliki volume sendiri. Berpindah profil tidak membawa login,
konfigurasi, atau data AI dari profil sebelumnya. Volume tidak dihapus oleh
rebuild; data baru hilang jika volume tersebut memang dihapus secara manual.

## Screenshot pada profil AI

Profil AI memasang direktori screenshot host ke
`/home/vscode/Pictures/Screenshots` secara **read-only**. Sumber mount diambil
dari environment variable host `FINRESOLVE_SCREENSHOTS_DIR`.

Persyaratan:

- Nilainya harus berupa **path absolut**.
- Direktori harus sudah ada dan dapat diakses oleh proses Docker.
- Tidak ada fallback dan tidak ada pembuatan direktori otomatis.
- Jika variabel kosong, tidak diset, atau path-nya tidak ada, mount akan gagal
  dan container tidak dibuat. Ini disengaja.

Variabel harus terlihat oleh proses VS Code, bukan hanya terminal terpisah.
Tutup semua jendela VS Code, set variabel, lalu buka VS Code dari terminal yang
sama. Pada macOS, aplikasi yang dibuka dari Dock tidak mewarisi export shell;
gunakan `code .` dari terminal atau `launchctl setenv` lalu restart VS Code.

### Windows (PowerShell)

```powershell
$env:FINRESOLVE_SCREENSHOTS_DIR = "$env:USERPROFILE\Pictures\Screenshots"
Test-Path $env:FINRESOLVE_SCREENSHOTS_DIR
code .
```

### macOS

```bash
export FINRESOLVE_SCREENSHOTS_DIR="$HOME/Pictures/Screenshots"
test -d "$FINRESOLVE_SCREENSHOTS_DIR"
code .
```

Bila VS Code selalu dibuka dari Dock:

```bash
launchctl setenv FINRESOLVE_SCREENSHOTS_DIR "$HOME/Pictures/Screenshots"
# Keluar dari VS Code, lalu buka kembali.
```

### Linux

```bash
export FINRESOLVE_SCREENSHOTS_DIR="$HOME/Pictures/Screenshots"
test -d "$FINRESOLVE_SCREENSHOTS_DIR"
code .
```

Verifikasi dari dalam container:

```bash
test -r /home/vscode/Pictures/Screenshots
touch /home/vscode/Pictures/Screenshots/.write-test
# Perintah touch harus gagal karena mount read-only.
```

Profil standar tidak memasang screenshot dan tidak membaca
`FINRESOLVE_SCREENSHOTS_DIR`.

## Login dan persistensi data

Login dilakukan dari dalam container, bukan dengan menyalin kredensial host.

Profil standar hanya menyimpan konfigurasi/login GitHub CLI:

```bash
gh auth login --hostname github.com --git-protocol https --web
gh auth status
```

Profil AI menyimpan GitHub CLI, Codex, dan OpenCode:

```bash
gh auth login --hostname github.com --git-protocol https --web
codex login
opencode auth login
```

Perintah di atas dijalankan sekali setelah container dibuat. Setup ulang tidak
menghapus isi volume.

### Volume per profil

Nama volume memakai `${devcontainerId}` sehingga terikat pada workspace dan
konfigurasi masing-masing. Akhiran nama membedakan profil:

- Profil standar: `*-standard-state` untuk konfigurasi/login `gh`.
- Profil AI:
  - `*-ai-state` untuk `gh`, home Codex, database SQLite Codex, dan database
    OpenCode;
  - `*-ai-opencode-config` untuk konfigurasi OpenCode;
  - `*-ai-opencode-data` untuk data OpenCode.

Data CLI bertahan setelah rebuild selama volume tidak dihapus. Data tidak
otomatis dibagikan antarprofil atau antarperangkat.

Konfigurasi dan login AI lama dari bind mount host tidak dimigrasikan otomatis.
Mount host untuk `.codex`, `.config/opencode`, dan `.local/share/opencode` sudah
dihapus. Pengguna profil AI melakukan login sekali lagi di container baru;
volume lama tidak dihapus oleh perubahan ini.

## Menjalankan aplikasi

Dev Container tidak menyalakan aplikasi secara otomatis. Ikuti
[panduan menjalankan demo atau development](../README.md) dari root proyek.
