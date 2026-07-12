# 03 — Architecture: the screen-aware assistant

The goal: an app that **stays running in the background**, and when you open a Flow Free level,
lets you **capture → solve → see the answer** without leaving the game. Target platform:
**Android** (the APIs for background overlay + screen capture + gesture injection all exist and
are usable by a normal app; iOS does not allow this).

## End-to-end flow

```
 ┌─ Foreground service (always alive) ─────────────────────────────┐
 │   • persistent notification (keeps process from being killed)   │
 │   • floating overlay button (SYSTEM_ALERT_WINDOW)               │
 └──────────────┬──────────────────────────────────────────────────┘
                │ user taps the button while Flow Free is open
                ▼
      ┌───────────────────┐
      │ 1. Screen capture │  MediaProjection → single frame bitmap
      └─────────┬─────────┘
                ▼
      ┌───────────────────┐
      │ 2. Grid detection │  find board bounds, cell size, K colors
      └─────────┬─────────┘
                ▼
      ┌───────────────────┐
      │ 3. Build model    │  R×C grid + endpoint pairs (+ bridges)
      └─────────┬─────────┘
                ▼
      ┌───────────────────┐
      │ 4. Solve          │  search solver (docs/04) → paths per color
      └─────────┬─────────┘
                ▼
      ┌───────────────────┐         ┌────────────────────────────┐
      │ 5a. Overlay draw  │   or    │ 5b. Auto-draw (gestures)   │
      │  (you trace it)   │         │  AccessibilityService swipes│
      └───────────────────┘         └────────────────────────────┘
```

## Components

### 1. Background presence — Foreground Service + overlay button

- A **foreground service** with an ongoing notification keeps the process alive so the assistant
  is "always there". This is the sanctioned Android way to stay running.
- A **floating button** drawn with the `SYSTEM_ALERT_WINDOW` / `TYPE_APPLICATION_OVERLAY`
  permission sits on top of other apps. Tapping it triggers a capture+solve.
- Permissions to request up front: *Display over other apps* and (on first capture) the
  MediaProjection consent dialog.

### 2. Screen capture — MediaProjection

- `MediaProjectionManager` gives a one-time user consent, then the app can grab frames via an
  `ImageReader` + `VirtualDisplay`.
- We only need **one frame** per solve (the puzzle is static), so grab, copy the bitmap, and
  release — cheap and battery-friendly.
- Capture the whole screen; cropping to the board happens in detection.

### 3. Grid detection — from pixels to a puzzle model

The hardest CV step. Approach (build simplest-first, upgrade if needed):

1. **Locate the board** — Flow Free's board is a large square/grid region of roughly uniform
   background with a strong grid line pattern. Find the largest bounded square region; detect
   grid lines to get `R`, `C`, and cell pixel size.
2. **Sample each cell** — take the center pixel(s) of each cell. A cell is either background
   (empty) or a saturated color dot (endpoint).
3. **Cluster colors** — group the non-background samples into `K` clusters; each cluster with
   exactly two cells is one endpoint pair. (Validates: every color must appear exactly twice.)
4. **Bridges** — detect the bridge glyph (a distinct cross/overpass icon) on a cell; mark it as a
   crossover in the model.

Ship order: start with a **calibration-friendly** detector (let the user confirm/adjust the
detected grid the first time), then automate. Real screens drift — DPI, themes, ad banners,
notches — so keep a manual nudge for grid bounds. *(ponytail: center-pixel sampling + manual
grid confirm first; add OpenCV line-detection only if simple sampling misreads boards.)*

Detection can also be sidestepped for early testing by importing a **text grid** (the format in
[01-problem-analysis.md](01-problem-analysis.md)) — that decouples solver work from CV work.

### 4. Solve

Feed the detected model to the search solver (**[04-solver-design.md](04-solver-design.md)**).
Runs on a background thread; typical designed levels solve in well under a second. Return a
per-color ordered path list in cell coordinates.

### 5. Show the answer — two modes

**5a. Overlay (manual trace):** draw the solution paths as colored lines in the overlay window,
aligned to the detected grid geometry. You then trace them yourself on the real game. Safest,
zero interaction with the game process.

**5b. Auto-draw (one-tap solve):** use an **AccessibilityService** with `dispatchGesture` to
inject swipe gestures — one continuous swipe per color, from endpoint to endpoint along the
solved path, converting cell coordinates back to screen pixels. This physically draws the flow
in the game.

> Coordinate mapping is the linchpin for both modes: `screen_px = board_origin + cell_index *
> cell_size + cell_size/2`. Grid detection must produce accurate `board_origin` and `cell_size`,
> or overlay lines/gestures land off-cell. Keep a calibration offset knob.

## Module boundaries (so pieces stay testable in isolation)

```
:solver       pure Kotlin/JVM — model + search. No Android deps. Unit-testable on desktop.
:detection    bitmap → model. Depends on solver's model types only.
:capture      MediaProjection wrapper → bitmap.
:overlay      floating button + solution rendering (Android views/canvas).
:autodraw     AccessibilityService gesture injection.
:app          wires the above; permissions, service lifecycle, settings.
```

The `:solver` module is plain JVM so it can be developed and tested **on the desktop CLI first**
(Phase 1) before any Android code exists.

## Platform & ethics notes

- **Android only.** iOS sandboxing forbids background screen capture of other apps and gesture
  injection. A jailbreak or a "type in the grid manually" iOS path is the only option there —
  out of scope.
- This is a **personal assist/solver tool**. It reads the screen you're looking at and draws on
  your own device. No network, no other users' data — keep it that way (fully on-device).
