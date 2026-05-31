# Auto-Release Signed APK on Merge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On every merge to `main`, build a release-signed APK and publish a GitHub release whose version comes from a checked-in `VERSION` file, enforcing a semver bump per PR with a `[skip release]` escape hatch.

**Architecture:** A root `VERSION` file is the single source of truth, read by Gradle (for `versionName`/`versionCode`) and by CI (for the tag). A `pull_request` workflow (`version-check.yml`) blocks merges that don't bump `VERSION` (unless the PR title says `[skip release]`). A `push`-to-`main` workflow (`release.yml`) decodes a release keystore from GitHub Secrets, builds `assembleRelease`, and publishes `v<VERSION>` with the signed APK.

**Tech Stack:** Gradle Kotlin DSL (AGP 9.1.1, Kotlin 2.2.10), GitHub Actions, `gh` CLI, Android `apksigner`/`keytool`.

**Design spec:** `docs/superpowers/specs/2026-05-30-auto-release-on-merge-design.md`

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `VERSION` | Single source of truth for the semver string | Create (seed `0.6.2`) |
| `app/build.gradle.kts` | Derive `versionName`/`versionCode` from `VERSION`; conditional release `signingConfig` from env | Modify |
| `.gitignore` | Never commit a keystore | Modify (add `*.jks`, `*.keystore`) |
| `.github/workflows/version-check.yml` | PR gate: require a semver bump (or honor `[skip release]`) | Create |
| `.github/workflows/release.yml` | Merge → signed release build → tag + GitHub release | Rework (replaces tag-triggered version) |

**Operator actions (out of repo, documented in Task 5):** generate the release keystore, set 4 GitHub Secrets, add `version-check` as a required status check on `main`.

**Testing note:** GitHub Actions workflows cannot be unit-tested locally. They are verified by (a) local Gradle builds proving the version/signing wiring, (b) YAML lint/`actionlint` if available, and (c) exercising them on the first real PR and merge (checklist in Task 6). No JVM unit tests are added; this plan touches build config and CI only.

**Task order:** 1 → 6 in sequence. Task 1 (version wiring) and Task 2 (signing) are independent of the workflow tasks but the workflows reference their outputs, so do them first.

---

## Task 1: VERSION file + Gradle version wiring

Make the APK's `versionName`/`versionCode` come from a root `VERSION` file instead of the hardcoded `1` / `"1.0"`.

**Files:**
- Create: `VERSION`
- Modify: `app/build.gradle.kts` (the `defaultConfig` block, currently lines 10–16)

- [ ] **Step 1: Create the VERSION file**

Create `VERSION` at the repo root with exactly this content (one line, trailing newline):

```
0.6.2
```

- [ ] **Step 2: Add the version-derivation helper to `app/build.gradle.kts`**

At the top of `app/build.gradle.kts`, immediately after the `plugins { ... }` block (before `android {`), add:

```kotlin
// Single source of truth for the app version: the root-level VERSION file.
// versionCode is derived as MAJOR*10000 + MINOR*100 + PATCH (MINOR/PATCH must stay <= 99).
val appVersionName: String = rootProject.file("VERSION").readText().trim()
val appVersionCode: Int = run {
    val parts = appVersionName.split(".")
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
        "VERSION must be MAJOR.MINOR.PATCH (got '$appVersionName')"
    }
    val (major, minor, patch) = parts.map { it.toInt() }
    require(minor <= 99 && patch <= 99) {
        "VERSION minor/patch must each be <= 99 for the versionCode formula (got '$appVersionName')"
    }
    major * 10000 + minor * 100 + patch
}
```

- [ ] **Step 3: Use the derived values in `defaultConfig`**

In the `defaultConfig { ... }` block, replace:

```kotlin
        versionCode = 1
        versionName = "1.0"
```

with:

```kotlin
        versionCode = appVersionCode
        versionName = appVersionName
```

- [ ] **Step 4: Build and confirm the merged manifest carries the new values**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Then confirm the values resolved (no keystore needed for debug):

```bash
grep -o 'versionName="[^"]*"\|versionCode="[^"]*"' \
  app/build/intermediates/merged_manifests/debug/*/AndroidManifest.xml \
  2>/dev/null || \
find app/build/intermediates -name AndroidManifest.xml -path '*merged*debug*' \
  -exec grep -o 'versionName="[^"]*"\|versionCode="[^"]*"' {} +
```

Expected output contains `versionCode="602"` and `versionName="0.6.2"`.

- [ ] **Step 5: Commit**

```bash
git add VERSION app/build.gradle.kts
git commit -m "$(printf 'Derive app versionName/versionCode from root VERSION file\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

## Task 2: Conditional release signing config + ignore keystores

Add a release `signingConfig` driven by env vars, attached only when a keystore is provided, so CI can sign while local/debug builds need nothing.

**Files:**
- Modify: `app/build.gradle.kts` (the `android { ... }` block — add `signingConfigs`, wire into `buildTypes.release`)
- Modify: `.gitignore`

- [ ] **Step 1: Add `signingConfigs` and wire the release buildType**

In `app/build.gradle.kts`, inside the `android { ... }` block, add a `signingConfigs` block immediately before the existing `buildTypes { ... }` block:

```kotlin
    signingConfigs {
        create("release") {
            // Populated only in CI (see .github/workflows/release.yml). Absent locally,
            // which is fine: the release buildType only attaches this config when present.
            System.getenv("KEYSTORE_FILE")?.let { ksPath ->
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
```

Then, inside `buildTypes { release { ... } }`, after the existing `proguardFiles(...)` call, add:

```kotlin
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
```

The `release` block becomes:

```kotlin
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
```

- [ ] **Step 2: Ignore keystores in `.gitignore`**

Append to `.gitignore` (after the existing `*.apk` block):

```
# Signing keystores are NEVER committed; CI gets them from GitHub Secrets.
*.jks
*.keystore
```

- [ ] **Step 3: Confirm debug build is unaffected (no keystore present)**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL (debug variant never references the release signingConfig).

- [ ] **Step 4: Confirm release build configures without a keystore (it just won't be signed locally)**

Run:

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. Because `KEYSTORE_FILE` is unset, the release APK is produced **unsigned** (or with no release signing) — that is expected for local runs; CI provides the keystore. Confirm Gradle did not error on the signing config.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts .gitignore
git commit -m "$(printf 'Add env-driven release signing config; ignore keystores\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

## Task 3: `version-check.yml` — PR gate enforcing the bump

A `pull_request` workflow that fails unless `VERSION` is a valid semver strictly greater than the highest existing `v*` tag — or the PR title contains `[skip release]`.

**Files:**
- Create: `.github/workflows/version-check.yml`

- [ ] **Step 1: Create the workflow**

Create `.github/workflows/version-check.yml` with exactly:

```yaml
name: Version check

on:
  pull_request:
    branches: [main]

jobs:
  version-check:
    runs-on: ubuntu-latest
    steps:
      - name: Check out (full history + tags)
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Validate VERSION bump (or honor [skip release])
        env:
          # Passed via env, never interpolated into the script, to avoid shell
          # injection from a crafted PR title.
          PR_TITLE: ${{ github.event.pull_request.title }}
        run: |
          set -euo pipefail

          case "$PR_TITLE" in
            *"[skip release]"*)
              echo "PR title opts out with [skip release]; skipping version-bump check."
              exit 0
              ;;
          esac

          if [ ! -f VERSION ]; then
            echo "::error::VERSION file is missing. Add a root VERSION file (e.g. 0.6.3)."
            exit 1
          fi

          VERSION="$(tr -d '[:space:]' < VERSION)"
          if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
            echo "::error::VERSION '$VERSION' is not MAJOR.MINOR.PATCH semver."
            exit 1
          fi

          HIGHEST="$(git tag -l 'v*' | sed 's/^v//' \
            | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)"

          if [ -z "$HIGHEST" ]; then
            echo "No existing v* release tag; VERSION '$VERSION' accepted."
            exit 0
          fi

          if [ "$VERSION" = "$HIGHEST" ]; then
            echo "::error::VERSION '$VERSION' equals the latest release tag v$HIGHEST — bump it (or add [skip release] to the PR title)."
            exit 1
          fi

          TOP="$(printf '%s\n%s\n' "$HIGHEST" "$VERSION" | sort -V | tail -1)"
          if [ "$TOP" != "$VERSION" ]; then
            echo "::error::VERSION '$VERSION' is lower than the latest release tag v$HIGHEST — bump it past v$HIGHEST."
            exit 1
          fi

          echo "VERSION '$VERSION' is a valid bump over v$HIGHEST."
```

- [ ] **Step 2: Lint the YAML if a linter is available (optional but preferred)**

Run (skip if `actionlint` is not installed — it is not required to proceed):

```bash
command -v actionlint >/dev/null 2>&1 && actionlint .github/workflows/version-check.yml || echo "actionlint not installed; relying on GitHub to validate"
```

Expected: no errors (or the "not installed" message).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/version-check.yml
git commit -m "$(printf 'Add PR version-check gate (semver bump or [skip release])\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

## Task 4: Rework `release.yml` — merge-triggered signed release

Replace the tag-triggered workflow with a `push`-to-`main` workflow that honors `[skip release]`, builds a signed release APK, and publishes `v<VERSION>`.

**Files:**
- Rework (overwrite): `.github/workflows/release.yml`

- [ ] **Step 1: Overwrite the workflow**

Replace the entire contents of `.github/workflows/release.yml` with exactly:

```yaml
name: Release

on:
  push:
    branches: [main]

permissions:
  contents: write

concurrency:
  group: release
  cancel-in-progress: false

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Check out (full history + tags)
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Skip if merged PR opted out with [skip release]
        id: skipcheck
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          PR_TITLE="$(gh api "repos/${GITHUB_REPOSITORY}/commits/${GITHUB_SHA}/pulls" \
            --jq '.[0].title // ""' 2>/dev/null || true)"
          echo "Associated PR title: '${PR_TITLE}'"
          case "$PR_TITLE" in
            *"[skip release]"*)
              echo "skip=true" >> "$GITHUB_OUTPUT"
              echo "PR opted out of release; nothing to do."
              ;;
            *)
              echo "skip=false" >> "$GITHUB_OUTPUT"
              ;;
          esac

      - name: Read and guard VERSION
        if: steps.skipcheck.outputs.skip == 'false'
        id: version
        run: |
          set -euo pipefail
          if [ ! -f VERSION ]; then
            echo "::error::VERSION file is missing."
            exit 1
          fi
          VERSION="$(tr -d '[:space:]' < VERSION)"
          if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
            echo "::error::VERSION '$VERSION' is not MAJOR.MINOR.PATCH semver."
            exit 1
          fi
          if git rev-parse -q --verify "refs/tags/v${VERSION}" >/dev/null; then
            echo "::error::Tag v${VERSION} already exists — VERSION was not bumped for this merge."
            exit 1
          fi
          echo "version=${VERSION}" >> "$GITHUB_OUTPUT"
          echo "Releasing v${VERSION}."

      - name: Set up JDK 17
        if: steps.skipcheck.outputs.skip == 'false'
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        if: steps.skipcheck.outputs.skip == 'false'
        uses: android-actions/setup-android@v3

      - name: Decode release keystore
        if: steps.skipcheck.outputs.skip == 'false'
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
        run: |
          set -euo pipefail
          printf '%s' "$KEYSTORE_BASE64" | base64 -d > "${RUNNER_TEMP}/release.jks"

      - name: Build signed release APK
        if: steps.skipcheck.outputs.skip == 'false'
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease

      - name: Stage APK
        if: steps.skipcheck.outputs.skip == 'false'
        run: |
          set -euo pipefail
          cp app/build/outputs/apk/release/app-release.apk \
            "freeollee-faces-${{ steps.version.outputs.version }}.apk"

      - name: Publish release
        if: steps.skipcheck.outputs.skip == 'false'
        env:
          GH_TOKEN: ${{ github.token }}
        run: >
          gh release create "v${{ steps.version.outputs.version }}"
          "freeollee-faces-${{ steps.version.outputs.version }}.apk"
          --title "v${{ steps.version.outputs.version }}"
          --target "${GITHUB_SHA}"
          --generate-notes
```

- [ ] **Step 2: Lint the YAML if a linter is available (optional)**

```bash
command -v actionlint >/dev/null 2>&1 && actionlint .github/workflows/release.yml || echo "actionlint not installed; relying on GitHub to validate"
```

Expected: no errors (or the "not installed" message).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "$(printf 'Rework release workflow: merge-triggered signed release with [skip release]\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

## Task 5: One-time operator setup (out of repo — run by the maintainer)

These steps involve credential generation and repo administration. They are NOT code and NOT committed. Run them once. (In-session, prefix shell commands with `!` so output lands in the conversation; the `gh secret`/branch-protection calls may prompt for auth.)

- [ ] **Step 1: Generate the release keystore**

Pick a strong store password and key password (can be the same). Run:

```bash
keytool -genkeypair -v -keystore freeollee-release.jks \
  -alias freeollee -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$STOREPASS" -keypass "$KEYPASS" \
  -dname "CN=FreeOllee Faces, O=Blizzard-Caron, C=US"
```

Expected: `freeollee-release.jks` created in the current directory. It is git-ignored by Task 2.

- [ ] **Step 2: Upload the four secrets**

```bash
gh secret set KEYSTORE_BASE64 < <(base64 -w0 freeollee-release.jks)
gh secret set KEYSTORE_PASSWORD --body "$STOREPASS"
gh secret set KEY_ALIAS --body "freeollee"
gh secret set KEY_PASSWORD --body "$KEYPASS"
```

Verify: `gh secret list` shows all four.

- [ ] **Step 3: Back up the keystore offline**

Move `freeollee-release.jks` (and note the passwords) to safe offline storage. Losing it means future builds can no longer update installs in place. Do NOT leave it in the repo working tree long-term.

- [ ] **Step 4: Make `version-check` a required status check on `main`**

After the `version-check.yml` workflow has run at least once (so GitHub knows the check name), enable branch protection. Either via the web UI (Settings → Branches → add rule for `main`) or:

```bash
gh api -X PUT "repos/$(gh repo view --json nameWithOwner -q .nameWithOwner)/branches/main/protection" \
  --input - <<'JSON'
{
  "required_status_checks": { "strict": true, "contexts": ["version-check"] },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null
}
JSON
```

`"strict": true` is the "Require branches to be up to date before merging" setting that mitigates the concurrent-same-version-PR collision (design spec, Error handling). `version-check` is the job name from `version-check.yml`.

---

## Task 6: End-to-end verification (after merge to main is enabled)

Workflows can only be fully verified by running on GitHub. Perform these once the above is merged and the secrets/branch-protection are in place.

- [ ] **Step 1: PR without a bump fails the check**

Open a PR whose `VERSION` is unchanged (still equal to the latest tag). Confirm the `version-check` check goes **red** with the "bump it" error.

- [ ] **Step 2: `[skip release]` waives the check**

Edit that PR's title to include `[skip release]`. Confirm `version-check` re-runs and goes **green** without a bump.

- [ ] **Step 3: A bump passes**

Remove `[skip release]`, set `VERSION` to a value above the latest tag (e.g. `0.6.3`). Confirm `version-check` goes **green**.

- [ ] **Step 4: Merge produces a signed release**

Merge a normal (non-skip) PR. Confirm `release.yml`:
- runs on the push to `main`,
- creates tag `v<VERSION>` and a GitHub release with a `freeollee-faces-<VERSION>.apk` asset,
- the asset is **release**-signed:

```bash
# Download the asset, then:
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs freeollee-faces-<VERSION>.apk
```

Expected: prints the FreeOllee release certificate (CN=FreeOllee Faces), not the Android debug cert.

- [ ] **Step 5: `[skip release]` merge does NOT release**

Merge a PR whose title has `[skip release]`. Confirm `release.yml` runs, logs "PR opted out", and creates **no** new tag/release.

- [ ] **Step 6: In-place upgrade (one-time signing transition noted)**

Install the first release-signed APK over an existing debug build → expect a signature-mismatch requiring uninstall/reinstall (one-time). Install a subsequent release over it → expect a clean in-place upgrade preserving app data.

---

## Self-review notes

- **Spec coverage:** Unit 1 → Task 1; Unit 2 → Tasks 2 & 5; Unit 3 → Task 3; Unit 4 → Task 4; operator/branch-protection → Task 5; testing section → Task 6. All four secrets and the `[skip release]` hatch appear in both workflows.
- **Env var names are consistent across tasks:** `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (Gradle reads these in Tasks 1–2; release.yml sets them in Task 4). The base64 secret `KEYSTORE_BASE64` is decoded to the path passed as `KEYSTORE_FILE`.
- **Version is read in exactly one place per consumer:** Gradle (`rootProject.file("VERSION")`) and CI (`tr -d '[:space:]' < VERSION`), both trimming whitespace identically.
- **Security:** PR titles are passed via env (`PR_TITLE`) or captured from API output into a shell variable, never interpolated into a script body.
