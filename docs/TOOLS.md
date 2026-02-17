# Development Workflow Tools

This document defines the git, GitHub CLI (`gh`), and local CI (`act`) commands and conventions used in this project. All contributors and LLM agents MUST follow these conventions.

---

## Table of Contents

1. [Branching Convention](#branching-convention)
2. [Commit Convention](#commit-convention)
3. [Pull Request Convention](#pull-request-convention)
4. [Release Workflow](#release-workflow)
5. [Git Commands Reference](#git-commands-reference)
6. [GitHub CLI Commands Reference](#github-cli-commands-reference)
7. [Local CI with act](#local-ci-with-act)

---

## Branching Convention

### Branch Naming

All feature branches MUST follow this format:

```
feat/<description-with-dashes>
```

**Examples**:
- `feat/project-scaffolding`
- `feat/data-layer-utilities`
- `feat/ui-layer-compose`
- `feat/accessibility-service`
- `feat/screen-capture-service`
- `feat/mcp-server-ktor`
- `feat/mcp-introspection-system-tools`
- `feat/mcp-touch-gesture-tools`
- `feat/mcp-element-text-utility-tools`
- `feat/e2e-tests-ci-docs`

### Branch Lifecycle

1. Always branch from `main` (latest)
2. Implement the plan on the feature branch
3. Push to remote with `-u` flag
4. Create PR via `gh pr create`
5. User reviews and merges the PR
6. Agent pulls updated `main` before starting the next plan

---

## Commit Convention

### Commit Granularity

- Create **multiple logical commits** per PR, NOT one giant squash commit.
- Each commit MUST be a **coherent, self-contained unit of work**.
- Use `git add -p` when needed to stage specific hunks from a file that contains changes belonging to different logical commits.
- Each commit tells a story: what was done and why.

### Commit Message Format

```
<type>: <short description>

<optional body explaining the "why", not the "what">
```

**Note**: Commits and PRs MUST NOT contain any references to Claude Code, Claude, Anthropic, or any AI tooling. This includes `Co-Authored-By` trailers, `Generated with Claude Code` footers, or any similar attribution. You are the sole author.

**Types**:
- `feat`: New feature or functionality
- `fix`: Bug fix
- `refactor`: Code restructuring without behavior change
- `test`: Adding or updating tests
- `docs`: Documentation changes
- `chore`: Build, CI, tooling, dependencies
- `style`: Code style/formatting (no logic change)

**Examples**:
```
feat: add Gradle project configuration with all dependencies

Set up root and app build.gradle.kts with Kotlin DSL, configure
libs.versions.toml version catalog with all project dependencies,
and define gradle.properties with version and build settings.
```

```
chore: add Makefile with development workflow targets

Implement all Makefile targets defined in PROJECT.md including
build, test, lint, device management, emulator, and versioning
targets.
```

### Staging Specific Chunks

When a single file contains changes that belong to different logical commits, use interactive staging:

```bash
# Stage specific hunks interactively
git add -p <file>

# Review what is staged vs unstaged
git diff --cached   # staged changes
git diff            # unstaged changes
```

**Rules**:
- Never use `git add -A` or `git add .` blindly — always review what is being staged.
- Prefer `git add <specific-file>` for files that belong entirely to one commit.
- Use `git add -p <file>` only when a file has changes spanning multiple logical commits.

---

## Pull Request Convention

### PR Creation

Create PRs using the GitHub CLI with a HEREDOC for the body:

```bash
gh pr create --base main --title "<Plan N>: <short title>" --body "$(cat <<'EOF'
## Summary

Brief summary of the plan: what it implements, why, and what the expected outcome is.

## Plan Reference

Implementation of Plan N: <plan name> from `docs/plans/<plan-file>.md`.

## Changes

- <bullet points of key changes>

## Testing

- <what tests were added/run>
- <verification steps performed>

## Checklist

- [ ] All tests pass (`make test-unit`)
- [ ] Lint passes (`make lint`)
- [ ] Build succeeds (`make build`)
- [ ] CI validated locally (`gh act --validate`)
EOF
)"
```

### PR Title Format

```
Plan <N>: <Short descriptive title>
```

**Examples**:
- `Plan 1: Project scaffolding and build system`
- `Plan 2: Data layer, settings repository, and utilities`
- `Plan 7: Screen introspection and system action MCP tools`

---

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

1. **CI Gate**: The workflow waits for the CI pipeline (`Build Release` check) to pass on the tagged commit before proceeding. If CI hasn't completed yet, it polls until it finishes or the job timeout (150 minutes) is reached.
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

## Git Commands Reference

### Daily Workflow

```bash
# Ensure main is up to date before starting a new plan
git checkout main
git pull origin main

# Create feature branch
git checkout -b feat/<description>

# Stage specific files
git add <file1> <file2>

# Stage specific hunks from a file
git add -p <file>

# Review staged changes
git diff --cached

# Review unstaged changes
git diff

# Commit with HEREDOC for multi-line message
git commit -m "$(cat <<'EOF'
<type>: <description>

<body>
EOF
)"

# Push branch to remote (first push)
git push -u origin feat/<description>

# Push subsequent commits
git push
```

### Inspection Commands

```bash
# Show working tree status
git status

# Show recent commit history
git log --oneline -20

# Show diff between branch and main
git diff main...HEAD

# Show all changes (staged + unstaged)
git diff HEAD

# Show specific file history
git log --oneline -- <file>
```

### Safety Rules

- **NEVER** use `git push --force` without explicit user permission.
- **NEVER** use `git reset --hard` without explicit user permission.
- **NEVER** amend published commits without explicit user permission.
- **NEVER** skip hooks (`--no-verify`) without explicit user permission.
- **ALWAYS** create NEW commits rather than amending after hook failures.

---

## GitHub CLI Commands Reference

### PR Management

```bash
# Create PR
gh pr create --base main --title "<title>" --body "<body>"

# List open PRs
gh pr list

# View PR details
gh pr view <number>

# View PR checks/status
gh pr checks <number>

# View PR diff
gh pr diff <number>
```

### Repository Information

```bash
# View repo info
gh repo view

# View remote URL
git remote -v
```

### PR Reviewers

```bash
# Add Copilot as reviewer
gh pr edit <number> --add-reviewer copilot
```

### PR Review Comments

```bash
# List review comments on a PR
gh api repos/<owner>/<repo>/pulls/<number>/comments

# Reply to a review comment (use in_reply_to with the comment's databaseId)
gh api repos/<owner>/<repo>/pulls/<number>/comments \
  -f body="Your reply text here." \
  -F in_reply_to=<comment-id>
```

### Resolving Review Conversations

Resolving review threads requires GraphQL. First, get the thread IDs:

```bash
# Get review thread IDs and their resolved status
gh api graphql -f query='
query {
  repository(owner: "<owner>", name: "<repo>") {
    pullRequest(number: <number>) {
      reviewThreads(first: 50) {
        nodes {
          id
          isResolved
          comments(first: 1) {
            nodes {
              body
              databaseId
            }
          }
        }
      }
    }
  }
}'
```

Then resolve each thread by its `id`:

```bash
# Resolve a review thread
gh api graphql -f query='
mutation {
  resolveReviewThread(input: {threadId: "<thread-id>"}) {
    thread { isResolved }
  }
}'
```

### Issue Management (if needed)

```bash
# List issues
gh issue list

# Create issue
gh issue create --title "<title>" --body "<body>"

# View issue
gh issue view <number>
```

---

## Local CI with act

### Overview

`act` (installed as `gh act` via the nektos/act GitHub CLI extension) runs GitHub Actions workflows locally using Docker containers. This allows testing CI pipelines before pushing to GitHub.

### Validation Commands

```bash
# Validate workflow YAML syntax (no Docker needed)
gh act --validate

# Dry run — validates correctness without creating containers
gh act -n

# Dry run for a specific event
gh act -n push
gh act -n pull_request
```

### Running Workflows Locally

```bash
# Run all jobs triggered by push event (default)
gh act push

# Run all jobs triggered by pull_request event
gh act pull_request

# Run a specific job by ID
gh act -j lint
gh act -j test-unit
gh act -j build

# List all available workflows and jobs
gh act -l
```

### Platform Image Configuration

GitHub Actions runners may need custom Docker images for Android development:

```bash
# Use a custom image for a platform
gh act -P ubuntu-latest=<custom-image>

# Example: use a specific Ubuntu image
gh act -P ubuntu-latest=catthehacker/ubuntu:act-latest
```

### Environment and Secrets

```bash
# Pass environment variables
gh act --env ANDROID_HOME=/opt/android-sdk --env JAVA_HOME=/usr/lib/jvm/java-17

# Pass secrets
gh act -s GITHUB_TOKEN=<token>

# Use env file
gh act --env-file .env.ci

# Use secrets file
gh act --secret-file .secrets
```

### Known Limitations

- **Android SDK**: CI containers may not have Android SDK pre-installed. Jobs requiring Android SDK (build, test) may need custom platform images (`-P`) or may only work fully on GitHub-hosted runners.
- **Android Emulator**: Integration tests requiring an emulator are unlikely to work locally via `act` due to KVM/hardware acceleration requirements inside Docker. These jobs should be validated on GitHub.
- **Docker-in-Docker**: E2E tests using Testcontainers (Docker Android) require Docker-in-Docker support, which may not work in all `act` configurations.
- **Recommended local usage**: Use `gh act` primarily for validating workflow syntax, running lint jobs, and testing non-Android-specific jobs. Rely on GitHub-hosted runners for full Android CI.

### Workflow During Plan Implementation

1. **After creating/modifying CI workflow**: Run `gh act --validate` to check syntax.
2. **After implementing lint/test jobs**: Run `gh act -j <job-id>` to verify locally.
3. **Before creating PR**: Run `gh act -n` to dry-run the full pipeline.
4. **Plan 10 (CI finalization)**: Run `gh act push` for full local pipeline validation.

---

## Quick Reference Card

| Task | Command |
|------|---------|
| Create feature branch | `git checkout -b feat/<desc>` |
| Stage files | `git add <file>` |
| Stage hunks | `git add -p <file>` |
| Review staged | `git diff --cached` |
| Commit | `git commit -m "$(cat <<'EOF' ... EOF)"` |
| Push (first time) | `git push -u origin feat/<desc>` |
| Push (subsequent) | `git push` |
| Create PR | `gh pr create --base main --title "..." --body "..."` |
| Add Copilot reviewer | `gh pr edit <n> --add-reviewer copilot` |
| Reply to PR comment | `gh api repos/.../pulls/<n>/comments -f body="..." -F in_reply_to=<id>` |
| Resolve PR thread | `gh api graphql -f query='mutation { resolveReviewThread(...) }'` |
| Create release tag | `git tag v1.2.3 && git push origin v1.2.3` |
| Create pre-release tag | `git tag v1.2.3-rc1 && git push origin v1.2.3-rc1` |
| Validate CI | `gh act --validate` |
| Dry-run CI | `gh act -n` |
| Run CI job | `gh act -j <job-id>` |
| Run full CI | `gh act push` |
| List CI jobs | `gh act -l` |
