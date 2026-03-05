<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 39: Verified Typing with Adaptive Pacing

## Problem

The text typing tools (`type_append_text`, `type_insert_text`, `type_replace_text`) lose characters intermittently on some apps. Root cause: `AccessibilityInputConnection.commitText()` is fire-and-forget one-way IPC — returns `void` in the Android framework. The boolean return in `TypeInputControllerImpl` only indicates IC availability, not acceptance. Apps with heavy text processing (watchers, autocomplete, input filters) can silently drop characters.

## Solution

1. **Per-character verification**: After each `commitText()`, read back via `getSurroundingText()` (synchronous two-way IPC) to confirm the character landed. On miss, wait 50ms and retry (up to 3 retries).
2. **Adaptive pacing (AIMD-style)**: Each miss increases effective delay by +50ms. Each success decreases by -25ms. Floor = caller's `typing_speed` parameter value.
3. **Default speed change**: Increase `DEFAULT_TYPING_SPEED_MS` from 70→250 and `DEFAULT_TYPING_VARIANCE_MS` from 15→50.

---

## User Story 1: Add per-character verification and adaptive pacing to `typeCharByChar`

**Why**: The core typing loop currently has no feedback mechanism — characters are dispatched via one-way IPC with no confirmation. This user story adds a verify-retry loop and AIMD-style delay adjustment to make typing reliable across all target apps.

**Acceptance Criteria**:
- [x] After each `commitText()`, `getSurroundingText(charCount, 0, 0)` is called to verify
- [x] Verification checks that text before cursor matches the committed code point string
- [x] On miss: 50ms wait, then retry same character (up to 3 retries)
- [x] On exhausted retries: throw `ActionFailed` with position info
- [x] On `getSurroundingText()` returning null during verification: throw `ActionFailed` immediately (IC lost, no retry)
- [x] Each miss: `effectiveDelay += 50`
- [x] Each success: `effectiveDelay = max(minDelay, effectiveDelay - 25)` where `minDelay` = caller's `typingSpeed`
- [x] Inter-character delay uses `effectiveDelay ± variance`
- [x] `DEFAULT_TYPING_SPEED_MS` = 250, `DEFAULT_TYPING_VARIANCE_MS` = 50
- [x] Pre-retry length check: before re-committing, verify field length didn't grow (avoids double-commit)
- [x] `effectiveDelay` capped at `ADAPTIVE_DELAY_MAX_MS` (2000ms)

### Task 1.1: Modify `typeCharByChar()` — add verification, retry, and adaptive pacing

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt` — modify

**Actions**:

1. Update constants:

```kotlin
private const val DEFAULT_TYPING_SPEED_MS = 250
private const val DEFAULT_TYPING_VARIANCE_MS = 50
private const val COMMIT_VERIFY_RETRY_DELAY_MS = 50L
private const val COMMIT_MAX_RETRIES = 3
private const val ADAPTIVE_DELAY_INCREASE_MS = 50
private const val ADAPTIVE_DELAY_DECREASE_MS = 25
private const val ADAPTIVE_DELAY_MAX_MS = 2000
```

2. Replace the `typeCharByChar()` function (lines 80-119) with the verified version. New signature adds no new parameters — `TypeInputController` already has `getSurroundingText()`.

New algorithm:

```kotlin
internal suspend fun typeCharByChar(
    text: String,
    typingSpeed: Int,
    typingSpeedVariance: Int,
    typeInputController: TypeInputController,
) {
    val effectiveVariance = typingSpeedVariance.coerceIn(0, typingSpeed)
    val codePointCount = text.codePointCount(0, text.length)
    var codePointIndex = 0
    var offset = 0
    val minDelay = typingSpeed
    var effectiveDelay = typingSpeed

    // Read initial field length once for pre-retry length validation
    val initialFieldLength =
        typeInputController.getSurroundingText(
            MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0,
        )?.let { it.offset + it.text.length } ?: 0
    var committedCodeUnits = 0

    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val charCount = Character.charCount(codePoint)
        val codePointStr = text.substring(offset, offset + charCount)

        var committed = false
        for (attempt in 0..COMMIT_MAX_RETRIES) {
            // Only commit on first attempt; on retries, commit only if
            // pre-retry length check confirmed the char didn't land
            if (attempt == 0 || !committed) {
                val dispatchResult = typeInputController.commitText(codePointStr, 1)
                if (!dispatchResult) {
                    throw McpToolException.ActionFailed(
                        "Input connection lost during typing at position $codePointIndex of $codePointCount. " +
                            "commitText returned false.",
                    )
                }
            }

            // Verify: read back the character before cursor
            val surrounding = typeInputController.getSurroundingText(charCount, 0, 0)
            if (surrounding == null) {
                throw McpToolException.ActionFailed(
                    "Input connection lost during verification at position $codePointIndex of $codePointCount.",
                )
            }

            val textBeforeCursor = surrounding.text.toString()
            if (textBeforeCursor.endsWith(codePointStr)) {
                committed = true
                effectiveDelay = (effectiveDelay - ADAPTIVE_DELAY_DECREASE_MS).coerceAtLeast(minDelay)
                break
            }

            // Miss — increase pacing (capped) and wait before retry
            effectiveDelay = (effectiveDelay + ADAPTIVE_DELAY_INCREASE_MS).coerceAtMost(ADAPTIVE_DELAY_MAX_MS)
            if (attempt < COMMIT_MAX_RETRIES) {
                delay(COMMIT_VERIFY_RETRY_DELAY_MS)

                // Pre-retry length check: if field grew by charCount, the char
                // landed despite endsWith mismatch (autocomplete/formatter modified text)
                val currentLength =
                    typeInputController.getSurroundingText(
                        MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0,
                    )?.let { it.offset + it.text.length } ?: 0
                val expectedLength = initialFieldLength + committedCodeUnits + charCount
                if (currentLength >= expectedLength) {
                    committed = true
                    effectiveDelay = (effectiveDelay - ADAPTIVE_DELAY_DECREASE_MS).coerceAtLeast(minDelay)
                    break
                }
                // Length didn't increase — char genuinely dropped, retry commit next iteration
            }
        }

        if (!committed) {
            throw McpToolException.ActionFailed(
                "Character dropped after $COMMIT_MAX_RETRIES retries at position $codePointIndex of $codePointCount.",
            )
        }

        committedCodeUnits += charCount
        offset += charCount
        codePointIndex++

        // Skip delay after the last character
        if (offset < text.length) {
            val delayMs =
                if (effectiveVariance > 0) {
                    val variance = kotlin.random.Random.nextInt(-effectiveVariance, effectiveVariance + 1)
                    (effectiveDelay + variance).coerceAtLeast(1).toLong()
                } else {
                    effectiveDelay.toLong()
                }
            delay(delayMs)
        }
    }
}
```

**Key implementation notes**:
- Verification uses `endsWith(codePointStr)` because `getSurroundingText` `beforeLength` is a hint — the returned text may be longer than requested.
- **Pre-retry length check (A-7 fix)**: Before re-committing, reads total field length. If field grew by `charCount` since commit, the char landed despite `endsWith` mismatch (autocomplete/formatter modified text around cursor). Avoids double-commit.
- `effectiveDelay` capped at `ADAPTIVE_DELAY_MAX_MS` (2000ms) to prevent unbounded growth (P-1 fix).
- Variance uses the ORIGINAL `typingSpeedVariance` (clamped to `typingSpeed`), NOT `effectiveDelay`.
- **Known limitation (T-2)**: `endsWith` check assumes cursor stays immediately after the committed character. Apps with auto-formatting that moves the cursor post-commit may cause spurious verification failures (mitigated by pre-retry length check).

**Definition of Done**:
- [x] `typeCharByChar()` verifies each character after commit
- [x] Retries up to 3 times on miss with 50ms wait
- [x] Pre-retry length check prevents double-commit
- [x] Adaptive delay: +50 on miss (capped at 2000ms), -25 on success, floor at `typingSpeed`
- [x] IC loss (commitText false or getSurroundingText null) throws immediately
- [x] Constants updated: `DEFAULT_TYPING_SPEED_MS=250`, `DEFAULT_TYPING_VARIANCE_MS=50`
- [x] Mutex hold-time comment updated from "70ms" to "250ms"

### Task 1.2: Update KDoc for `typeCharByChar()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt` — modify

Replace the KDoc block above `typeCharByChar()` (lines 53-79) with updated documentation reflecting the new verification, retry, and adaptive pacing behavior.

**Definition of Done**:
- [x] KDoc accurately describes verification, retry, adaptive pacing, and error conditions

### Task 1.3: Update unit tests for `typeCharByChar()`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputToolsTest.kt` — modify

Existing `typeCharByChar` tests in the `SharedUtilitiesTests` inner class need updates to account for the new verification calls, and new tests need to be added for the retry/adaptive behavior.

**Shared verification mock helper** (add to `TextInputToolsTest` class level, Q-4 fix):

```kotlin
/**
 * Sets up the mockTypeInputController to handle verification getSurroundingText calls.
 * Uses answers {} block to dynamically return a SurroundingText whose text ends with
 * whatever was last committed via commitText. This avoids brittle returnsMany lists.
 *
 * Verification calls use afterLength=0; field-content reads use afterLength=10000.
 * This helper only covers afterLength=0 calls.
 */
private fun setupVerificationMock() {
    var lastCommittedText = ""
    every { mockTypeInputController.commitText(any(), any()) } answers {
        lastCommittedText = firstArg<String>()
        true
    }
    // Verification calls: afterLength=0
    every { mockTypeInputController.getSurroundingText(any(), eq(0), eq(0)) } answers {
        createMockSurroundingText(lastCommittedText)
    }
    // Initial field length read at top of typeCharByChar: afterLength=MAX (10000)
    // This must be set up separately per test or in setupDefaultMocks
}
```

This `answers {}` approach (Q-3 fix) dynamically tracks what was committed and returns it for verification — no `returnsMany` exhaustion issues.

**Updated existing tests** (mock verification calls for happy path):

| Test | Change |
|------|--------|
| `typeCharByChar iterates by code points not chars` | Use `setupVerificationMock()`; add initial field length mock; verify `getSurroundingText` called for verification |
| `typeCharByChar stops when commitText returns false` | No change needed — commitText false still throws before verification |
| `typeCharByChar respects coroutine cancellation` | Use `setupVerificationMock()`; add initial field length mock |
| `typeCharByChar handles empty string without calling commitText` | Add initial field length mock (loop body not reached but initialFieldLength is read) |
| `typeCharByChar skips delay after last character` | Use `setupVerificationMock()`; add initial field length mock |
| `typeCharByChar clamps large variance to typing_speed` | Use `setupVerificationMock()`; add initial field length mock |
| `extractTypingParams uses defaults when not provided` | Update expected defaults: speed=250, variance=50 |

**New tests**:

| Test | Verifies |
|------|----------|
| `typeCharByChar retries on verification miss and succeeds` | First verify: miss (text doesn't match). Pre-retry length check: length didn't grow. Second commit+verify: success. Verify commitText called 2 times for that code point. |
| `typeCharByChar throws after max retries exhausted` | All 4 attempts (1+3 retries) return mismatched text. Pre-retry length checks all show no growth. Verify `ActionFailed` thrown with "retries" message. |
| `typeCharByChar throws immediately when getSurroundingText returns null` | commitText returns true, verification getSurroundingText returns null. Verify `ActionFailed` thrown with "verification" message, no retry. |
| `typeCharByChar adaptive delay increases on miss` | Commit 3 chars. Second char misses once then succeeds. Verify total elapsed time is greater than 3 × typingSpeed (delay increased). **Setup**: Use fixed typingSpeed=100, variance=0 for deterministic timing. |
| `typeCharByChar adaptive delay decreases on success back to floor` | Set initial effectiveDelay higher via a miss, then verify delay decreases on subsequent successes but never below typingSpeed. **Setup**: Type 10+ chars, first char misses once, rest succeed. Track timing. |
| `typeCharByChar verification uses endsWith for robustness` | Verification getSurroundingText returns extra text before the committed char (e.g., "xyzD" when committing "D"). Verify success (endsWith matches). |
| `typeCharByChar pre-retry length check detects char landed despite endsWith mismatch` | Commit char, endsWith fails, but pre-retry length check shows field grew. Verify committed=true, no re-commit. |
| `typeCharByChar effectiveDelay capped at 2000ms` | Set up repeated misses. Verify delay never exceeds ADAPTIVE_DELAY_MAX_MS regardless of miss count. |
| `typeCharByChar cancellation during retry delay` | Coroutine is cancelled during the 50ms retry delay. Verify CancellationException propagates. (Q-2 fix) |

**Definition of Done**:
- [x] Shared `setupVerificationMock()` helper added
- [x] All existing `typeCharByChar` and tool-level tests updated with verification mocks
- [x] 9 new tests for retry, exhaustion, IC loss, adaptive increase/decrease, endsWith, pre-retry length check, delay cap, and retry cancellation
- [ ] All tests pass

### Task 1.4: Update tool-level unit tests for new defaults and verification mocks

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputToolsTest.kt` — modify

All `TypeAppendTextTool`, `TypeInsertTextTool`, `TypeReplaceTextTool` tests that call `setupDefaultMocks()` need updated mocking.

**Changes to `setupDefaultMocks()` in each tool's test class**:
- Use the shared `setupVerificationMock()` helper (or equivalent `answers {}` block) for per-character verification
- Add initial field length mock: `every { mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0)) }` returns current field text (for the `typeCharByChar` initial read)
- Field-content reads for cursor positioning/readFieldContent already use `every { getSurroundingText(any(), any(), any()) }` — these need to be ordered AFTER the more specific matchers (MockK matches most specific first)

**Tests that reference default typing speed (70/15) must be updated to (250/50)**:
- `uses default typing speed and variance` in TypeAppendTextToolTests — update comment from "70/15" to "250/50"

**Definition of Done**:
- [x] All tool-level tests pass with the new verification behavior
- [x] Default speed/variance references updated

### Task 1.5: Update integration tests for verification mocks

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/TextInputIntegrationTest.kt` — modify

Integration tests use `McpIntegrationTestHelper.createMockDependencies()` which creates `typeInputController = mockk(relaxed = true)`. A relaxed mock returns `null` for nullable `getSurroundingText`, which now throws `ActionFailed` during verification.

**Setup changes** (Q-3 fix): In each integration test that exercises typing tools, configure the `typeInputController` mock with an `answers {}` block (same pattern as `setupVerificationMock()`) to dynamically return matching verification responses. Existing `returnsMany` patterns for field-content reads must be replaced with argument-matcher-based mocking to separate verification calls (`afterLength=0`) from field-content reads (`afterLength=10000`).

**Definition of Done**:
- [x] All integration tests pass with the new verification behavior

---

## User Story 2: Update documentation for new defaults

**Why**: The MCP tools documentation in `PROJECT.md` and `MCP_TOOLS.md` references the old default values (70ms/15ms). These must match the implementation.

**Acceptance Criteria**:
- [x] `docs/PROJECT.md` shows `typing_speed` default = 250, `typing_speed_variance` default = 50
- [x] `docs/MCP_TOOLS.md` shows `typing_speed` default = 250, `typing_speed_variance` default = 50

### Task 2.1: Update PROJECT.md

**File**: `docs/PROJECT.md` — modify

Update all occurrences of `default 70` → `default 250` and `default 15` → `default 50` in the text input tools table.

**Definition of Done**:
- [x] All three typing tools show updated defaults in PROJECT.md

### Task 2.2: Update MCP_TOOLS.md

**File**: `docs/MCP_TOOLS.md` — modify

Update the parameter tables for `android_type_append_text`, `android_type_insert_text`, and `android_type_replace_text`:
- `typing_speed` default: 70 → 250
- `typing_speed_variance` default: 15 → 50

**Definition of Done**:
- [x] All three tool parameter tables show updated defaults in MCP_TOOLS.md

---

## Review Findings

| # | Category | Severity | Finding | Resolution |
|---|----------|----------|---------|------------|
| S-2 | Structure (Anti-Verbosity) | INFO | "Key implementation notes" restated code | Trimmed to non-obvious notes only |
| S-3 | Structure (Completeness) | WARNING | Mutex hold-time comment (lines 47-48) says "70ms" | Added to Task 1.1 DoD: update comment to "250ms" |
| Q-2 | QA (Edge Cases) | INFO | No test for cancellation during retry delay | Added `typeCharByChar cancellation during retry delay` test |
| Q-3 | QA (Integration Mocks) | WARNING | `returnsMany` consumed by verification calls | Changed to `answers {}` block that dynamically tracks committed text |
| Q-4 | QA (Test Infrastructure) | WARNING | Verification mock pattern needed as shared helper | Added `setupVerificationMock()` helper |
| A-7 | Architecture (Idempotency) | WARNING | Double-commit if char accepted but getSurroundingText slow | Added pre-retry length check: reads field length before re-committing |
| P-1 | Performance | INFO | effectiveDelay no upper bound | Added `ADAPTIVE_DELAY_MAX_MS = 2000` cap |
| P-3 | Performance | INFO | Additional IPC overhead per character | Accepted tradeoff; documented in KDoc (Task 1.2) |
| T-1 | Technical Correctness | INFO | getSurroundingText charCount semantics | Verified correct for both BMP and supplementary chars |
| T-2 | Technical Correctness | INFO | endsWith assumes cursor stays after committed char | Documented as known limitation; mitigated by pre-retry length check |
