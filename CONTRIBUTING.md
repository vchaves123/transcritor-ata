# Contributing

## Setup

Requires **JDK 21** and **Maven**. See [README.md](README.md#build-and-run) for how to build and
run the app, and the prerequisites section for the external tools (ffmpeg, whisper.cpp) needed to
actually use it locally.

```bash
mvn clean package
java -jar target/transcritor-ata.jar
```

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

1. Bump `APP_VERSION` in
   [`AboutDialog.java`](src/main/java/com/tailor/transcritorata/gui/AboutDialog.java) and move the
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
