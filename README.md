<div align="center">

<a href="https://aniyomi.org">
    <img src="./.github/assets/logo.png" alt="Aniyomi logo" title="Aniyomi logo" width="80"/>
</a>

# Aniyomi [App](#)

### Full-featured player and reader, based on ~~Tachiyomi~~ Mihon.
Discover and watch anime, cartoons, series, and more – easier than ever on your Android device.

A maintained fork of [Aniyomi](https://github.com/aniyomiorg/aniyomi), which is no longer updated.

[![Discord server](https://img.shields.io/discord/841701076242530374.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/F32UjdJZrR)
[![GitHub downloads](https://img.shields.io/github/downloads/Blackyfi/aniyomi-revived/total?label=downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/Blackyfi/aniyomi-revived/releases)

[![CI](https://img.shields.io/github/actions/workflow/status/Blackyfi/aniyomi-revived/build_push.yml?labelColor=27303D)](https://github.com/Blackyfi/aniyomi-revived/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/aniyomiorg/aniyomi?labelColor=27303D&color=818cf8)](/LICENSE)
[![Translation status](https://img.shields.io/weblate/progress/aniyomi?labelColor=27303D&color=946300)](https://hosted.weblate.org/engage/aniyomi/)

## Download

[![Aniyomi Stable](https://img.shields.io/github/release/Blackyfi/aniyomi-revived.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/Blackyfi/aniyomi-revived/releases)

*Requires Android 8.0 or higher.*

</div>

## Verifying a download

This app is safe to install, and you shouldn't have to take my word for it. Every release is built by GitHub Actions straight from this repository, never by hand, then signed, attested and scanned on VirusTotal, with checksums listed in the release notes. If you'd rather check for yourself, upload the APK to [VirusTotal](https://www.virustotal.com/) or any scanner you trust, or use the checks below.

**It was built from this source.** Each APK comes with a build attestation signed by GitHub, recording the exact commit and workflow run that produced it. With the [GitHub CLI](https://cli.github.com/):

```
gh attestation verify aniyomi-v0.18.1.46.apk --repo Blackyfi/aniyomi-revived
```

All attestations are also listed on the [attestations page](https://github.com/Blackyfi/aniyomi-revived/attestations).

**It is signed by us.** Every release is signed with the same key. Its certificate SHA-256 fingerprint is:

```
6D:16:C0:9F:5D:EB:49:1B:A6:BE:5B:72:5E:15:82:8C:B4:EE:A1:98:A2:9C:DE:9A:45:23:71:36:17:D4:81:EF
```

On Android, [AppVerifier](https://github.com/soupslurpr/AppVerifier) can compare it for you; on a computer, run `apksigner verify --print-certs` on the APK. Android also refuses to install an update signed with a different key over an existing install.

**It was scanned.** Each release APK is sent to VirusTotal, and the scan links are added at the bottom of the release notes.

The full source is here, so anything above can be traced back to the code.

<div align="center">

## Features

<div align="left">

* Local reading and watching of content.
* A configurable reader with multiple viewers, reading directions and other settings.
* A configurable player built on mpv-android with multiple options and settings.
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Simkl](https://simkl.com/), and [Bangumi](https://bgm.tv/) support.
* Categories to organize your library.
* Light and dark themes.
* Schedule updating your library for new chapters/episodes.
* Create backups locally to read/watch offline or to your desired cloud service.
* Plus much more...

</div>

## Contributing

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md)

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

Before reporting a new issue, take a look at the [FAQ](https://aniyomi.org/docs/faq/general), the [changelog](https://aniyomi.org/changelogs/) and the already opened [issues](https://github.com/aniyomiorg/aniyomi/issues); if you got any questions, join our [Discord server](https://discord.gg/F32UjdJZrR).

### Repositories

[![aniyomiorg/aniyomi-website - GitHub](https://github-readme-stats.vercel.app/api/pin/?username=aniyomiorg&repo=aniyomi-website&bg_color=161B22&text_color=c9d1d9&title_color=818cf8&icon_color=818cf8&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/aniyomiorg/aniyomi-website/)
[![aniyomiorg/aniyomi-mpv-lib - GitHub](https://github-readme-stats.vercel.app/api/pin/?username=aniyomiorg&repo=aniyomi-mpv-lib&bg_color=161B22&text_color=c9d1d9&title_color=818cf8&icon_color=818cf8&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/aniyomiorg/aniyomi-mpv-lib/)

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/aniyomiorg/aniyomi/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=aniyomiorg/aniyomi" alt="Aniyomi app contributors" title="Aniyomi app contributors" width="800"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2024 Aniyomi Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

</div>
