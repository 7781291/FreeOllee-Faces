# Design: Auto-release a signed APK on merge, with semver from a VERSION file

**Date:** 2026-05-30
**Status:** Approved (brainstorming)

## Problem

Releases are cut by hand: a maintainer runs `git tag vX.Y.Z`, pushes it, and the
existing `.github/workflows/release.yml` (triggered on `push` tag `v*`) builds a
**debug**-signed APK and publishes a GitHub release with auto-generated notes
(latest: `v0.6.1`). Two problems:

1. **Manual, easy to forget / inconsistent.** The version lives only in the tag.
   `app/build.gradle.kts` hardcodes `versionCode = 1` / `versionName = "1.0"`, so
   every shipped APK reports itself as `1.0` internally regardless of the release
   name.
2. **Debug signing changes the key every CI run.** GitHub-hosted runners generate
   a fresh debug keystore per run, so each release is signed with a different key.
   Android refuses an in-place update when the signature changes, forcing an
   uninstall/reinstall that wipes app data (saved location, watch pairing, prefs).

## Goal

On merge to `main`, automatically build a **release-signed** APK and publish a
GitHub release whose version follows **semver**, sourced from a checked-in
`VERSION` file. A merge that does not bump `VERSION` fails its pull request (so it
cannot merge) unless it explicitly opts out via a `[skip release]` escape hatch.

## Non-goals

- Play Store / Play Console publishing (this app is sideloaded to a personal
  GrapheneOS device; no Play Services).
- Changelog automation beyond GitHub's `--generate-notes`.
- Conventional-commit parsing or any automatic bump inference — the version is
  chosen explicitly by the author in the `VERSION` file.
- Per-architecture / multi-variant outputs. One universal release APK.

## Architecture overview

```
PR opened ──▶ version-check.yml (pull_request)
                 │  validate VERSION: present, valid semver, > latest release tag
                 │  ── unless PR title contains [skip release] ──▶ pass
                 ▼
              merge to main ──▶ release.yml (push: main)
                                   │  resolve associated PR title
                                   │  ── [skip release]? ──▶ exit 0, no release
                                   │  re-validate tag v<VERSION> absent
                                   │  decode keystore, assembleRelease (signed)
                                   ▼
                                 tag v<VERSION> + gh release create (APK + notes)
```

Three units, each independently understandable:

1. **`VERSION` file + Gradle wiring** — the single source of truth for the version.
2. **`version-check.yml`** — a PR gate that enforces the bump (or honors skip).
3. **`release.yml`** — the merge-triggered builder/publisher.

## Unit 1: `VERSION` file as single source of truth

A plain-text file `VERSION` at the repository root containing exactly one semver
string and a trailing newline, e.g.:

```
0.6.2
```

- **Seed value:** `0.6.2` (a patch bump over the current `v0.6.1`).
- **Format:** `MAJOR.MINOR.PATCH`, digits only, no `v` prefix, no pre-release/build
  metadata. Validated by the regex `^[0-9]+\.[0-9]+\.[0-9]+$`.

### Gradle wiring (`app/build.gradle.kts`)

Replace the hardcoded `versionCode = 1` / `versionName = "1.0"` in `defaultConfig`
with values derived from the file:

- `versionName` = trimmed contents of `rootProject.file("VERSION")`.
- `versionCode` = `MAJOR * 10000 + MINOR * 100 + PATCH`.
  - `0.6.2` ⇒ `0*10000 + 6*100 + 2 = 602`.
  - Monotonic as long as the semver increases, given the **constraint** that
    `MINOR` and `PATCH` each stay ≤ 99. Acceptable for this app; documented as a
    code comment so a future 3-digit minor/patch isn't a silent surprise.

If `VERSION` is missing or malformed, the Gradle build fails fast with a clear
message (so a broken file surfaces at build time, not as a silent `0`).

> **Single source of truth:** the version is read from `VERSION` in exactly one
> place (Gradle) for the APK, and read by shell in CI for the tag. Nothing else
> hardcodes a version.

## Unit 2: Release signing from GitHub Secrets

The `release` buildType currently has **no `signingConfig`**, which is why the
existing workflow builds `assembleDebug` (an unsigned release APK won't install).
Add a release signing config wired from environment variables.

### `app/build.gradle.kts`

- Add `signingConfigs { create("release") { ... } }` reading from env vars:
  - `KEYSTORE_FILE` → `storeFile`
  - `KEYSTORE_PASSWORD` → `storePassword`
  - `KEY_ALIAS` → `keyAlias`
  - `KEY_PASSWORD` → `keyPassword`
- Attach it to the `release` buildType **conditionally**: only set
  `signingConfig = signingConfigs.getByName("release")` when `KEYSTORE_FILE` is
  present in the environment. When absent (local dev, the Pi, `assembleDebug`),
  the release config is not attached, so local builds and the debug variant keep
  working with zero secrets and no keystore.

### GitHub repository secrets

Four secrets, set once:

| Secret | Contents |
|--------|----------|
| `KEYSTORE_BASE64` | the release keystore (`.jks`), base64-encoded |
| `KEYSTORE_PASSWORD` | keystore store password |
| `KEY_ALIAS` | key alias inside the keystore |
| `KEY_PASSWORD` | key password for that alias |

### One-time operator setup (run by the maintainer, not CI)

These involve credential generation and are run by hand (e.g. via the `!`
prefix in-session, or locally):

```bash
# 1. Generate a release keystore (RSA 2048, ~27-year validity)
keytool -genkeypair -v -keystore freeollee-release.jks \
  -alias freeollee -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <STOREPASS> -keypass <KEYPASS> \
  -dname "CN=FreeOllee Faces, O=Blizzard-Caron, C=US"

# 2. Upload secrets
gh secret set KEYSTORE_BASE64 < <(base64 -w0 freeollee-release.jks)
gh secret set KEYSTORE_PASSWORD --body '<STOREPASS>'
gh secret set KEY_ALIAS --body 'freeollee'
gh secret set KEY_PASSWORD --body '<KEYPASS>'

# 3. Store freeollee-release.jks somewhere safe and OFFLINE. It is the identity
#    of the app; losing it means no future in-place updates. It is NOT committed.
```

`freeollee-release.jks` and any `*.jks`/`*.keystore` are added to `.gitignore`.

### First-release caveat

Existing installs are debug-signed (versionCode 1). The first release-keystore
build changes the signing key, so that single transition requires an
uninstall/reinstall. Every release afterward (same key, increasing versionCode)
upgrades in place and preserves app data.

## Unit 3: `version-check.yml` — PR gate (enforces the bump)

Triggered on `pull_request` targeting `main`. Its sole job: guarantee a merge
carries a version bump, so the release job downstream always has a fresh version.

Steps:

1. Check out the PR head.
2. **Skip short-circuit:** if `github.event.pull_request.title` contains the
   literal substring `[skip release]`, pass immediately (green check, no bump
   required).
3. Otherwise validate `VERSION`:
   - File exists and matches `^[0-9]+\.[0-9]+\.[0-9]+$` — else fail with a message.
   - `VERSION` is **strictly greater** (semver comparison) than the **highest**
     existing release tag — the max semver among `git tag` values matching `v*`,
     each stripped of the `v` (not the most-recent-by-date tag). If `VERSION` is
     equal-or-lower (i.e. not bumped), fail with a message telling the author to
     bump `VERSION`. If no `v*` tag exists yet, any valid semver passes.
4. On success the check passes.

This is a **required status check** in branch protection on `main`, so a PR that
neither bumps `VERSION` nor opts out cannot merge.

> The enforcement lives at PR time (not only post-merge) so it *blocks the merge*
> rather than failing after the change is already on `main`.

## Unit 4: `release.yml` — merge-triggered build + publish

Replaces the current tag-triggered trigger. Tags are now created **by** this job,
never by hand.

- **Trigger:** `push` to `main`.
- **Concurrency:** a `concurrency: { group: release, cancel-in-progress: false }`
  block serializes runs so two quick merges cannot race the same tag.
- **Permissions:** `contents: write` (create tags/releases).

Steps:

1. Check out `main` (full history + tags: `fetch-depth: 0`).
2. **Skip short-circuit:** resolve the PR associated with the pushed head commit
   via `gh api repos/{owner}/{repo}/commits/{sha}/pulls` (works for squash and
   merge-commit strategies). If that PR's title contains `[skip release]`, log and
   `exit 0` — no build, no tag, no release. A direct push with no associated PR
   has no skip signal and proceeds.
3. Read `VERSION`; **re-validate** that tag `v<VERSION>` does **not** already exist
   (guards direct pushes that bypassed the PR check, and re-runs). If it exists,
   fail.
4. Set up JDK 17 (temurin) and the Android SDK.
5. Decode `KEYSTORE_BASE64` → a `.jks` file in the runner workspace; export
   `KEYSTORE_FILE` (absolute path) + the three password/alias env vars.
6. `./gradlew :app:assembleRelease` → signed `app-release.apk`.
7. Stage it as `freeollee-faces-<VERSION>.apk`.
8. Create the tag and release:
   `gh release create "v<VERSION>" "freeollee-faces-<VERSION>.apk" --title "v<VERSION>" --generate-notes`
   (`gh release create` creates the tag at the current commit if absent).

## Data flow summary

`PR opens → version-check validates bump (or honors [skip release]) → merge →
push to main → release.yml resolves PR title, re-validates tag, builds signed
release APK, tags v<VERSION>, publishes release with APK + auto notes.`

## Error handling & edge cases

- **Merge without bump:** blocked by the required `version-check` on the PR. If a
  direct push to `main` bypasses it, `release.yml` step 3 fails on the
  tag-already-exists guard — no duplicate or overwritten release.
- **`[skip release]` merge:** PR check waived; release job exits 0. `VERSION` may
  stay unchanged; it will be released by the next non-skip merge.
- **Local / Pi builds:** no `KEYSTORE_FILE` in env → release signing config not
  attached; `assembleDebug` and the existing dev workflow are untouched.
- **Malformed `VERSION`:** caught by both the PR check (regex) and the Gradle
  build (fail-fast), so it cannot reach a release.
- **Re-run of a release job:** tag-exists guard makes it idempotent (fails the
  second attempt rather than republishing).
- **Two concurrent PRs at the same version:** both can pass `version-check`
  against the same baseline tag while open. Whichever merges first releases
  `v<VERSION>`; the second's release job then hits the tag-exists guard and fails
  post-merge. Mitigation: enable branch protection's **"Require branches to be up
  to date before merging"** on `main`, which forces the second PR to update and
  re-run `version-check`, surfacing the collision (it must bump again) *before*
  merge rather than after.
- **versionCode ceiling:** documented ≤ 99 constraint on minor/patch; revisit the
  formula only if the app ever needs a 3-digit component.

## Testing

CI workflows are validated by exercising them, not unit tests:

- **Gradle version wiring:** `./gradlew :app:assembleDebug` locally still succeeds
  with the new `VERSION`-derived values (no keystore needed); confirm
  `versionName`/`versionCode` resolve (e.g. via `:app:dependencies`-free check or
  reading the merged manifest under `app/build/intermediates`).
- **version-check:** a PR that doesn't bump `VERSION` shows a failing check; adding
  `[skip release]` to the title turns it green; bumping `VERSION` turns it green.
- **release:** the first real merge produces a `v0.6.2` release with a signed APK
  asset; `apksigner verify --print-certs` (or inspecting the asset) confirms the
  release key, and the APK installs in place over a prior release-signed build.

## Files

- **Create:** `VERSION` (seed `0.6.2`)
- **Modify:** `app/build.gradle.kts` (version wiring + release signingConfig)
- **Modify:** `.gitignore` (ignore `*.jks` / `*.keystore`)
- **Create:** `.github/workflows/version-check.yml`
- **Rework:** `.github/workflows/release.yml` (tag-trigger → push-to-main, signed
  release build, skip-release short-circuit)
- **Operator action (out of repo):** generate keystore, set 4 GitHub secrets,
  add `version-check` as a required status check on `main`.
