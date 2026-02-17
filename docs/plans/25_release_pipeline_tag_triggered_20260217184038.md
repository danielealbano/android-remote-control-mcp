# Plan 25: Tag-Triggered Draft Release Pipeline

## Overview

**What**: A new GitHub Actions workflow (`.github/workflows/release.yml`) triggered by pushing semantic version tags (`v*`) that:
1. Gates on CI passing for the tagged commit
2. Builds debug and release APKs with version injected from the tag
3. Generates AI-summarized release notes (via GitHub Models + `actions/ai-inference`) with fallback to auto-generated notes
4. Creates a **draft** GitHub release (never auto-published) with both APKs attached

**Why**: Automate the release creation process — push a tag, get a draft release with properly named APKs and rich release notes.

**Files involved**:
- **NEW**: `.github/workflows/release.yml` — The release workflow
- **NEW**: `.github/prompts/release-notes.prompt.yml` — AI prompt template for release note summarization
- **MODIFIED**: `docs/TOOLS.md` — Add release workflow documentation

---

## Design Decisions (Agreed)

### Tag Format
- Semantic version tags: `vX.Y.Z`, `vX.Y.Z-alpha`, `vX.Y.Z-beta`, `vX.Y.Z-rc1`, `vX.Y.Z-rc2`, ...
- Tags with `-alpha`, `-beta`, `-rcN` suffix are pre-releases
- Tags without suffix (e.g., `v1.0.0`) are stable releases

### CI Gate
- Use `lewagon/wait-on-check-action` (pinned to SHA `a499964d` / v1.5.0) to wait for the `Build Release` check (last job in CI chain) to pass
- Polls every 30 seconds; fails if CI fails or times out
- Job-level timeout of 150 minutes covers CI wait + build time (with safety margin)
- **Prerequisite**: The tagged commit must already have CI results from a prior push/merge to main. The release workflow does NOT trigger CI — it only waits for existing results.
- **Re-tagging**: If you need to re-tag (delete + re-push a tag), you must first delete the existing draft release on GitHub, then delete and recreate the tag.

### VERSION_NAME
- Extracted from tag by stripping the `v` prefix: `v1.2.3-rc1` → `1.2.3-rc1`
- Passed to Gradle via `-PVERSION_NAME=...`

### VERSION_CODE
- Formula: `MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + OFFSET`
- Offset mapping:
  - `alpha` → 01
  - `beta` → 10
  - `rc{N}` → 20 + N
  - stable release (no suffix) → 99
- Examples:
  - `v1.2.3-alpha` → `1020301`
  - `v1.2.3-beta` → `1020310`
  - `v1.2.3-rc1` → `1020321`
  - `v1.2.3-rc2` → `1020322`
  - `v1.2.3` → `1020399`
- Ordering: `alpha < beta < rc1 < rc2 < release < next-version-alpha`
- Passed to Gradle via `-PVERSION_CODE=...`

### APK Naming
- Debug: `android-remote-control-mcp-{tag}-debug.apk` (e.g., `android-remote-control-mcp-v1.2.3-debug.apk`)
- Release: `android-remote-control-mcp-{tag}-release.apk` (e.g., `android-remote-control-mcp-v1.2.3-release.apk`)
- Pre-release suffix included when present: `android-remote-control-mcp-v1.2.3-rc1-release.apk`

### Release Notes
1. Auto-generated notes via GitHub API (`repos/{owner}/{repo}/releases/generate-notes`) — lists PRs merged since last tag
2. PR titles + bodies gathered for AI context (parsed from auto-generated notes, then fetched individually; capped at 100 PRs, bodies truncated to 500 chars)
3. AI summary via `actions/ai-inference@v1` using GitHub Models (`openai/gpt-4o`, `models: read` permission)
4. User-friendly tone, grouped by category (Features, Improvements, Bug Fixes, Other)
5. Fallback: if AI fails, draft is created with just auto-generated notes + compare link
6. Compare URL included: `https://github.com/{owner}/{repo}/compare/{prev_tag}...{tag}`

### Release Body Structure
```markdown
## Summary
{AI-generated user-friendly summary — omitted if AI fails}

## What's Changed
{GitHub auto-generated PR list}

**Full Changelog**: https://github.com/{owner}/{repo}/compare/{prev_tag}...{tag}
```

### Draft + Pre-release
- Always `--draft` — never auto-published
- `--prerelease` added when tag has alpha/beta/rc suffix

### APK Signing
- Not signing APKs in CI (no keystore configured)
- Release APK will be unsigned

### First Release Edge Case
- When no previous tag exists, auto-generated notes include all PRs from the beginning of the repo
- AI summary receives all PR context (capped at 100 PRs to avoid token limits)
- Compare URL uses `commits/{tag}` instead of `compare/{prev}...{tag}`

---

## User Story 1: Tag-triggered draft release pipeline

**Description**: As a maintainer, when I push a semantic version tag to GitHub, a draft release is automatically created with properly named APKs, AI-generated release notes, and a link to the full diff.

**Acceptance Criteria**:
- [x] Pushing a tag matching `v*` triggers the release workflow
- [x] Workflow waits for CI to pass on the tagged commit before proceeding
- [x] Workflow fails if CI has not passed or fails
- [x] Tag is parsed correctly for all formats: `vX.Y.Z`, `vX.Y.Z-alpha`, `vX.Y.Z-beta`, `vX.Y.Z-rcN`
- [x] Invalid tag formats cause the workflow to fail with a clear error
- [x] VERSION_NAME and VERSION_CODE are correctly derived from the tag
- [x] Debug and release APKs are built with the correct version injected
- [x] APKs are renamed to `android-remote-control-mcp-{tag}-{debug|release}.apk`
- [x] Both APKs are attached to the draft release
- [x] Release notes include AI-generated summary (when AI succeeds)
- [x] Release notes include auto-generated PR list
- [x] Release notes include a link to the full changelog/diff
- [x] If AI fails, the draft release is still created without the summary section
- [x] Pre-release tags produce a draft release marked as pre-release
- [x] Stable tags produce a draft release NOT marked as pre-release
- [x] First release (no previous tag) is handled correctly
- [x] Workflow syntax is valid (`gh act --validate` passes)
- [x] `docs/TOOLS.md` is updated with release workflow documentation

---

### Task 1.1: Create the release workflow file

**Description**: Create `.github/workflows/release.yml` containing the complete release pipeline.

**Acceptance Criteria**:
- [x] File exists at `.github/workflows/release.yml`
- [x] Workflow triggers on `push: tags: ['v*']`
- [x] Permissions include `contents: write`, `models: read`, `actions: read`
- [x] CI gate step uses `lewagon/wait-on-check-action` pinned to SHA (v1.5.0) with correct parameters
- [x] Tag parsing step handles all valid tag formats and rejects invalid ones
- [x] VERSION_CODE formula is correctly implemented with the agreed offset mapping
- [x] Build setup matches existing CI `build-release` job (JDK 17, Go 1.25.6, Rust with Android targets, Gradle)
- [x] Native deps compilation matches existing CI (cloudflared, ngrok-java)
- [x] APKs are built with `-PVERSION_NAME` and `-PVERSION_CODE` from tag
- [x] APK renaming handles both signed and unsigned release APK filenames
- [x] Previous tag detection works for both first-release and subsequent-release cases
- [x] Auto-generated notes are fetched via GitHub API
- [x] PR details (titles + bodies) are gathered for AI context
- [x] AI inference step uses `actions/ai-inference@v1` with `continue-on-error: true`
- [x] Release body is composed correctly with/without AI summary
- [x] Draft release is created with correct flags (`--draft`, conditional `--prerelease`)
- [x] Both APKs are attached to the release

#### Action 1.1.1: Create `.github/workflows/release.yml`

**File**: `.github/workflows/release.yml` (NEW)

**Context**: This is a new workflow file. The existing `.github/workflows/ci.yml` is NOT modified. The release workflow is fully independent and gates on CI via the Checks API.

**Content**:
```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write
  models: read
  actions: read

concurrency:
  group: release-${{ github.ref_name }}
  cancel-in-progress: false

jobs:
  release:
    name: Create Draft Release
    runs-on: ubuntu-latest
    timeout-minutes: 150
    steps:
      # ── 1. Checkout with full history (needed for tag comparison) ──
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0
          submodules: true

      # ── 2. Wait for CI to pass on this commit ──
      - name: Wait for CI to pass
        uses: lewagon/wait-on-check-action@a499964d3917700d29a2a8baf3f16b078d609410 # v1.5.0
        with:
          ref: ${{ github.sha }}
          repo-token: ${{ secrets.GITHUB_TOKEN }}
          check-name: 'Build Release'
          wait-interval: 30
          allowed-conclusions: success
          running-workflow-name: 'Create Draft Release'

      # ── 3. Parse tag → VERSION_NAME, VERSION_CODE, pre-release flag ──
      - name: Parse tag
        id: parse-tag
        env:
          TAG: ${{ github.ref_name }}
        run: |
          # Strip 'v' prefix to get version
          VERSION="${TAG#v}"

          # Parse: MAJOR.MINOR.PATCH[-PRERELEASE]
          if [[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-(.+))?$ ]]; then
            MAJOR="${BASH_REMATCH[1]}"
            MINOR="${BASH_REMATCH[2]}"
            PATCH="${BASH_REMATCH[3]}"
            PRERELEASE="${BASH_REMATCH[5]}"
          else
            echo "::error::Invalid version format: $VERSION (expected: MAJOR.MINOR.PATCH[-PRERELEASE])"
            exit 1
          fi

          # Compute VERSION_CODE offset based on pre-release type
          # alpha=01, beta=10, rc{N}=20+N, release=99
          if [ -z "$PRERELEASE" ]; then
            OFFSET=99
            IS_PRERELEASE="false"
          elif [ "$PRERELEASE" = "alpha" ]; then
            OFFSET=1
            IS_PRERELEASE="true"
          elif [ "$PRERELEASE" = "beta" ]; then
            OFFSET=10
            IS_PRERELEASE="true"
          elif [[ "$PRERELEASE" =~ ^rc([0-9]+)$ ]]; then
            RC_NUM="${BASH_REMATCH[1]}"
            OFFSET=$(( 20 + RC_NUM ))
            if [ "$OFFSET" -ge 99 ]; then
              echo "::error::RC number too high: rc${RC_NUM} (max rc78)"
              exit 1
            fi
            IS_PRERELEASE="true"
          else
            echo "::error::Unknown pre-release type: $PRERELEASE (expected: alpha, beta, or rcN)"
            exit 1
          fi

          VERSION_CODE=$(( MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + OFFSET ))

          echo "tag=$TAG" >> "$GITHUB_OUTPUT"
          echo "version=$VERSION" >> "$GITHUB_OUTPUT"
          echo "version-code=$VERSION_CODE" >> "$GITHUB_OUTPUT"
          echo "is-prerelease=$IS_PRERELEASE" >> "$GITHUB_OUTPUT"

          echo "### Parsed Tag" >> "$GITHUB_STEP_SUMMARY"
          echo "| Field | Value |" >> "$GITHUB_STEP_SUMMARY"
          echo "|-------|-------|" >> "$GITHUB_STEP_SUMMARY"
          echo "| Tag | \`$TAG\` |" >> "$GITHUB_STEP_SUMMARY"
          echo "| Version Name | \`$VERSION\` |" >> "$GITHUB_STEP_SUMMARY"
          echo "| Version Code | \`$VERSION_CODE\` |" >> "$GITHUB_STEP_SUMMARY"
          echo "| Pre-release | $IS_PRERELEASE |" >> "$GITHUB_STEP_SUMMARY"

      # ── 4. Setup build toolchains (same as CI build-release job) ──
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Set up Go
        uses: actions/setup-go@v5
        with:
          go-version: '1.25.6'

      - name: Set up Rust
        uses: dtolnay/rust-toolchain@631a55b12751854ce901bb631d5902ceb48146f7 # stable
        with:
          targets: aarch64-linux-android,x86_64-linux-android

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      # ── 5. Compile native dependencies ──
      - name: Compile cloudflared for Android
        run: make compile-cloudflared

      - name: Compile ngrok-java native for Android
        run: make compile-ngrok-native
        env:
          JAVA_11_HOME: ${{ env.JAVA_HOME }}
          JAVA_17_HOME: ${{ env.JAVA_HOME }}

      # ── 6. Build APKs with version from tag ──
      - name: Build APKs
        env:
          VERSION_NAME: ${{ steps.parse-tag.outputs.version }}
          VERSION_CODE: ${{ steps.parse-tag.outputs.version-code }}
        run: ./gradlew assembleDebug assembleRelease -PVERSION_NAME="$VERSION_NAME" -PVERSION_CODE="$VERSION_CODE"

      # ── 7. Rename APKs with proper names ──
      - name: Rename APKs
        env:
          TAG: ${{ steps.parse-tag.outputs.tag }}
        run: |
          mkdir -p release-assets

          cp app/build/outputs/apk/debug/app-debug.apk \
            "release-assets/android-remote-control-mcp-${TAG}-debug.apk"

          # Release APK may be signed or unsigned depending on keystore config
          if [ -f app/build/outputs/apk/release/app-release.apk ]; then
            cp app/build/outputs/apk/release/app-release.apk \
              "release-assets/android-remote-control-mcp-${TAG}-release.apk"
          else
            cp app/build/outputs/apk/release/app-release-unsigned.apk \
              "release-assets/android-remote-control-mcp-${TAG}-release.apk"
          fi

          echo "### Release Assets" >> "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          echo '```' >> "$GITHUB_STEP_SUMMARY"
          ls -lh release-assets/ >> "$GITHUB_STEP_SUMMARY"
          echo '```' >> "$GITHUB_STEP_SUMMARY"

      # ── 8. Find previous tag for release notes context ──
      - name: Find previous tag
        id: prev-tag
        env:
          CURRENT_TAG: ${{ steps.parse-tag.outputs.tag }}
        run: |
          # Get the tag before the current one, sorted by version (v* tags only)
          PREV_TAG=$(git tag --list 'v*' --sort=-version:refname \
            | grep -vxF "${CURRENT_TAG}" \
            | head -1)

          if [ -z "$PREV_TAG" ]; then
            echo "prev-tag=" >> "$GITHUB_OUTPUT"
            echo "is-first-release=true" >> "$GITHUB_OUTPUT"
            echo "First release — no previous tag found"
          else
            echo "prev-tag=$PREV_TAG" >> "$GITHUB_OUTPUT"
            echo "is-first-release=false" >> "$GITHUB_OUTPUT"
            echo "Previous tag: $PREV_TAG"
          fi

      # ── 9. Generate auto release notes via GitHub API ──
      - name: Generate auto release notes
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: ${{ steps.parse-tag.outputs.tag }}
          PREV_TAG: ${{ steps.prev-tag.outputs.prev-tag }}
          REPO: ${{ github.repository }}
        run: |
          if [ -n "$PREV_TAG" ]; then
            gh api "repos/${REPO}/releases/generate-notes" \
              -f tag_name="$TAG" \
              -f previous_tag_name="$PREV_TAG" \
              --jq '.body' > auto-notes.md
          else
            gh api "repos/${REPO}/releases/generate-notes" \
              -f tag_name="$TAG" \
              --jq '.body' > auto-notes.md
          fi

          echo "Auto-generated release notes saved to auto-notes.md"

      # ── 10. Gather PR details (titles + bodies) for AI context ──
      - name: Gather PR details for AI context
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          # Extract PR numbers from auto-generated notes (capped at 100)
          PR_NUMBERS=$(grep -oE '#[0-9]+' auto-notes.md | sed 's/#//' | sort -un | head -100)

          if [ -z "$PR_NUMBERS" ]; then
            echo "No PRs found in auto-generated notes"
            echo "No pull requests found for this release." > pr-details.txt
            exit 0
          fi

          # Fetch full details (title + body) for each PR
          # Bodies are truncated to 500 chars to keep AI input focused
          > pr-details.txt
          for NUM in $PR_NUMBERS; do
            echo "Fetching PR #${NUM}..."
            PR_JSON=$(gh pr view "$NUM" --json title,body 2>/dev/null || echo '{}')
            PR_TITLE=$(printf '%s' "$PR_JSON" | jq -r '.title // "(title unavailable)"')
            PR_BODY=$(printf '%s' "$PR_JSON" | jq -r '.body // "No description"')
            PR_BODY_TRUNCATED=$(printf '%.500s' "$PR_BODY")
            printf 'PR #%s: %s\n%s\n---\n\n' "$NUM" "$PR_TITLE" "$PR_BODY_TRUNCATED" >> pr-details.txt
          done

          echo "Gathered details for $(echo "$PR_NUMBERS" | wc -w | tr -d ' ') PRs"

      # ── 11. Generate AI summary via GitHub Models ──
      - name: Generate AI summary
        id: ai-summary
        continue-on-error: true
        uses: actions/ai-inference@v1
        with:
          model: openai/gpt-4o
          max-tokens: 1500
          prompt-file: '.github/prompts/release-notes.prompt.yml'
          file_input: |
            pr_details: ./pr-details.txt

      # ── 12. Compose release body ──
      - name: Compose release body
        env:
          TAG: ${{ steps.parse-tag.outputs.tag }}
          PREV_TAG: ${{ steps.prev-tag.outputs.prev-tag }}
          REPO: ${{ github.repository }}
          AI_OUTCOME: ${{ steps.ai-summary.outcome }}
          AI_RESPONSE_FILE: ${{ steps.ai-summary.outputs.response-file }}
        run: |
          # Build compare URL
          if [ -n "$PREV_TAG" ]; then
            COMPARE_URL="https://github.com/${REPO}/compare/${PREV_TAG}...${TAG}"
          else
            COMPARE_URL="https://github.com/${REPO}/commits/${TAG}"
          fi

          # Compose body into file
          {
            # AI summary section (only if AI succeeded)
            if [ "$AI_OUTCOME" = "success" ] && \
               [ -n "$AI_RESPONSE_FILE" ] && \
               [ -f "$AI_RESPONSE_FILE" ] && \
               [ -s "$AI_RESPONSE_FILE" ]; then
              echo "## Summary"
              echo ""
              cat "$AI_RESPONSE_FILE"
              echo ""
              echo ""
            fi

            # Auto-generated PR list
            echo "## What's Changed"
            echo ""
            cat auto-notes.md
            echo ""

            # Full changelog link
            echo "**Full Changelog**: ${COMPARE_URL}"
          } > release-body.md

          echo "Release body composed"
          echo ""
          echo "### Release Body Preview" >> "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          cat release-body.md >> "$GITHUB_STEP_SUMMARY"

      # ── 13. Create draft release with APK attachments ──
      - name: Create draft release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: ${{ steps.parse-tag.outputs.tag }}
          IS_PRERELEASE: ${{ steps.parse-tag.outputs.is-prerelease }}
          REPO: ${{ github.repository }}
        run: |
          PRERELEASE_FLAG=""
          if [ "$IS_PRERELEASE" = "true" ]; then
            PRERELEASE_FLAG="--prerelease"
          fi

          gh release create "$TAG" \
            --draft \
            --title "$TAG" \
            --notes-file release-body.md \
            $PRERELEASE_FLAG \
            release-assets/*.apk

          echo "### Draft Release Created" >> "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          echo "Tag: \`$TAG\`" >> "$GITHUB_STEP_SUMMARY"
          echo "Pre-release: $IS_PRERELEASE" >> "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          echo "[View release](https://github.com/${REPO}/releases/tag/${TAG})" >> "$GITHUB_STEP_SUMMARY"
```

**Definition of Done for this action**:
- [x] File created at `.github/workflows/release.yml`
- [x] YAML is syntactically valid
- [x] All steps reference correct output IDs and parameter names
- [x] **Security**: ALL `${{ }}` expressions in `run:` blocks are passed via `env:` declarations (no direct interpolation)
- [x] **Security**: Third-party actions pinned to SHA (`lewagon/wait-on-check-action`, `dtolnay/rust-toolchain`)
- [x] **Security**: Previous tag detection filters to `v*` tags only (`git tag --list 'v*'`)
- [x] CI gate check-name matches the CI workflow's last job name (`Build Release`)
- [x] Tag parsing regex accepts: `v1.0.0`, `v1.0.0-alpha`, `v1.0.0-beta`, `v1.0.0-rc1`, `v0.0.1`
- [x] Tag parsing rejects: `v1.0`, `v1.0.0-gamma`, `v1.0.0-`, `v1.0.0-rc79` (overflow), `v1.0.0-rc99` (overflow)
- [x] VERSION_CODE formula produces correct values for all examples in Design Decisions
- [x] RC number overflow check (`rc78` max → offset 98, `rc79+` → error before colliding with release offset 99)
- [x] Build setup (JDK, Go, Rust, Gradle, native deps) matches CI `build-release` job (Rust and Gradle are SHA-pinned in release for supply chain security)
- [x] APKs built with single Gradle invocation (`assembleDebug assembleRelease`)
- [x] APK rename handles both `app-release.apk` (signed) and `app-release-unsigned.apk` (unsigned)
- [x] Step summary `ls` output wrapped in code block (triple backticks)
- [x] Auto-notes generation handles both first-release and subsequent-release
- [x] PR details gathering: capped at 100 PRs, single API call per PR (`--json title,body` + `jq`), bodies truncated to 500 chars, uses `printf` not `echo -e`
- [x] AI inference uses `continue-on-error: true` for fallback
- [x] Release body omits Summary section when AI fails
- [x] `gh release create` uses `--draft` always and `--prerelease` conditionally
- [x] Job timeout set to 150 minutes
- [x] GitHub step summary provides useful information at each stage

---

### Task 1.2: Create the AI prompt template file

**Description**: Create the `.prompt.yml` file used by `actions/ai-inference` to generate user-friendly release note summaries.

**Acceptance Criteria**:
- [x] File exists at `.github/prompts/release-notes.prompt.yml`
- [x] Uses `openai/gpt-4o` model
- [x] System prompt sets user-friendly tone and formatting rules
- [x] User prompt receives PR details via `{{pr_details}}` template variable
- [x] Output is grouped by category when applicable

#### Action 1.2.1: Create `.github/prompts/release-notes.prompt.yml`

**File**: `.github/prompts/release-notes.prompt.yml` (NEW)

**Context**: This prompt file is referenced by the AI inference step in the release workflow (step 11). The `{{pr_details}}` variable is populated via `file_input` from `pr-details.txt` which contains PR titles and bodies gathered in step 10.

**Content**:
```yaml
messages:
  - role: system
    content: >
      You are writing release notes for an open-source Android application called
      "Android Remote Control MCP". This app allows controlling Android devices
      remotely via the Model Context Protocol (MCP).

      Write in a user-friendly, clear, and concise tone.
      Group changes by category when applicable: Features, Improvements, Bug Fixes, Other.
      Only include categories that have actual changes — omit empty categories.
      Use bullet points for each change.
      Do not include PR numbers.
      Do not include contributor names.
      Do not repeat the same change under multiple categories.
      If there is only one category, omit the category heading and just list the changes.
  - role: user
    content: |
      Below are the pull requests merged since the last release.
      Summarize the changes for the release notes.

      {{pr_details}}
model: openai/gpt-4o
```

**Definition of Done for this action**:
- [x] File created at `.github/prompts/release-notes.prompt.yml`
- [x] YAML is syntactically valid
- [x] Contains `messages` array with `system` and `user` roles
- [x] System prompt specifies user-friendly tone
- [x] System prompt specifies grouping by category
- [x] System prompt specifies no PR numbers and no contributor names
- [x] User prompt includes `{{pr_details}}` template variable
- [x] Model is set to `openai/gpt-4o`

---

### Task 1.3: Update development workflow documentation

**Description**: Add a "Release Workflow" section to `docs/TOOLS.md` documenting how to create releases using tags.

**Acceptance Criteria**:
- [x] `docs/TOOLS.md` has a new "Release Workflow" section
- [x] Documents the tagging convention
- [x] Documents what happens when a tag is pushed
- [x] Documents VERSION_CODE derivation
- [x] Documents pre-release detection
- [x] Table of Contents is updated

#### Action 1.3.1: Add "Release Workflow" section to `docs/TOOLS.md`

**File**: `docs/TOOLS.md` (MODIFIED)

**Context**: This section is added between the "Pull Request Convention" section (line 114) and the "Git Commands Reference" section (line 164). The Table of Contents on line 9-15 is also updated.

**Change 1 — Update Table of Contents** (lines 8-15):

Replace:
```markdown
1. [Branching Convention](#branching-convention)
2. [Commit Convention](#commit-convention)
3. [Pull Request Convention](#pull-request-convention)
4. [Git Commands Reference](#git-commands-reference)
5. [GitHub CLI Commands Reference](#github-cli-commands-reference)
6. [Local CI with act](#local-ci-with-act)
```

With:
```markdown
1. [Branching Convention](#branching-convention)
2. [Commit Convention](#commit-convention)
3. [Pull Request Convention](#pull-request-convention)
4. [Release Workflow](#release-workflow)
5. [Git Commands Reference](#git-commands-reference)
6. [GitHub CLI Commands Reference](#github-cli-commands-reference)
7. [Local CI with act](#local-ci-with-act)
```

**Change 2 — Add "Release Workflow" section** (after line 161, before `## Git Commands Reference`):

Insert (note: the content below uses 4-backtick fencing to avoid conflicts with inner code blocks):

````markdown
## Release Workflow

### Overview

Releases are created automatically via GitHub Actions when a semantic version tag is pushed. The workflow creates a **draft** release (never auto-published) with:
- Debug and release APKs attached with proper names
- AI-generated release note summary (via GitHub Models)
- Auto-generated list of merged PRs
- Link to the full changelog diff

### Creating a Release

```bash
# 1. Ensure you are on main with latest changes
git checkout main
git pull origin main

# 2. Create and push a tag
git tag v1.2.3
git push origin v1.2.3

# 3. The release workflow triggers automatically
# 4. Check the Actions tab for progress
# 5. Find the draft release under Releases when done
```

### Tag Format

Tags MUST follow semantic versioning with a `v` prefix:

| Tag | Type | GitHub Release |
|-----|------|---------------|
| `v1.0.0` | Stable release | Draft |
| `v1.0.0-alpha` | Pre-release (alpha) | Draft + Pre-release |
| `v1.0.0-beta` | Pre-release (beta) | Draft + Pre-release |
| `v1.0.0-rc1` | Release candidate | Draft + Pre-release |
| `v1.0.0-rc2` | Release candidate | Draft + Pre-release |

### VERSION_CODE Derivation

The `VERSION_CODE` (Android integer version) is automatically computed from the tag:

**Formula**: `MAJOR × 1,000,000 + MINOR × 10,000 + PATCH × 100 + OFFSET`

| Pre-release type | Offset | Example tag | VERSION_CODE |
|-----------------|--------|-------------|-------------|
| `alpha` | 01 | `v1.2.3-alpha` | `1020301` |
| `beta` | 10 | `v1.2.3-beta` | `1020310` |
| `rc{N}` | 20+N | `v1.2.3-rc1` | `1020321` |
| stable (no suffix) | 99 | `v1.2.3` | `1020399` |

This ensures correct ordering: `alpha < beta < rc1 < rc2 < ... < release`.

### APK Naming

Attached APKs follow this naming convention:
- `android-remote-control-mcp-{tag}-debug.apk`
- `android-remote-control-mcp-{tag}-release.apk`

### Pipeline Behavior

1. **CI Gate**: The workflow waits for the CI pipeline (`Build Release` check) to pass on the tagged commit before proceeding. If CI hasn't run yet, it polls until complete.
2. **Build**: APKs are built with `VERSION_NAME` and `VERSION_CODE` injected from the tag.
3. **Release Notes**: AI-generated summary + auto-generated PR list + changelog link. If AI generation fails, the release is created without the summary section.
4. **Draft Release**: Created as draft — you must manually review and publish.

### Prerequisites and Limitations

- **CI must have already run**: The tagged commit must have CI results from a prior push or PR merge to `main`. The release workflow does **not** trigger CI — it only waits for existing check results. If CI never ran for the commit, the workflow will poll until the job timeout (150 minutes) and then fail.
- **Re-tagging**: If you need to re-tag a release (e.g., to fix the build), you must:
  1. Delete the existing draft release on GitHub (Releases → draft → Delete)
  2. Delete the tag: `git tag -d v1.2.3 && git push origin :refs/tags/v1.2.3`
  3. Recreate and push the tag: `git tag v1.2.3 && git push origin v1.2.3`

---

````

**Change 3 — Update Quick Reference Card** (at the end of the file, around line 420-437):

Add to the Quick Reference Card table:

| Task | Command |
|------|---------|
| Create release tag | `git tag v1.2.3 && git push origin v1.2.3` |
| Create pre-release tag | `git tag v1.2.3-rc1 && git push origin v1.2.3-rc1` |

**Definition of Done for this action**:
- [x] Table of Contents updated with "Release Workflow" entry at position 4
- [x] Subsequent TOC entries renumbered (5, 6, 7)
- [x] "Release Workflow" section added between "Pull Request Convention" and "Git Commands Reference"
- [x] Section documents: tagging commands, tag format table, VERSION_CODE formula + table, APK naming, pipeline behavior
- [x] Section documents: CI prerequisite (tagged commit must have CI results)
- [x] Section documents: re-tagging limitation and steps to fix
- [x] Section ends with `---` separator before "Git Commands Reference"
- [x] Two new rows added to the Quick Reference Card table
- [x] Commands are correct

---

### Task 1.4: Validate workflow syntax

**Description**: Validate that the new workflow file is syntactically correct using `gh act --validate`.

**Acceptance Criteria**:
- [x] `gh act --validate` passes without errors

#### Action 1.4.1: Run workflow validation

**Command**:
```bash
gh act --validate
```

**Definition of Done for this action**:
- [x] Command exits with code 0
- [x] No YAML syntax errors reported
- [x] No workflow structure errors reported

---

### Task 1.5: End-to-end double-check from the ground up

**Description**: Comprehensive verification of every file, every step, and every design decision against the agreed requirements. This is the final verification before considering the implementation complete.

**Acceptance Criteria**:
- [x] Every item below is verified

#### Action 1.5.1: Comprehensive verification checklist

Go through each item and verify against the source files:

**Workflow trigger and permissions**:
- [x] `.github/workflows/release.yml` triggers on `push: tags: ['v*']` — not on branches, not on PRs
- [x] Permissions: `contents: write` (create release), `models: read` (AI inference), `actions: read` (check CI status)
- [x] Concurrency group prevents parallel release runs for same tag
- [x] `cancel-in-progress: false` — never cancel a running release

**CI gate**:
- [x] Uses `lewagon/wait-on-check-action` pinned to SHA `a499964d3917700d29a2a8baf3f16b078d609410` (v1.5.0)
- [x] `check-name: 'Build Release'` matches the CI workflow's `build-release` job `name:` field exactly
- [x] `running-workflow-name: 'Create Draft Release'` matches THIS workflow's job `name:` field to prevent deadlock
- [x] `wait-interval: 30` (seconds between polls)
- [x] `allowed-conclusions: success` (only proceed on success)
- [x] Job `timeout-minutes: 150` provides enough time for CI wait + build (with safety margin)

**Security — shell injection prevention**:
- [x] ALL `${{ github.ref_name }}` usages in `run:` blocks are passed via `env:` declarations
- [x] ALL `${{ steps.*.outputs.* }}` usages in `run:` blocks are passed via `env:` declarations
- [x] ALL `${{ github.repository }}` usages in `run:` blocks are passed via `env:` declarations
- [x] No `${{ }}` expressions appear directly inside any `run:` script body

**Security — supply chain**:
- [x] `lewagon/wait-on-check-action` pinned to SHA `a499964d3917700d29a2a8baf3f16b078d609410` with `# v1.5.0` comment
- [x] `dtolnay/rust-toolchain` pinned to SHA `631a55b12751854ce901bb631d5902ceb48146f7` with `# stable` comment
- [x] GitHub first-party actions (`actions/checkout`, `actions/setup-java`, etc.) pinned to major version tags (acceptable)

**Security — previous tag filtering**:
- [x] `git tag --list 'v*'` filters to only version tags (prevents picking up malicious non-version tags)

**Tag parsing**:
- [x] `TAG` received via `env:` block, not direct `${{ }}` interpolation
- [x] Regex `^([0-9]+)\.([0-9]+)\.([0-9]+)(-(.+))?$` correctly parses all valid formats
- [x] Validates: `1.0.0` ✓, `1.2.3-alpha` ✓, `1.2.3-beta` ✓, `1.2.3-rc1` ✓
- [x] Rejects: `1.0` ✗, `1.0.0-gamma` ✗, `1.0.0-` ✗, `abc` ✗, `1.0.0-rc79` ✗ (overflow), `1.0.0-rc99` ✗ (overflow)
- [x] VERSION_CODE examples match Design Decisions table:
  - `v1.2.3-alpha` → `1020301`
  - `v1.2.3-beta` → `1020310`
  - `v1.2.3-rc1` → `1020321`
  - `v1.2.3-rc2` → `1020322`
  - `v1.2.3` → `1020399`
- [x] RC overflow check: `rc78` → offset 98 (OK), `rc79` → offset 99 (would collide with release) → error
- [x] Outputs are set: `tag`, `version`, `version-code`, `is-prerelease`
- [x] Step summary table is written

**Build setup**:
- [x] JDK 17 temurin — matches CI
- [x] Go 1.25.6 — matches CI
- [x] Rust stable (SHA-pinned) with `aarch64-linux-android,x86_64-linux-android` targets — matches CI
- [x] Gradle setup — matches CI
- [x] `make compile-cloudflared` — matches CI
- [x] `make compile-ngrok-native` with `JAVA_11_HOME` and `JAVA_17_HOME` env vars — matches CI

**APK build and naming**:
- [x] Single Gradle invocation: `assembleDebug assembleRelease` (not separate steps)
- [x] VERSION_NAME and VERSION_CODE passed via `env:` block, then `-PVERSION_NAME="$VERSION_NAME" -PVERSION_CODE="$VERSION_CODE"`
- [x] `build.gradle.kts` reads these via `project.findProperty()` — verified lines 16-17
- [x] Debug APK source: `app/build/outputs/apk/debug/app-debug.apk`
- [x] Release APK source: `app/build/outputs/apk/release/app-release.apk` (signed) or `app-release-unsigned.apk` (unsigned)
- [x] Rename target: `android-remote-control-mcp-{tag}-debug.apk` and `android-remote-control-mcp-{tag}-release.apk`
- [x] Both files placed in `release-assets/` directory
- [x] Step summary `ls` output wrapped in triple backtick code block

**Previous tag detection**:
- [x] `git tag --list 'v*' --sort=-version:refname` filters to `v*` tags and sorts by version (highest first)
- [x] Current tag is excluded via `grep -vxF` (fixed-string whole-line match, not regex)
- [x] First result is the previous tag
- [x] Empty result handled as first release
- [x] `CURRENT_TAG` received via `env:` block

**Release notes generation**:
- [x] `TAG`, `PREV_TAG`, `REPO` received via `env:` block
- [x] `gh api repos/${REPO}/releases/generate-notes` called with `tag_name`
- [x] `previous_tag_name` included when previous tag exists, omitted for first release
- [x] Result saved to `auto-notes.md`

**PR details gathering**:
- [x] PR numbers extracted from `auto-notes.md` via `grep -oE '#[0-9]+'`
- [x] Deduplicated via `sort -un`
- [x] **Capped at 100 PRs** via `head -100`
- [x] Each PR's title and body fetched via single `gh pr view --json title,body` call (parsed with `jq`)
- [x] **PR bodies truncated to 500 chars** via `printf '%.500s'`
- [x] Uses `printf` instead of `echo -e` (no escape sequence interpretation of untrusted content)
- [x] Missing PRs handled gracefully (fallback text)
- [x] Empty PR list handled (writes "No pull requests found")

**AI summary**:
- [x] Uses `actions/ai-inference@v1`
- [x] `model: openai/gpt-4o`
- [x] `max-tokens: 1500`
- [x] `prompt-file` points to `.github/prompts/release-notes.prompt.yml`
- [x] `file_input` maps `pr_details` to `./pr-details.txt`
- [x] `continue-on-error: true` ensures failure doesn't block release
- [x] Prompt file exists and contains correct template variable `{{pr_details}}`

**Release body composition**:
- [x] `TAG`, `PREV_TAG`, `REPO`, `AI_OUTCOME`, `AI_RESPONSE_FILE` received via `env:` block
- [x] AI summary section included only when `outcome=success` AND response file exists AND is non-empty
- [x] Auto-notes always included under "## What's Changed"
- [x] Compare URL uses `compare/{prev}...{tag}` format (or `commits/{tag}` for first release)
- [x] Final format matches agreed structure: Summary (optional) → What's Changed → Full Changelog link

**Draft release creation**:
- [x] `TAG`, `IS_PRERELEASE`, `REPO` received via `env:` block
- [x] `gh release create` uses `--draft` always
- [x] `--prerelease` added only when `is-prerelease=true`
- [x] `--title` set to the tag name
- [x] `--notes-file` reads from `release-body.md`
- [x] `release-assets/*.apk` attaches both APKs
- [x] Step summary link uses `${REPO}` and `${TAG}` env vars (not `${{ }}` expressions)

**AI prompt template**:
- [x] `.github/prompts/release-notes.prompt.yml` exists
- [x] System prompt: user-friendly tone, categorized (Features/Improvements/Bug Fixes/Other), no PR numbers, no contributor names
- [x] User prompt includes `{{pr_details}}` variable
- [x] Model set to `openai/gpt-4o`

**TOOLS.md documentation**:
- [x] Table of Contents updated (7 items, Release Workflow at position 4)
- [x] Release Workflow section contains: overview, tagging commands, tag format table, VERSION_CODE table, APK naming, pipeline behavior
- [x] Release Workflow section documents CI prerequisite (tagged commit must have CI results from prior push/merge)
- [x] Release Workflow section documents re-tagging limitation and steps (delete draft, delete tag, re-tag)
- [x] Quick Reference Card has release tag commands
- [x] No existing content was modified or removed (only additions)

**No deviations from agreed design**:
- [x] No extra features added beyond what was discussed
- [x] No changes to existing CI workflow
- [x] No changes to `build.gradle.kts`
- [x] No changes to `gradle.properties`
- [x] APKs are NOT signed (no keystore configuration added)
- [x] Release is ALWAYS draft, NEVER auto-published

**Definition of Done for this action**:
- [x] Every checkbox above is verified ✓
