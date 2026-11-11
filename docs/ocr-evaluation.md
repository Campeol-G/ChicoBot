# OCR offline evaluation

Method: `OcrCli` (`ImagePreprocessor` → `TesseractCliEngine` → `DefaultTrackLineParser`),
Tesseract 5.5.4, `por+eng`, `--psm 6`, timeout 8 s. One track per line:
`title - artist`, or title only when no artist was detected.
No personal content below: only track counts and anonymized observations.

## Golden set (6 white-theme prints, fixtures in `src/test/resources/parser/`)

Content recall after normalization (separator-insensitive: fixtures use
`title | artist`, the CLI prints `title - artist`):

| Print | CLI tracks | Expected | Content match |
| --- | --- | --- | --- |
| ytmusic01 | 8 | 8 | 8/8 |
| ytmusic02 | 9 | 9 | 9/9 |
| ytmusic03 | 9 | 9 | 9/9 |
| spotify01 | 9 | 9 | 9/9 |
| spotify02 | 9 | 9 | 9/9 |
| spotify05 | 9 | 9 | 9/9 |
| **Total** | **53** | **53** | **53/53 (100%)** |

Unit golden tests (`TrackLineParserTest`) assert the exact fixture lists
and pass.

## Clean individual prints (39 files: 20 dark + 19 white)

CLI exits 0 on all files. Representative counts:

- Near-perfect album prints: white spotify01/02/05/06/08 (9/9 each),
  dark spotify03 (8), dark ytmusic09 MJ Thriller (9/9),
  white ytmusic01/02/03/05/06/08 (8-9 each).
- Usable with OCR noise (funk/rap compilations, small glyphs):
  dark playlistyoutube01-03 (9/9/10 with artist noise),
  white playlistyoutube01-03 (9/9/10), dark spotify01 (8), dark spotify06 (8).
- Hard cases (below target, preview must catch):
  white ytmusic04 (3, cover art destroys layout),
  white ytmusic07 (2, truncated list), white ytmusic09 (4, mixed collage),
  white ytmusic10 (1, autoplay panel, not a list),
  dark ytmusic05 (2, low contrast), dark spotify08/09 (1-2, video rows),
  dark spotify02/04/05 (2-3, desktop table with date columns).

Estimated recall on clean album/list prints is at or above the ~80%
target; cover-art, truncated and collage prints are below it. The import
preview exists for exactly this reason.

## Playlist series (12 prints, 6 per theme)

YouTube Music series: raw counts 9 + 9 + 10 per theme, with the expected
1-track boundary overlaps visible in the output (`Cega Eles Vovo` between
01/02, `No Piscar dos Olhos` between 02/03). After `TrackListMerger`
(1-2 track boundary dedup, normalized title + artist) the merged unique
list is 25 tracks, exactly the cap: no "Showing N of M" overflow.
Merger behavior is pinned by `TrackListMergerTest`.

Spotify series: raw CLI counts are low (dark 2 + 11 + 2, white 5 + 3 + 2)
because cover art and tight row spacing degrade the OCR well below the
individual-print level. The 27-track merged list and the "Showing 25 of
27" cap are verified at the merger level (unit test with a 27-track
synthetic list), not end-to-end from these prints.

## Bad prints (20 files, robustness only, excluded from recall)

All 20 exit 0 with no exception and no hang: 1-5 tracks each
(crops, truncations, tiny sizes). None counts toward recall.

## Calibration

- PSM: raw word counts on two samples, `por+eng` TSV output —
  ytmusic01: psm 6 = 94 words vs psm 4 = 85; spotify01: psm 6 = 63 vs
  psm 4 = 52. Kept `--psm 6`.
- Preprocessing: grayscale + dark-background inversion handles the dark
  theme (mean < 128 inverts); 2x upscale below 800 px longest side;
  downscale above 3000 px; alpha flattened onto white; Otsu binarization
  left off by default (available via constructor flag).
- Parser fixes from this round: split reused TSV line numbers into visual
  lines by vertical gap; drop header lines containing interface words even
  with OCR garbage around them; accept English `Dec 16, 2025` dates as noise.

## v1.1 knob sweep (2026-11-10, item 20)

Method: same pipeline via `OcrCli`, with review-only env overrides
`OCR_PSM` and `OCR_BINARIZE` (production keeps PSM 6 and Otsu off).
51 top-level prints: 6 golden, 35 clean (excluding golden and hard), 10 hard;
`bad_prints/` excluded. Content match for the golden set compares normalized
titles against the fixtures in `src/test/resources/parser/`.

| Config | Golden match | Clean tracks | Hard tracks |
| --- | --- | --- | --- |
| PSM 6 (baseline) | 53/53 | 414 | 114 |
| PSM 4 | 49/53 | 365 | 88 |
| PSM 11 | 50/53 | 363 | 88 |
| PSM 6 + Otsu | 50/53 | 437 | 118 |

Automatic keep-or-defer rule: a candidate lands only when the golden set
stays 53/53 and the target-set content recall strictly improves. Every
candidate regressed the golden set, so all are deferred and the baseline is
kept. PSM 11/Otsu raise hard-print *counts* but counts are not content
recall; without ground truth for the hard set they cannot pass the rule.
Cover-art masking (Phase B) was timeboxed out after no cheap candidate
survived; recorded as deferred, not silently dropped.

## Import recall gate (item 14)

After slices 5 (simultaneous search) and 15 (text blend), measure
`enqueued / detected` on clean prints. At or above 90% the current ranking
stays; below 90% triggers duration/channel-aware ranking, official-audio
boost and a `VERSION_PENALTY` retune. This metric needs a live
Discord/YouTube/SoundCloud run, so the gate stays pending; no ranking change
was applied by default.
