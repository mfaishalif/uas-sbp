# Prosedur penilaian subagent v2

Label relevansi studi ini dibuat oleh dua reviewer AI independen dan seorang adjudikator AI. Tidak ada
label yang dimasukkan ke lembar penilaian manusia dan `humanValidated` tetap `false`.

## Identitas dan konteks

| Peran | Identitas | Model | Konteks |
| --- | --- | --- | --- |
| Reviewer awal A | `reviewer-a` | `opencode-go/deepseek-v4.1-flash` | sesi terpisah |
| Reviewer awal B | `reviewer-b` | `opencode-go/deepseek-v4.1-flash` | sesi terpisah |
| Adjudikator | `adjudicator` | `opencode-go/deepseek-v4.1-flash` | sesi baru, hanya melihat pasangan yang berbeda |

Setiap reviewer menerima paket buta berisi identitas pasangan (`queryId`, `cfpbId`), narasi query,
narasi kasus, dan kategori kedua sisi. Paket tidak memuat skor, peringkat, konfigurasi, label lama,
pembagian dataset, atau penanda query negatif. Prompt lengkap ada di `prompt-review.md` dan
`prompt-adjudication.md`. Checksum paket dicatat di `input-checksums.json`.

## Definisi label

`relevant=1` hanya jika kasus membantu memahami atau menangani **mekanisme masalah yang sama**.
Kesamaan kata atau kategori saja tidak cukup. Kegagalan setoran ATM dan kegagalan penarikan ATM
adalah mekanisme berbeda. Penagihan ganda, transaksi tidak sah, transfer tertunda, saldo ditahan, dan
biaya juga harus dibedakan menurut mekanisme yang dijelaskan kasus.

Setiap baris wajib memiliki alasan spesifik dan kutipan verbatim dari narasi kasus. Kutipan harus
muncul persis (setelah normalisasi spasi dan kapitalisasi) di narasi kasus; validasi ini dijalankan
oleh runner evaluasi.

## Berkas keluaran

- `calibration-review-a.csv`, `calibration-review-b.csv`
- `calibration-adjudication.csv`
- `calibration-final.csv`
- `final-review-a.csv`, `final-review-b.csv`
- `final-adjudication.csv`
- `final-final.csv`

File review awal memakai kolom
`queryId,cfpbId,relevant,reason,evidence,reviewer`. File adjudikasi memakai kolom
`queryId,cfpbId,reviewerARelevant,reviewerBRelevant,adjudicator,relevant,reason,evidence`.
File final memakai kolom yang sama dengan review awal. Label final untuk pasangan yang disepakati
memakai alasan dan bukti reviewer; label final untuk pasangan yang berbeda memakai keputusan
adjudikator.

## Aturan penggabungan

1. Kedua reviewer harus menilai setiap pasangan pada kandidat bagian tersebut.
2. Pasangan yang disepakati langsung menjadi label final.
3. Setiap pasangan yang berbeda wajib memiliki tepat satu baris adjudikasi.
4. Label final harus sama dengan kedua reviewer untuk pasangan yang disepakati, atau dengan
   adjudikator untuk pasangan yang berbeda.
5. Label hilang, duplikat, kutipan yang tidak ditemukan, atau adjudikasi tidak lengkap menggagalkan
   evaluasi.

Kesepakatan awal dan Cohen's kappa dihitung dari kedua review awal. Kappa dilaporkan `null` bila
tidak terdefinisi (misalnya kedua reviewer memberi label yang sama untuk semua pasangan).
