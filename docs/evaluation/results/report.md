# Hasil kalibrasi pencarian eksploratif — 27 Oktober 2026

> Status: **eksploratif**. Label relevansi dibuat otomatis oleh peninjauan berbantuan AI dan **belum divalidasi manusia**. Angka di bawah bukan klaim kualitas final dan tidak boleh dipakai sebagai bukti keberhasilan penanganan.

| Properti | Nilai |
| --- | --- |
| Versi algoritme | `tfidf-cosine-v1` |
| Sampel | 500 kasus, SHA-256 `682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644` |
| Seed pembagian | 20261027 |
| Baseline | w=0.4/t=0.500 (kategori/teks 0.4/0.6, ambang 0.50) |
| Konfigurasi terpilih | w=0.2/t=0.275 |
| Keputusan | **pertahankan baseline** |

## Dataset dan penilaian

Sebanyak 24 query dibekukan sebelum eksperimen: 16 skenario pengaduan dan 8 skenario negatif. Pembagian terstratifikasi memakai seed `20261027`: 10 pengaduan + 4 negatif untuk kalibrasi, dan 6 pengaduan + 4 negatif untuk pengujian akhir. Kandidat top-5 dari seluruh 147 konfigurasi digabung dan setiap pasangan query/kasus dinilai sekali tanpa skor, peringkat, atau identitas konfigurasi. Precision@5 memakai penyebut tetap 5; slot kosong dihitung tidak relevan. Precision hasil yang dikembalikan didefinisikan 0 bila tidak ada hasil. Recall tidak dilaporkan karena korpus tidak dilabeli menyeluruh.

## Kalibrasi (pemilihan hanya dari bagian ini)

| Metrik | Baseline | Terpilih |
| --- | ---: | ---: |
| Rata-rata precision@5 | 0.180 | 0.440 |
| Rata-rata precision hasil | 0.410 | 0.530 |
| Hasil pengaduan | 13 | 40 |
| Hasil negatif | 2 | 2 |
| Total hasil | 15 | 42 |
| Query negatif mendapat hasil | 2 | 2 |
| Proporsi query negatif mendapat hasil | 0.500 | 0.500 |

Konfigurasi terpilih: **w=0.2/t=0.275**. Tabel lengkap 147 konfigurasi ada di `calibration-configurations.csv`.

## Pengujian akhir (sekali, tidak dipakai untuk memilih)

| Metrik | Baseline | Terpilih |
| --- | ---: | ---: |
| Rata-rata precision@5 | 0.100 | 0.367 |
| Rata-rata precision hasil | 0.167 | 0.678 |
| Hasil pengaduan | 5 | 16 |
| Hasil negatif | 0 | 1 |
| Total hasil | 5 | 17 |
| Query negatif mendapat hasil | 0 | 1 |
| Proporsi query negatif mendapat hasil | 0.000 | 0.250 |

Gerbang penerapan: precision@5 meningkat = **ya**, precision hasil tidak turun = **ya**, kegagalan negatif tidak bertambah = **tidak**. Hasil: **GAGAL**.

## Contoh hasil pada bagian pengujian

### Keberhasilan

**Q17** — Closed account with an unreleased balance

> My bank closed my checking account without warning and still has not returned the remaining balance.

- `10696339` — **relevan**: Rekening ditutup dan saldo besar tidak dikembalikan.  
  _Good Morning, On XX/XX/XXXX J.P. Morgan Chase Bank, N.A. communicated they could no longer maintain the banking relationship and closed my bank account. The remaining balance in my account was around {$37000.00}. Between the dates of XX/XX/year> - XX/XX/year>…_
- `10685101` — **relevan**: Saldo rekening tertutup tidak dikirim meski sudah diminta.  
  _When the account was closed at the end of XXXX. There was a remaining balance in the account. Consumer contacted in XXXX for update address so check can be mailed. It was supposed to be sent out in XXXX. They asked for updated lease agreement. Still have not …_
- `10684043` — **relevan**: Rekening ditutup tanpa alasan dan setoran awal tidak dikembalikan.  
  _I've called them in numerous times over the course of the past 3 months to get this settled. I opened a checking account with them. And it's made an opening deposit of {$50.00} which they required. The very next day, they close my account without reason. Caus…_
- `10674152` — **relevan**: Rekening ditutup dan akses ke dana hilang.  
  _XXXX XXXX XXXX XXXX XXXX XXXX XXXX XXXX, MO XXXX XXXX XXXX XX/XX/XXXX XXXX XXXXXXXX XXXX XXXX XXXX : Consumer Complaint XXXX XXXX XXXX XXXX XXXX, MO XXXX Dear Sir/Madam, Subject : Formal Complaint Regarding Account Closure by US Bank and Lack of Access to Fun…_
- `10691464` — **relevan**: Rekening ditutup dan saldo tidak dapat diakses.  
  _Can not access my account have a balance and has not been sent to me closed my account for no reason_

### Kegagalan

**Q06** — Numeric and redacted date filler mixed with meaningful text

> I was charged 240 dollars on XX/XX/2024 and a 75 dollar transfer I never made appeared on my account.

- `10673035` — **tidak relevan**: Tidak menjawab masalah spesifik query; kemiripan kategori atau kata tidak cukup.  
  _I was trying to transfer money into my external account and in the bank took of my external account and the money says reversal but the account was charged money and never got it back they have messed up on 3 disputes on this before because they made a mistak…_

**Negatif Q24** (General savings question sharing deposit and account words) tetap mendapat hasil:

- `10688349` — Kasus operasional rekening tabungan tidak menjelaskan cara kerja bunga majemuk.
## Batas dan keputusan

- Label dibuat otomatis dan belum divalidasi manusia; `judgments.csv` tetap disediakan untuk penilaian manusia.
- Sampel 500 kasus hanya mencakup dua produk dan tidak mewakili seluruh CFPB.
- Kandidat dinilai pada penggabungan top-5 semua konfigurasi, sehingga perbandingan konfigurasi tetap terbatas pada kandidat tersebut.
- Tidak ada klaim recall, akurasi, atau keberhasilan penanganan.
- Konfigurasi terpilih **tidak** diterapkan; default baseline dipertahankan dan keterbatasan didokumentasikan.
