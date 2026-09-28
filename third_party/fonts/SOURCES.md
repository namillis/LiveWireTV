# Bundled fonts — sources and provenance

All three families are licensed under the SIL Open Font License 1.1 (OFL).
Full license texts are alongside this file. They are bundled in
`app/src/main/res/font/`, subset to Latin (no downloadable fonts — AOSP TV
images have no Play Services).

| Family | Version | Source | res/font files |
|---|---|---|---|
| Inter | v4.1 | https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip (static `extras/ttf/Inter-{Regular,Medium,SemiBold,Bold}.ttf`) | `inter_regular.ttf`, `inter_medium.ttf`, `inter_semibold.ttf`, `inter_bold.ttf` |
| JetBrains Mono | v2.304 | https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip (static `fonts/ttf/JetBrainsMono-{Medium,SemiBold}.ttf`) | `jetbrains_mono_medium.ttf`, `jetbrains_mono_semibold.ttf` |
| Space Grotesk | google/fonts `ofl/spacegrotesk` (variable `SpaceGrotesk[wght].ttf`, wght 300–700), retrieved 2026-09-28 | https://github.com/google/fonts/tree/main/ofl/spacegrotesk (upstream: https://github.com/floriankarsten/space-grotesk) | `space_grotesk_semibold.ttf` (wght=600), `space_grotesk_bold.ttf` (wght=700) |

## Subsetting

Performed with `fonttools subset` (fontTools 4.66.0), Unicode coverage:

- Basic Latin + Latin-1 Supplement (`U+0000–00FF`, includes `·` U+00B7)
- Punctuation / symbols: `–` U+2013, `—` U+2014, `'` U+2018, `'` U+2019,
  `"` U+201C, `"` U+201D, `•` U+2022, `…` U+2026, `‹` U+2039, `›` U+203A
- UI arrows / dot: `▲` U+25B2, `▶` U+25B6, `▼` U+25BC, `◀` U+25C0, `●` U+25CF

Layout features `tnum` and `kern` are retained. Space Grotesk is instanced from
the variable font at wght=600 and wght=700 before subsetting; it carries only
the Latin letters/digits/punctuation it needs for hero titles (the UI arrow and
`●` glyphs are not present in Space Grotesk because Display text never renders
them — they render in the Inter/JetBrains Mono contexts, which retain them).

JetBrains Mono is monospaced: all digits share one advance width (tabular by
construction), so tabular figures are always on regardless of the `tnum`
feature.

Total subset size: ~456 KB across the eight files (budget ~600 KB).
