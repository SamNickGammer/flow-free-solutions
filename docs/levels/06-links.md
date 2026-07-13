# Links

**Packs:** Hoop, Loop, Chain, Chain Maze.

> ⚠️ **Lowest-confidence doc in this set.** The pack names are confirmed; the exact mechanic is
> inferred from naming and pack listings, **not** from playing the levels. Confirm against real
> screenshots before writing code against this. The good news: under the graph model, *every*
> plausible reading is already covered — see below.

## Reading A: board-shape packs (most likely)

"Hoop", "Loop", "Chain" almost certainly describe the **shape of the playable region**, not a new
game rule. If so they are **[Obstacles](04-obstacles.md)** — pure `HOLE` directives, **zero new
code**. A ring board is a rectangle with the middle punched out.

### See it — a Hoop board

> Generated and machine-verified. 5×5 with the inner 3×3 removed → **16 nodes** in a ring.

```
PUZZLE                  SOLUTION
──────                  ────────
 .   A   B   .   .       a───A   B───b───b
                         │               │
 .   #   #   #   .       a   #   #   #   b
                         │               │
 .   #   #   #   .       a   #   #   #   b
                         │               │
 .   #   #   #   B       a   #   #   #   B
                         │
 .   A   C   .   C       a───A   C───c───C
```

A ring board forces flows into **long single-file runs** — there's no room to manoeuvre. Note this
is nothing but `HOLE` directives; the solver has no idea it's solving a "Hoop".

## Reading B: portals

"Link" *could* instead mean a **teleport pair** — two non-adjacent cells joined so a flow steps
straight between them. Also covered: **add an edge**.

```
LINK 0,0 4,4     →   Edges.add( (0,0) ↔ (4,4) )
```

### See it — a portal board

> Generated and machine-verified. Plain 5×5 **plus one added edge** `(0,0) ↔ (4,4)`.

```
PUZZLE                  SOLUTION
──────                  ────────
 .   A   .   .   .       a───A   d───d───d
                                 │       │
 .   D   .   .   .       d───D   d───d   d
                         │           │   │
 .   .   .   .   D       d───d───d───d   D

 .   .   .   .   C       c───c───c───c───C
                         │
 .   C   B   B   A       c───C   B───B   A
```

Flow **A** has endpoints at `(0,1)` and `(4,4)` — opposite corners of the board. Its entire path is
just **three cells**: `A(0,1) → a(0,0) → ⚡ → A(4,4)`. It steps from the top-left corner **directly
to the bottom-right** through the portal.

That jump is **invisible in the flat grid** — which is exactly the point. The route looks
impossible until you remember the edge exists. The graph doesn't care that the two cells aren't
neighbours; flood fill and coverage just walk `Edges`.

Same mechanism as [Cubes](07-cubes.md) seams and Warps wrapping — all three are "add an edge".

## Why this doc is safe to be wrong

This is the payoff of the graph model, stated bluntly:

| If Links turns out to be… | Transform | New solver code |
|---|---|---|
| Ring/chain board shapes | `HOLE` (remove nodes) | **none** |
| Teleport/portal pairs | `LINK` (add edges) | **none** |
| Both | both | **none** |

**Either way it's a parse-time transform.** So there's no reason to block on resolving this —
build the solver, and whichever Links turns out to be, it's a few lines in the parser.

## Solver notes

- **Ring/chain topology is genuinely interesting to the search**, regardless of mechanism. Narrow
  necks between chambers are exactly where the **stranded-pocket prune** earns its keep: fill a
  neck wrong and you seal off a chamber. Expect this prune to fire constantly on these boards.
- If it's portals: the only real assumption to check is that **reachability doesn't assume edges
  are geometrically adjacent**. If any flood fill computes neighbors as `(r±1, c)` / `(r, c±1)`
  instead of reading `Edges`, portals silently do nothing. Same latent bug as
  [Cubes](07-cubes.md) — and the same fix.

## Action item

**Grab a screenshot of a Hoop/Loop/Chain level and settle this.** It's a 30-second check that
turns this doc from inference into fact. Until then, don't hardcode anything about Links.

## Test fixtures

Deferred until the mechanic is confirmed. Once known, it reduces to existing fixtures — either
the Obstacles set or an added-edge test like the Cubes/Warps set.
