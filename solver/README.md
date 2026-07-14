# `:solver` — the engine

Pure **Kotlin/JVM. Zero Android dependencies.** No `java.awt`, no desktop-only APIs, JDK 17
toolchain. The same module runs the desktop CLI and drops straight into the Android app.

```bash
./gradlew test                       # 17 tests: every variant + 29 real Flow Free levels
./gradlew cli                        # build build/libs/flowsolve.jar
java -jar build/libs/flowsolve.jar puzzle.txt
./gradlew bench --args="src/test/resources/real 20000"
```

## It solves real levels

Benchmarked against **29 actual Flow Free levels** (regular / extreme / jumbo, 5×5 → 14×14):

| | |
|---|---|
| levels solved & independently verified | **28 / 28** (+1 unsolvable board correctly rejected) |
| real 14×14 Jumbo | **325 ms** |
| worst case in the corpus | **1.6 s** |

## Two engines, and why

`Flow.solve()` picks one:

| Engine | Handles | Why |
|---|---|---|
| **`SatEngine`** | everything real — walls, holes, seams, rectangles, mania | It is the only one that works. |
| **`Solver`** (DFS) | **bridges** | SAT's "every cell has exactly one colour" cannot express a cell carrying **two** flows. Bridges only appear on small boards, where DFS is instant. |

### The DFS engine lost, and it lost badly

`Solver.kt` is a real search solver — graph model, most-constrained-colour, forced-move
propagation, region-based stranded pruning, wall-hugging move ordering, primitive-array hot loops
with no hashing. Every one of those helped; per-node cost fell ~90×.

It still **could not solve a real 12×12 in 85,000,000 nodes and 90 seconds.**

The same boards go through SAT in milliseconds. Matt Zucker — who wrote the canonical Flow Free
search solver — published a follow-up titled *"eating SAT-flavored crow"* for exactly this reason.
We cited that post and picked search anyway. The benchmark is what corrected us.

The DFS engine is kept because it earns its place: it handles bridges, and it is a second,
independent implementation to cross-check against.

## The SAT encoding

```
X(i,c)   cell i has colour c
Y(e)     edge e carries a pipe

1. every cell has EXACTLY ONE colour
2. endpoint cells have their given colour
3. degree: an endpoint has EXACTLY ONE incident pipe, every other cell EXACTLY TWO
           ← this is also what forces FULL COVERAGE: a cell with no pipe cannot have degree 2
4. a pipe joins cells of the SAME colour:  Y(e) -> (X(u,c) <-> X(v,c))
```

That forces each colour into disjoint paths and cycles, with the endpoints the only degree-1 cells
— so the endpoint component is always a valid path. A colour can *also* throw off a free-floating
**cycle**, which is not a legal solution. So: solve → find cycles → add a clause forbidding that
exact ring → solve again. Converges in a handful of rounds (usually zero).

**Variants are free**, because the CNF is built from the graph: a wall means the edge variable
never exists; a hole means the cell is not a node; a cube seam is just another edge.

Solver: **Sat4j** — pure Java, no JNI, **Android-compatible**.

## Nothing ships unverified

`Verifier` does not trust either engine. It re-walks every returned path from scratch and asserts:

- each flow joins its two endpoints
- every step is a **real edge** (so a wall or hole violation is caught)
- no cell is used twice (except a valid bridge H/V pair)
- **every node is covered**, and no hole is filled
- no flow turns on a bridge; each bridge is crossed on both axes

The CLI runs it on every answer before printing.

## Detection: screenshot -> board  (`flow.detect`)

```bash
./gradlew shot   --args="/tmp/shot.png"      # render a synthetic Flow Free screenshot
./gradlew detect --args="/tmp/shot.png"      # read it, solve it, print screen coordinates
./gradlew detect --args="/tmp/shot.png --bounds 34,34,537,537 --size 9x9"   # manual calibration
```

Pipeline: **find board -> size the grid -> classify cells -> pair the dots -> find walls**. It
outputs a `Board` *and* the pixel geometry (`Grid.centerOf(r,c)`) that the overlay draws with and
auto-draw aims swipes at.

Zero Android types. Android supplies `Bitmap.getPixels()` -> `IntArray`; the desktop CLI supplies
`ImageIO` -> `IntArray`. **`ImageIO` lives only in `flow.desktop` — it does not exist on Android.**

### It refuses to guess

A wrong board does not fail loudly. It reports "unsolvable" (and you blame the solver), or — with
auto-draw armed — **it swipes the wrong cells and wrecks a half-finished level.** So any doubt
returns **no board** plus a list of `Problem`s, and the app falls back to manual calibration.

The strongest self-check is that **every colour has exactly two dots**. Which is also why dot
pairing is a *matching* problem, not clustering: threshold clustering needs a tolerance loose
enough to survive antialiasing and tight enough to separate Flow Free's yellow from its orange
(only ~5,700 apart in squared RGB). The first version merged them into one four-dot "colour".
Greedy nearest-twin matching needs no tolerance at all.

### ⚠️ What is NOT yet proven

Detection is tested by a **render -> detect -> compare** round-trip against known ground truth
(walls vs grid lines, walls vs holes, rows vs cols, holes, non-square boards, end-to-end solve).

**That proves the pipeline is sound. It does NOT prove it survives a real screenshot.** Real ones
bring themes, gradients, drop shadows, DPI scaling, notches and ad banners. The synthetic renderer
adds jitter and antialiasing so the test is not self-fulfilling, but it is still a rendering of my
own assumptions.

**Real Flow Free screenshots are the actual validation, and I do not have any.** Two thresholds
(`Detector.BG_TOLERANCE`, and the wall-brightness cutoff) are calibration knobs sitting at values
tuned against synthetic images.

## Puzzle format

One parser, every variant. Comments are `//` — **not `#`, which is the hole glyph.**

```
5 5
. . . . B
B . . . C
A . A . .
. D . . .
D C . . .
WALL 2,1 2,2       // block the edge between two adjacent cells — both still exist & must be filled
HOLE 2,3           // cell is not on the board — excluded from coverage
BRIDGE 3,2         // crossover: two flows pass straight through ('+' in the grid also works)
SEAM 0,2 2,6 R R   // add an edge between non-adjacent cells (cube fold / warp / portal)
```

Grid: `.` empty · `#` hole · `+` bridge · letter = endpoint (**case-sensitive** — real levels use
`b` and `B` as different colours).

## Using it from Android

No wrapper needed. It's a plain JVM library:

```kotlin
val board = Parser.parse(puzzleText)      // or build a Board directly from the grid detector
val solution = Flow.solve(board, budgetMs = 3_000)   // null = genuinely unsolvable
solution?.paths                           // Map<Char, List<Cell>> — ordered, endpoint to endpoint
```

`budgetMs` matters on a phone: a solve must never spin forever while the user stares at a spinner
over their game. Exceeding it throws `Solver.GaveUp`, which is *distinct from unsolvable* — show
"couldn't solve this one", not a wrong answer.

`paths` comes back ordered from one endpoint to the other, which is exactly what the overlay draws
and what the auto-draw gesture traces.
