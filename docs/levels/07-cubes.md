# Cubes

**Packs:** Cube Pack, Triple Cube, Cube Stacks.

> ⚠️ **Inferred mechanic.** Confirm against a real screenshot. As with [Links](06-links.md), the
> graph model already covers the likely readings — see "Why this is safe" below.

## What it is

A **3D board**: the puzzle is played on the faces of a cube (or several stacked cubes), shown as
an unfolded **net** or an isometric view. Flows **continue across the fold** from one face onto
the next.

```
unfolded net                       what the fold means

      ┌───┐
      │ T │                        a flow leaving the RIGHT edge of face F
  ┌───┼───┼───┬───┐                continues onto the LEFT edge of face R —
  │ L │ F │ R │ B │                they are adjacent on the cube,
  └───┼───┼───┴───┘                even though the net may not show them touching
      │ D │
      └───┘
```

The cells on adjoining face edges are **adjacent in 3D** even when they're far apart (or
differently oriented) in the 2D net.

## Graph transform

**Add edges** across the fold seams.

```
Edges = (normal within-face adjacencies)
      + (seam edges joining cells on adjoining face borders)
```

That's the whole thing. The solver has no concept of "faces", "cubes", or "3D". It has a node set
and an edge set. A seam edge is just an edge.

## The one real difficulty: seam mapping

The hard part isn't solving — it's **getting the seam adjacency right**.

- Which face borders which, and in **what orientation**? Fold a net and the edges meet with
  rotations. Face `R`'s left column maps to face `F`'s right column — but possibly **reversed**,
  depending on the net layout and how the faces are oriented.
- Getting the orientation backwards produces a board that's subtly, quietly wrong: it will look
  solvable and produce a solution the game rejects.

**Build the seam map as explicit data, not as clever index math.** A table of
`(faceA, edgeA, faceB, edgeB, reversed?)` is boring, checkable, and correct at 3am. Index
arithmetic that "derives" the folding is exactly the kind of cleverness that produces a bug you
can't see.

*(ponytail: hardcode the seam table for the standard net. Derive it programmatically only if the
game ships multiple net layouts — and even then, generate the table and check it in.)*

## Why this is safe

Whatever Cubes turns out to be — a folded net, stacked cubes with vertical seams, or an isometric
render of the same — the transform is **"add edges"**. No solver change. The only work is the
seam map.

Same for **Warps** (wrap the board edges: add edges joining opposite borders) and, if it's
portals, **Links**. They are all one operation.

## Solver notes

**Zero changes** — *provided* the flood fills read `Edges`.

> **The latent bug:** any code that computes neighbors as `(r±1, c)` / `(r, c±1)` instead of
> reading `Edges` will **silently ignore every seam**. The solver won't crash; it'll just never
> route across a fold and declare the board unsolvable.

This is the same bug as [Links](06-links.md) portals and Warps wrapping — and it's the reason the
whole design insists on `Edges` as the source of truth. Write the flood fill against `Edges`
once, and Cubes, Warps, Links, and Walls are all correct for free.

**Grep rule:** if `r+1` or `c-1` appears anywhere in the search or the flood fill, that's a bug.
Neighbors come from `Edges`, always. Build the adjacency once at parse time; never recompute it
geometrically.

## Detection notes

Hardest detection target in the game, by a wide margin:

- The board isn't one rectangle — it's **several face-grids** in a net or isometric layout.
- An isometric render means cells are **not axis-aligned squares** — center sampling and grid-line
  detection both get much harder.

Realistic plan: **detection for Cubes is a stretch goal.** Support it via **manual entry** (the
text format handles it fine — nodes + seam edges) long before attempting automatic CV. Don't let
Cubes detection block the phases that matter.

## Test fixtures

- A single cube net where a flow **must** cross a seam to solve — proves seam edges are live.
- A **reversed-orientation seam** — proves the seam table's `reversed?` flag is actually honored.
  This is the assertion that catches the quiet folding bug.
- **Negative test:** drop the seam edges; assert the board becomes unsolvable. Proves the seams
  are load-bearing and not decorative.
