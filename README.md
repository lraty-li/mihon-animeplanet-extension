# Mihon Anime-Planet Extension

[![Build](https://github.com/lraty-li/mihon-animeplanet-extension/actions/workflows/build.yml/badge.svg)](https://github.com/lraty-li/mihon-animeplanet-extension/actions/workflows/build.yml)

A Mihon extension focused on Anime-Planet manga recommendations.

The extension keeps recommendation browsing lightweight and stateless: Anime-Planet provides the recommendation graph, while Bangumi and MangaDex are used only when needed to resolve localized metadata.

## Features

- Browse and search Anime-Planet manga.
- Recursive recommendations through generated tags such as `ap:recommend:berserk`.
- Chinese/CJK search fallback:
  - Anime-Planet direct search first.
  - Bangumi resolves one exact matching work to an Anime-Planet-searchable alias.
  - MangaDex is used only if Bangumi cannot resolve the work.
- Recommendation detail enrichment:
  - Bangumi is queried once with up to five candidates.
  - Candidates are matched locally against `name`, `name_cn`, and Bangumi aliases.
  - A match can provide Chinese title, author/original creator, artist, and cover in the same response.
  - MangaDex is used as a work-level fallback only when Bangumi has no exact match.
- No Anime-Planet account is required.
- No Mihon library synchronization or upload.
- No persistent recommendation seed or title mapping cache.
- Recommendation detail enrichment does not request the Anime-Planet manga detail page.

## Request model

For a recommendation item that the user actually opens:

```text
Bangumi: at most 1 search request
MangaDex: at most 1 fallback search request
Anime-Planet detail page: 0 requests
```

Recommendation lists are not batch-enriched, avoiding a burst of metadata requests for every item on the page.

## Mihon limitation

Mihon handles genre/tag taps itself. Tapping a recommendation tag opens the normal "Search / Copy to clipboard" menu; an extension cannot change that interaction.

Tag search works when the manga detail screen was opened from the Anime-Planet source browser. Mihon's global-search navigation stack does not currently route the same tag action back to the source browser.

## Build

This repository keeps the Keiyoushi `extensions-source` build infrastructure so the extension can be built with the same toolchain.

The extension module is:

```text
src/en/animeplanet
```

Build a debug APK:

```bash
./gradlew :src:en:animeplanet:assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :src:en:animeplanet:assembleDebug
```

The APK is written under:

```text
src/en/animeplanet/build/outputs/apk/debug/
```

## Release

Releases are tag-driven. Pushing a tag beginning with `v` builds the release APK and publishes it to the matching GitHub Release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Regular pushes to `main` do not publish a release.

## Upstream

Build infrastructure and extension APIs are based on [Keiyoushi extensions-source](https://github.com/keiyoushi/extensions-source).

This project is not affiliated with Anime-Planet, Bangumi, MangaDex, Mihon, Tachiyomi, or Keiyoushi.

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE).
