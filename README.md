<div align="center">

# squaremap

[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/jpenilla/squaremap/build.yml?branch=master)](https://github.com/jpenilla/squaremap/actions)
[![Discord](https://img.shields.io/discord/390942438061113344?color=8C9CFE&label=discord&logo=discord&logoColor=white)](https://discord.gg/PHpuzZS)

</div>

## What is squaremap

squaremap is a minimalistic and lightweight live world map viewer for Minecraft servers.

squaremap hooks into your Minecraft server as a plugin or mod on a [supported platform](#supported-platforms), then generates and manages a live map of your server, viewable in any web browser.

## Features

* Ultra fast render times. Get your map rendered today, not next week.
* Simple vanilla-like top down 2D view, designed for navigation.
* Player markers showing yaw rotation, health, and armor.
* Easy to [setup](https://github.com/jpenilla/squaremap/wiki/Installation) and [configure](https://github.com/jpenilla/squaremap/wiki/Default-config.yml).
* Up to date Leaflet front-end.
* [Addons and integrations](https://github.com/jpenilla/squaremap/wiki/Addons) for many popular plugins.

## Supported platform in this source tree

- Minecraft 1.21.1
- [NeoForge](https://neoforged.net/)

The shared `api` and `common` modules are retained because the NeoForge implementation depends on them. Paper, Fabric, Folia and Sponge platform modules are intentionally not included.

## Downloads

Downloads can be obtained from the [releases](https://github.com/jpenilla/squaremap/releases) section.

<details>
<summary>Development builds</summary>

> Development builds are available at https://jenkins.jpenilla.xyz/job/squaremap/
</details>

## Demo

Official squaremap demo: https://squaremap-demo.jpenilla.xyz/

## License

This project is licensed under the [MIT license](https://github.com/jpenilla/squaremap/blob/master/LICENSE)

Leaflet (the web ui frontend) is licensed under [2-clause BSD License](https://github.com/Leaflet/Leaflet/blob/master/LICENSE)

## API

squaremap provides simple APIs to draw markers, shapes, icons, and etc. on rendered maps. Javadocs are hosted on the maven repository alongside the binaries, and should be automatically downloaded by your IDE. 

### Dependency Information

Releases are published to Maven Central

<details>
<summary>Using snapshot builds</summary>

> Snapshot builds are available on the Sonatype snapshots maven repository: `https://s01.oss.sonatype.org/content/repositories/snapshots/`
>
> Consult your build tool's documentation for details on adding maven repositories to your project.
</details>

Maven
```xml
<dependency>
    <groupId>xyz.jpenilla</groupId>
    <artifactId>squaremap-api</artifactId>
    <version>1.3.2</version>
    <scope>provided</scope>
</dependency>
```

Gradle
```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("xyz.jpenilla", "squaremap-api", "1.3.2")
}
```

## Building from source

Build with Java 21 and the bundled Gradle wrapper:

```bash
./gradlew clean :squaremap-neoforge:build
```

On Windows:

```bat
gradlew.bat clean :squaremap-neoforge:build
```

## Create 6 railway overlay

This source tree includes an optional Create 6.x web-map integration with tracks, curved rails, stations, signals, occupied sections and live trains. It uses squaremap's existing web server and does not require Create Track Map, Kotlin for Forge, Ktor, Fabric API or a second HTTP port.

See [`CREATE_TRACK_MAP_INTEGRATION.md`](CREATE_TRACK_MAP_INTEGRATION.md) for configuration and endpoint details.

## Backported rendering improvements

This fork includes direct saved-chunk palette decoding, bounded render scheduling, shared snapshots, full/radius render checkpoints, and ordered tile writes. Existing rendering settings and checkpoints are migrated. WebP, tile maintenance, SSE, player avatars, and the Create overlay are retained.

Optional newer blocks are resolved from the registry, including Vanilla Backport blocks; the mod is not required. Existing custom colors are preserved. Web clients use SSE with a `changes.json` fallback and reload only changed tiles. Add `?static=true` to disable automatic live polling and SSE for a static deployment.

See [BACKPORT_PLAN.md](BACKPORT_PLAN.md) for selected features and outstanding validation, and [DIAGNOSIS_1.21.1.md](DIAGNOSIS_1.21.1.md) for the initial spark/log analysis. Java tests can be run with `./gradlew :squaremap-common:test`. Browser checks are in `tools/test-web-map.cjs` (install Playwright under `build/diagnostics`; the script uses Microsoft Edge).

## Manual GitHub releases

Push/PR workflows build artifacts only. Releases are created exclusively through **Actions → Release NeoForge 1.21.1 (manual) → Run workflow** after the workflow has been pushed to GitHub.

1. Set the desired version in `gradle.properties` and update `RELEASE_NOTES.md`, then commit and push.
2. Select the branch/revision and enter `v` followed by that exact version, for example `v1.3.2-fork.2`.
3. By default, the workflow skips tests and creates a **draft prerelease**. Enable `run_tests` to run Java regression tests. Disable `draft` only when you want that manual run to publish immediately.
4. The workflow builds the selected commit, attaches only the production JAR and `SHA256SUMS`, and creates the tag at that commit if absent. An existing tag must point to the same commit; existing releases are never overwritten.

The standard `GITHUB_TOKEN` is used; write permission is limited to the release job. No Modrinth, CurseForge, or Maven publication is performed. Draft releases can be reviewed and published later from GitHub's Releases page. Remaining server/integration validation is recorded in the release notes.
