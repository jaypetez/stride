# Screenshot tests (Roborazzi)

Screens and design-system components are covered by **Roborazzi** screenshot
tests running on Robolectric native graphics — on the JVM, with no emulator
(ADR 0007, ANDF-008).

## Commands
| Command | Effect |
|---|---|
| `./gradlew -p android verifyRoborazziDebug` | Compares every capture with its baseline; fails on any diff. |
| `./gradlew -p android recordRoborazziDebug` | Overwrites baselines with fresh captures. |
| `./gradlew -p android compareRoborazziDebug` | Writes `*_compare.png` diff images without failing. |
| `./gradlew -p android check -Proborazzi.test.verify=true` | What CI runs: unit tests + screenshot verification in one pass. |

Baselines live next to each module in `src/test/screenshots/` and are committed.

## Writing a screenshot test
Use `captureScreenshot(path) { … }` from `core:testing`, not Roborazzi's
`captureRoboImage(path) { … }`. It renders on a **paused Compose clock** and
advances 1 s before capturing: infinite animations (progress spinners,
pull-to-refresh) would otherwise keep Compose from going idle and hang the
test, and animated frames would differ between runs.

```kotlin
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class TodayScreenshotTest {
    @Test fun demo() = captureScreenshot("src/test/screenshots/today_demo.png") {
        StrideTheme { TodayScreen(state = sample, onRefresh = {}) }
    }
}
```

### Font-scale, RTL and accessibility checks (AUD-035)
`captureScreenshot(path, variant = ScreenshotVariant.FONT_150 | FONT_200 | RTL)` renders
the same content at 150 % / 200 % text or right-to-left; name the file with
`variantPath(base, variant, theme)` (`today_demo_font150_light.png`). Key screens
(Today, Runs, run detail, Fitness, Plan, Connect, onboarding, You) have these variants.

`captureScreenshot(path, accessibilityChecks = true)` also runs Google's Accessibility
Test Framework (touch targets, contrast, unlabelled nodes, …) over every window and
fails on any error. Compose's own `enableAccessibilityChecks()` is a no-op under
Robolectric, so screenshot tests use Roborazzi's ATF integration; `createComposeRule()`
tests still call `compose.enableAccessibilityChecks()` so the same checks run on a device.

## Pinned rendering inputs
Screenshots must be identical on every machine, so the inputs are pinned:
- **Robolectric SDK:** `ScreenshotDefaults.SDK` (36), applied with `@Config(sdk = [...])`.
- **Qualifiers:** `ScreenshotDefaults.PHONE` = `en-rGB-w411dp-h891dp-xxhdpi`
  (plus `MEDIUM` and `EXPANDED` for adaptive layouts) from `core:testing`. The locale is
  pinned because dates follow the device locale (AUD-034); a test that overrides the
  qualifiers must keep the `en-rGB-` prefix.
- **Graphics:** `robolectric.graphicsMode=NATIVE` and `pixelCopyRenderMode=hardware`,
  set for every test task by the convention plugin.
- **Fonts:** the platform default font bundled with Robolectric's android-all jar — no
  downloadable fonts.
- **Data:** the deterministic demo dataset and `FakeStrideClock` (2026-11-01T07:00Z).

Baselines are recorded on **Linux x86_64 (CI)**. Other platforms may render
anti-aliasing slightly differently, so never re-record on a laptop and commit the result.

## When to re-record
Re-record only when a UI change is **intended**. A re-record must be called out in
the PR description with before/after images (the CI `roborazzi-diffs` artifact has
the `*_compare.png` files). Never re-record to make a failing check pass without
understanding the diff.

## How to re-record (CI)
1. Run the **Android** workflow manually (*Actions → Android → Run workflow*) on your
   branch with **record-screenshots** ticked, or `gh workflow run android.yml --ref <branch> -f record-screenshots=true`.
2. Download the `roborazzi-recorded` artifact and copy the PNGs into place
   (`gh run download <run-id> -n roborazzi-recorded -D android`).
3. Commit them and list the changed baselines in the PR (the template has a checkbox).

## Reviewing a diff
When verification fails, CI uploads the `roborazzi-diffs` artifact containing
`*_compare.png` (reference | actual | diff). Reviewers open those images and confirm
every change is intended.
