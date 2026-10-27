# Evaluasi relevansi pencarian v2 — 27 Oktober 2026

> Status: **penilaian AI independen, belum divalidasi manusia** (`humanValidated=false`). Label tidak ditampilkan pada API dan tidak mengubah hasil pencarian kecuali konfigurasi yang lolos gerbang penerapan.

| Properti | Nilai |
| --- | --- |
| Studi | `finresolve-search-relevance-v2` |
| Sampel | 500 kasus, SHA-256 `682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644` |
| Query | 104 (64 pengaduan, 40 negatif) |
| Kelompok skenario baru | 40 kelompok × 2 parafrasa |
| Kalibrasi | 64 query |
| Pengujian | 40 query |
| Seed pembagian | `20261027` |
| Kamus frasa | `domain-phrases-v1` SHA-256 `60b1ad61268f5541a7cbeb40b48efb9fdcaae07afa611188d0d1ad192de726ec` |
| Baseline | `baseline/w=0.4/t=0.500` |
| Terpilih | `phrases/w=0.5/t=0.575` |
| Keputusan | **pertahankan baseline setelah pengujian** |

Kandidat lolos seleksi kalibrasi. Pengujian akhir dijalankan sekali dan tidak dipakai untuk memilih ulang.

## Audit penilaian subagent

- Kalibrasi: 467 pasangan, 437 sepakat, 30 beda; kesepakatan 93.58%, Cohen's kappa 0.8457.
- Pengujian akhir: 59 pasangan, 55 sepakat, 4 beda; kesepakatan 93.22%, Cohen's kappa 0.8626.

Reviewer: `reviewer-a` dan `reviewer-b` (deepseek-v4.1-flash, konteks terpisah), keputusan beda diselesaikan `adjudicator` (deepseek-v4.1-flash). Paket review hanya memuat identitas pasangan, narasi, dan kategori; skor, peringkat, konfigurasi, label lama, pembagian, dan penanda negatif disembunyikan. Checksum input: `9fcfe8b8255d804a21392c9eb4859d22e3187a12cb516651b42340985bf8e356` (kalibrasi), `f3a8d92022f8a2e2789fc69095df6a5c29b9c85116ba9d647826606ebda3d54c` (pengujian).

Label yang hilang, duplikat, atau adjudikasi yang belum selesai menggagalkan evaluasi. `judgments.csv` manusia tetap kosong.

## Kalibrasi (hanya bagian ini yang dipakai memilih)

Grid: 2 mode × 7 bobot kategori × 21 ambang = **294 konfigurasi**; bobot teks adalah komplemen, bobot field 0.35/0.15/0.35/0.15, limit 5.

| Metrik | Baseline saat ini | Baseline terbaik layak | Frasa terbaik layak | Terpilih |
| --- | ---: | ---: | ---: | ---: |
| precision@5 | 0.105 | 0.140 | 0.145 | 0.145 |
| Macro precision | 0.246 | 0.273 | 0.277 | 0.277 |
| Micro precision | 0.525 | 0.528 | 0.547 | 0.547 |
| Keberhasilan per query | 0.300 | 0.350 | 0.350 | 0.350 |
| Hasil pengaduan | 40 | 53 | 53 | 53 |
| Hasil negatif | 17 | 20 | 20 | 20 |
| Total hasil | 57 | 73 | 73 | 73 |
| Kegagalan negatif | 9 | 9 | 9 | 9 |
| Proporsi query negatif | 0.375 | 0.375 | 0.375 | 0.375 |

| Konfigurasi | baseline/w=0.4/t=0.500 | baseline/w=0.5/t=0.575 | phrases/w=0.5/t=0.575 | phrases/w=0.5/t=0.575 |
| Layak | ya | ya | ya | ya |

- Baseline mentah terbaik: `baseline/w=0.3/t=0.250` — precision@5 0.445, kegagalan negatif 24 (tidak layak).
- Frasa mentah terbaik: `phrases/w=0.3/t=0.250` — precision@5 0.450, kegagalan negatif 24 (tidak layak).

Kelayakan kandidat: macro precision, micro precision, dan keberhasilan per query tidak di bawah baseline; kegagalan negatif tidak melebihi baseline. Pemilihan memakai precision@5 tertinggi, lalu micro precision, keberhasilan per query, lebih sedikit kegagalan negatif, mode baseline, lalu jarak parameter.

## Pengujian akhir (sekali)

| Metrik | Baseline | Terpilih |
| --- | ---: | ---: |
| precision@5 | 0.175 | 0.242 |
| Macro precision | 0.377 | 0.494 |
| Micro precision | 0.568 | 0.644 |
| Keberhasilan per query | 0.458 | 0.583 |
| Hasil pengaduan | 37 | 45 |
| Hasil negatif | 7 | 11 |
| Total hasil | 44 | 56 |
| Query negatif mendapat hasil | 5 | 7 |
| Proporsi query negatif mendapat hasil | 0.313 | 0.438 |

| Syarat penerapan | Hasil |
| --- | --- |
| precision@5 meningkat | ya |
| macro precision tidak menurun | ya |
| micro precision tidak menurun | ya |
| keberhasilan per query tidak menurun | ya |
| kegagalan negatif tidak bertambah | **tidak** |
| **Gerbang** | **GAGAL** |

## Contoh hasil

### Keberhasilan

**Q59** (test) — Mobile check deposit held

> A mobile check deposit was accepted and then held for review, and the funds are still not available.

- `10660825` — **relevan**: Both concern a mobile check deposit whose funds were held and left unavailable.  
  _On XX/XX/XXXX I mobile deposited a check into my account then they held the funds to wait for the check to clear then they emailed me and wanted to confirm the activity on my account to make sure it wasn't suspicious and they froze my account on XX/XX/XXXX an…_
- `10660424` — **tidak relevan**: Query concerns a mobile deposit held for review; the case concerns the same check being mobile deposited twice, a duplicate-deposit problem.  
  _On XX/XX/XXXX a check was written out to XXXX XXXX XXXX XXXXXXXX for {$1400.00} it was put through as a mobile deposit by XXXX on XX/XX/XXXX. Then on XX/XX/XXXX the customer put the same check through as a mobile deposit with XXXX. Neither bank caught that it…_
- `10675165` — **relevan**: Both concern a mobile check deposit followed by a bank lock that left the deposited funds inaccessible.  
  _I made a mobile check deposit, CitiBank locked my out of my account. They said it was fraud. Now I have no access to my other funds or the check funds. Made deposit on XX/XX/scrub>XXXX_
- `10663554` — **relevan**: Both concern check-deposit funds held by the bank and unavailable to the customer.  
  _On XX/XX/XXXX my husband and i opened a joint bank account at Trustmark bank. I used a check from XXXX XXXX that had been issued to me on XX/XX/XXXX for around {$7000.00} as my deposit for the new account. When i got home i signed into my account but it read …_
- `10672637` — **relevan**: Both involve incoming funds the bank has held and made unavailable past the promised time, producing a shortfall; the case is a held-funds problem, not a delayed transfer or a different mechanism.  
  _pnc bank has held my funds for over a week and have no contacted me like they said and as a result my account is negative and incurring fees because i had payments scheduled to come out. i have no been paid for my services rendered through my business bank ac…_

### Kegagalan

**Q26** (test) — ATM withdrawal debited without dispensing cash

> The cash machine completed my withdrawal request without dispensing any bills, yet the amount was removed from my available balance.

- `10652816` — **tidak relevan**: Query concerns a withdrawal debited without cash; the case is an ATM deposit that failed to credit.  
  _I attempted to deposit a total of {$7300.00} into an ATM machine at B of A. The machine had counted around {$3300.00} of the total amount and then asked if Id like to deposit more. I opted to deposit more as I needed to deposit the remaining {$4000.00}. I pla…_

### Query negatif yang tetap mendapat hasil


**Negatif Q74** — General ATM limit and usage question
- `10688449` — Query is a general question about checking a balance at an ATM; the case is an ATM withdrawal malfunction with missing cash.
- `10652816` — Query is a general question about checking a balance at an ATM; the case is an ATM deposit failure.
- `10682105` — Query is a general question about checking a balance at an ATM; the case is an ATM withdrawal failure.

**Negatif Q77** — General duplicate charge policy question
- `10683886` — Query asks about duplicate charges; the case concerns unauthorized charges after a stolen wallet.
- `10660224` — Both concern a merchant debiting the same amount again, producing a duplicate charge that should have been reversed.
- `10657211` — Query asks what a duplicate charge looks like; the case reports unauthorized transactions, not a charge duplicated for one purchase.

**Negatif Q78** — General duplicate charge policy question
- `10673265` — Both concern a single transaction charged twice to the customer's account.

**Negatif Q83** — General card freeze question
- `10683886` — Query asks how to freeze a debit card after exposure; the case concerns unauthorized charges and a dispute after a stolen wallet, not the freeze procedure.

**Negatif Q93** — General overdraft fee policy question
- `10658469` — Both address overdraft fees and enrollment in overdraft coverage, including how fees are incurred.

**Negatif Q95** — General fee waiver question
- `10658109` — The case concerns the same fee mechanism the query asks about: a monthly maintenance fee charged on a savings account, distinct from the overdraft fee also mentioned.

**Negatif Q102** — General phishing reporting question
- `10673396` — Shared 'delivery company' wording is superficial: the query asks how to report a phishing scam text, while the case is an advance-fee puppy-sale fraud paid through Zelle where the consumer seeks a refund, not text-reporting guidance.
## Keputusan dan rollback

Kandidat gagal gerbang pengujian; default baseline **baseline/w=0.4/t=0.500** dipertahankan dan tidak ada kandidat pengganti yang dipilih dari hasil pengujian.

Rollback: `finresolve.similarity.mode=baseline`, `category-weight=0.4`, `text-weight=0.6`, `threshold=0.50`. Tidak ada migrasi database.

## Batas

- Label dibuat oleh subagent AI independen, bukan manusia; `humanValidated=false`.
- Sampel 500 kasus hanya mencakup dua produk dan tidak mewakili seluruh CFPB.
- Recall seluruh korpus tidak dihitung; kandidat dinilai pada gabungan top-5 semua konfigurasi.
- Skor kemiripan bukan probabilitas relevansi atau keberhasilan penanganan.
- Perbandingan pengujian terbatas pada baseline saat ini dan kandidat terpilih yang dibekukan.
