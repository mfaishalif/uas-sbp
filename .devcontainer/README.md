# GitHub CLI di Dev Container

Konfigurasi dan kredensial `gh` disimpan di `/home/vscode/.local/state/gh`
melalui `GH_CONFIG_DIR`. Direktori ini berada pada named volume `cli-state`,
sehingga login tetap tersimpan setelah container dibangun ulang.

Login sekali dari terminal container:

```bash
gh auth login --hostname github.com --git-protocol https --web
gh auth status
```

Jika baru mengubah konfigurasi devcontainer, jalankan
**Dev Containers: Rebuild Container** di VS Code terlebih dahulu.
Login tetap tersimpan selama volume `cli-state` tidak dihapus.
