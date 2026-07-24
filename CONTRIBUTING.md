# Contributing

## Setup

Requires **JDK 21** and **Maven**. See [README.md](README.md#build-and-run) for how to build and
run the app, and the prerequisites section for the external tools (ffmpeg, whisper.cpp) needed to
actually use it locally.

### Use the exact same JDK the released `.msi` bundles

The installed app never uses the end user's own Java (if any) -- `jpackage` bundles a private
`jlink` runtime built from whatever JDK ran it. The release workflow builds with a specific pinned
**Eclipse Temurin 21.0.11+10** (see `java-version` in
[`.github/workflows/release.yml`](.github/workflows/release.yml)), so building/testing locally
with a *different* JDK (a different vendor, like Oracle's, or even a different Temurin patch) can
hide or introduce JDK-specific bugs that only show up for real users -- this project has already
been bitten by one (the `-XX:TieredStopAtLevel=1` JIT workaround in
[`package-installer.ps1`](package-installer.ps1)). To match it locally:

1. Download `OpenJDK21U-jdk_x64_windows_hotspot_21.0.11_10.zip` from
   [Adoptium's jdk-21.0.11+10 release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.11%2B10)
   and verify its SHA-256 against the `.sha256.txt` file on that same page.
2. Extract it to `%USERPROFILE%\.jdks\jdk-21.0.11+10` (i.e. so
   `%USERPROFILE%\.jdks\jdk-21.0.11+10\bin\java.exe` exists).
3. Set `JAVA_HOME` to that path before running `mvn`/`java` for this project (e.g. in the terminal
   you build from, or in Eclipse: Window -> Preferences -> Java -> Installed JREs -> Add..., point
   it at that same folder, then set it as this project's JRE under Project -> Properties -> Java
   Build Path -> Libraries).

### Also match the released `.msi`'s JIT configuration

`package-installer.ps1` launches the app with `-XX:TieredStopAtLevel=1` (disables the C2 JIT
compiler, keeping only C1) -- the workaround mentioned above. `mvn test` already passes this to
every test run (see the `maven-surefire-plugin` config in `pom.xml`), but running the jar directly
does not unless you pass it yourself:

```bash
mvn clean package
java -XX:TieredStopAtLevel=1 -jar target/transcritor-ata.jar
```

In Eclipse, add `-XX:TieredStopAtLevel=1` under the launch configuration's **Arguments** tab, VM
arguments box (Run -> Run Configurations... -> your launch config for `MainApp`), so runs from the
IDE match too -- without it, the app runs under the JVM's full default tiered compilation (C1+C2)
instead of what real users actually get.

## Running tests

```bash
mvn test
```

Tests don't require ffmpeg, whisper.cpp, or models -- external processes are isolated behind
interfaces and tested with mocks/fixtures.

## Code style

- All user-facing text, code, comments, and commit messages are in English.
- Keep changes focused: a bug fix doesn't need surrounding cleanup, a one-shot operation doesn't
  need a helper. Avoid adding abstractions, comments, or error handling for scenarios that can't
  happen in this codebase.
- Prefer small, focused commits over one large commit mixing unrelated concerns.

## Submitting changes

1. Make your change, add/update tests, and run `mvn clean package` locally to confirm the build
   and full test suite pass.
2. Update [CHANGELOG.md](CHANGELOG.md) under `## [Unreleased]` if the change is user-facing.
3. Open a pull request describing what changed and why.

## Releasing

Releases are built and published automatically by
[`.github/workflows/release.yml`](.github/workflows/release.yml) when a `vX.Y.Z` tag is pushed
(or via "Run workflow" in the Actions tab, giving a version number). To cut a release:

1. Bump `CURRENT` in
   [`AppVersion.java`](src/main/java/com/tailor/transcritorata/deps/AppVersion.java) and move the
   `## [Unreleased]` section of `CHANGELOG.md` under a new `## [X.Y.Z] - YYYY-MM-DD` heading.
2. Commit, push, then tag and push the tag:
   ```bash
   git tag -a vX.Y.Z -m "..."
   git push origin vX.Y.Z
   ```
3. The workflow compiles the jar, runs the tests, downloads ffmpeg/whisper.cpp, builds the `.msi`
   installer, and publishes it as a GitHub Release.

See [`package-installer.ps1`](package-installer.ps1) if you need to build the installer locally
instead (e.g. to test a packaging change before pushing a tag).
