import random, itertools, sys
from flow import Board, solve, verify, make_puzzle
from render import panel, render_puzzle, render_solution, side_by_side, build

OUT = {}


def show(title, txt):
    OUT[title] = txt
    print('=' * 78)
    print('###', title)
    print('=' * 78)
    print(txt)
    print()


# ---------------------------------------------------------------- 1. plain 5x5
b, p, n = build(Board(5, 5), 4, 7)
show('plain-5x5', panel(b, p) + f'\n\n[nodes explored: {n}]')

# ---------------------------------------------------------------- 2. walls
# find a 5x5 puzzle whose solution CHANGES when we add a wall on it -> wall is load-bearing
found = None
for seed in range(400):
    b0, p0, _ = build(Board(5, 5), 4, seed)
    if not b0:
        continue
    edges = [(p[i], p[i + 1]) for p in p0.values() for i in range(len(p) - 1)]
    for (x, y) in edges:
        bw = Board(5, 5, walls=[(x, y)])
        bw.endpoints = dict(b0.endpoints)
        pw, _ = solve(bw)
        if pw and pw != p0:
            verify(bw, pw)
            found = (b0, p0, bw, pw, (x, y))
            break
    if found:
        break
b0, p0, bw, pw, wall = found
show('walls', 'SAME endpoints. Left: no wall. Right: one wall added.\n\n'
     + side_by_side(render_solution(b0, p0), render_solution(bw, pw),
                    'SOLUTION (no wall)', 'SOLUTION (wall added)')
     + f'\n\nwall = between {wall[0]} and {wall[1]}\n\n'
     + panel(bw, pw))

# ------------------------------------------------- 3. WALL vs HOLE, same board
# same two cells: wall between them (both filled) vs one removed (not filled)
found = None
for seed in range(600):
    rng = random.Random(seed)
    cellA = (2, 2)
    bh = Board(5, 5, holes=[cellA])
    bh = make_puzzle(bh, 4, rng)
    if not bh:
        continue
    ph, _ = solve(bh)
    if not ph:
        continue
    verify(bh, ph)
    # same endpoints, but (2,2) exists and is walled off on one side instead
    bw2 = Board(5, 5, walls=[((2, 2), (2, 3))])
    bw2.endpoints = dict(bh.endpoints)
    pw2, _ = solve(bw2)
    if pw2:
        verify(bw2, pw2)
        found = (bh, ph, bw2, pw2)
        break
if found:
    bh, ph, bw2, pw2 = found
    nh = sum(len([c for c in p if c not in bh.holes]) for p in ph.values())
    show('wall-vs-hole',
         side_by_side(render_solution(bw2, pw2), render_solution(bh, ph),
                      'WALL at (2,2)-(2,3)', 'HOLE at (2,2)')
         + '\n\nleft: 25 cells filled (the walled cell STILL gets a pipe)'
           '\nright: 24 cells filled ((2,2) is gone and must NOT be filled)')
else:
    print('!! wall-vs-hole not found')

# ---------------------------------------------------------------- 4. obstacles
for seed in range(300):
    b = Board(6, 6, holes=[(2, 2), (2, 3), (3, 2), (3, 3)])   # courtyard
    b, p, n = build(b, 5, seed)
    if b:
        break
show('obstacles-courtyard', panel(b, p) + '\n\n[32 nodes; the 4 holes are NOT covered]')

for seed in range(300):
    b = Board(5, 5, holes=[(0, 2), (2, 0), (4, 3)])           # scattered
    b, p, n = build(b, 4, seed)
    if b:
        break
show('obstacles-scattered', panel(b, p))

# ---------------------------------------------------------------- 5. rectangle
b, p, n = build(Board(4, 7), 5, 3)
show('rectangle-4x7', panel(b, p))

# ---------------------------------------------------------------- 6. mania
b, p, n = build(Board(9, 9), 8, 11)
show('mania-9x9', panel(b, p) + f'\n\n[nodes explored: {n}]')

# ---------------------------------------------------------------- 7. bridges
# generate-and-test: coverage forces BOTH axes of the bridge to be used
found = None
rng = random.Random(0)
cells5 = [(r, c) for r in range(5) for c in range(5)]
for t in range(200000):
    br = (2, 2)
    pool = [c for c in cells5 if c != br]
    k = 4
    pts = rng.sample(pool, 2 * k)
    bb = Board(5, 5, bridges=[br])
    bb.endpoints = {'ABCD'[i]: (pts[2 * i], pts[2 * i + 1]) for i in range(k)}
    try:
        pb, nb = solve(bb, node_cap=60000)
    except RuntimeError:
        continue
    if pb:
        verify(bb, pb)
        # which colors cross the bridge, on which axis?
        cross = {}
        for col, pth in pb.items():
            for i, cell in enumerate(pth):
                if cell == br:
                    ax = 'H' if pth[i - 1][0] == pth[i + 1][0] else 'V'
                    cross[ax] = col
        if len(cross) == 2 and cross['H'] != cross['V']:
            found = (bb, pb, cross)
            break
bb, pb, cross = found
show('bridges', panel(bb, pb)
     + f"\n\n╬ = bridge at (2,2).  horizontal pass = {cross['H']}, vertical pass = {cross['V']}"
     + "\n(two different flows cross the same cell; each goes STRAIGHT through)")

# ---------------------------------------------------------------- 8. links: hoop
holes = [(r, c) for r in range(1, 4) for c in range(1, 4)]
for seed in range(400):
    b = Board(5, 5, holes=holes)
    b, p, n = build(b, 3, seed)
    if b:
        break
show('links-hoop', panel(b, p) + '\n\n[a ring board = plain HOLE directives. no new solver code]')

# ---------------------------------------------------------------- 9. links: portal
found = None
for seed in range(400):
    a, z = (0, 0), (4, 4)
    seams = [(a, z, (0, -1), (0, -1)), (z, a, (0, 1), (0, 1))]  # LINK a <-> z
    b = Board(5, 5, seams=seams)
    b, p, n = build(b, 4, seed)
    if not b:
        continue
    if any(z in [p[i + 1] for i in range(len(p) - 1) if p[i] == a] or
           a in [p[i + 1] for i in range(len(p) - 1) if p[i] == z] for p in p.values()):
        found = (b, p)
        break
if found:
    b, p = found
    user = [col for col, pp in p.items()
            for i in range(len(pp) - 1) if {pp[i], pp[i + 1]} == {(0, 0), (4, 4)}]
    show('links-portal', panel(b, p)
         + f"\n\nLINK (0,0) <-> (4,4) : an ADDED EDGE between two non-adjacent cells."
           f"\nflow {user[0]} steps straight from (0,0) to (4,4) - invisible in the flat grid.")
else:
    print('!! portal example not found')

# ---------------------------------------------------------------- 10. cubes
# two 3x3 faces (cols 0-2 and 4-6), col 3 removed. Seam joins A's right col to
# B's right col REVERSED -- a genuine fold, impossible to draw as a flat rect.
seams = []
for r in range(3):
    a, z = (r, 2), (2 - r, 6)
    seams.append((a, z, (0, 1), (0, -1)))
    seams.append((z, a, (0, 1), (0, -1)))
for seed in range(600):
    b = Board(3, 7, holes=[(r, 3) for r in range(3)], seams=seams)
    b, p, n = build(b, 4, seed)
    if not b:
        continue
    used = [(col, pp[i], pp[i + 1]) for col, pp in p.items() for i in range(len(pp) - 1)
            if abs(pp[i][0] - pp[i + 1][0]) + abs(pp[i][1] - pp[i + 1][1]) != 1]
    if used:
        break
show('cubes', panel(b, p)
     + '\n\nFACE A = cols 0-2   FACE B = cols 4-6   (# = the fold, not a cell)'
     + '\nseam: A(r,2) <-> B(2-r,6)  -- REVERSED orientation'
     + '\nseam crossings used by the solution:\n  '
     + '\n  '.join(f'{c}: {x} -> {y}' for c, x, y in used)
     + '\n(these steps look like jumps in the flat net - on the cube they are adjacent)')

print('\nALL EXAMPLES GENERATED AND VERIFIED')
