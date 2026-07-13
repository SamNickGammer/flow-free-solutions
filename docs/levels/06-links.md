# Links

**Packs:** Hoop, Loop, Chain, Chain Maze.

> ⚠️ **Lowest-confidence doc in this set.** The pack names are confirmed; the exact mechanic is
> inferred from naming and pack listings, **not** from playing the levels. Confirm against real
> screenshots before writing code against this. The good news: under the graph model, *every*
> plausible reading is already covered — see below.

## Most likely reading: board-shape packs

"Hoop", "Loop", "Chain" almost certainly describe the **shape of the playable region**, not a new
game rule:

```
Hoop / Loop (ring)          Chain (linked blobs)

# # . . . # #               . . . # . . .
# . . . . . #               . . . . . . .
. . # # # . .               . . . # . . .
. . # # # . .               # # . # . # #
. . # # # . .               . . . # . . .
# . . . . . #               . . . . . . .
# # . . . # #               . . . # . . .

# = hole                    a chain of chambers joined by narrow necks
```

If that's what they are, they are **[Obstacles](04-obstacles.md)** — pure `HOLE` directives.
**Zero new code.** A ring board is a rectangle with a hole punched in the middle; a chain board is
a rectangle with holes pinching it into linked chambers.

## The other possibility: portals

"Link" *could* mean a **teleport pair** — two non-adjacent cells joined so a flow can step
between them.

That's also covered: **add an edge** between the two cells.

```
LINK 0,0 4,4     →   Edges.add( (0,0) ↔ (4,4) )
```

The graph doesn't care that they aren't adjacent. Flood fill, reachability, and coverage all still
just walk `Edges`. Same as how [Cubes](07-cubes.md) and Warps work — they're all "add an edge".

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
