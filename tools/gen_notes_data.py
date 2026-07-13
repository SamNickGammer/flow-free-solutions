"""Emit verified boards as JSON for the handwritten-notes PDF.

Every board here is solved and passed through verify() before it is written out,
so the diagrams in the PDF cannot quietly violate coverage.
"""
import json, random, sys
from flow import Board, solve, verify, make_puzzle
from render import build

OUT = {}


def pack(name, b, paths, note=''):
    verify(b, paths)
    OUT[name] = {
        'rows': b.rows, 'cols': b.cols,
        'holes': sorted(list(b.holes)),
        'walls': sorted([sorted(list(w)) for w in b.walls]),
        'bridges': sorted(list(b.bridges)),
        'seams': [[list(a), list(z)] for (a, z, _, _) in b.seams],
        'endpoints': {k: [list(a), list(z)] for k, (a, z) in b.endpoints.items()},
        'paths': {k: [list(c) for c in p] for k, p in paths.items()},
        'note': note,
    }
    print(f'  ok  {name:22} {b.rows}x{b.cols}  {len(b.nodes)} nodes')


# 1. plain
b, p, n = build(Board(5, 5), 4, 7)
pack('plain', b, p, f'{n} nodes explored')

# 2. walls — same endpoints, with and without the wall
found = None
for seed in range(400):
    b0, p0, _ = build(Board(5, 5), 4, seed)
    if not b0:
        continue
    edges = [(pp[i], pp[i+1]) for pp in p0.values() for i in range(len(pp)-1)]
    for (x, y) in edges:
        bw = Board(5, 5, walls=[(x, y)])
        bw.endpoints = dict(b0.endpoints)
        pw, _ = solve(bw)
        if pw and pw != p0:
            found = (b0, p0, bw, pw)
            break
    if found:
        break
b0, p0, bw, pw = found
pack('nowall', b0, p0, 'no wall — A runs straight across')
pack('wall', bw, pw, 'one wall — A must detour')

# 3. obstacles
for seed in range(300):
    b, p, n = build(Board(6, 6, holes=[(2,2),(2,3),(3,2),(3,3)]), 5, seed)
    if b:
        break
pack('courtyard', b, p, '36 cells − 4 holes = 32 to cover')

# 4. bridges
rng = random.Random(0)
cells5 = [(r, c) for r in range(5) for c in range(5)]
found = None
for t in range(200000):
    br = (2, 2)
    pool = [c for c in cells5 if c != br]
    pts = rng.sample(pool, 8)
    bb = Board(5, 5, bridges=[br])
    bb.endpoints = {'ABCD'[i]: (pts[2*i], pts[2*i+1]) for i in range(4)}
    try:
        pb, _ = solve(bb, node_cap=60000)
    except RuntimeError:
        continue
    if pb:
        cross = {}
        for col, pth in pb.items():
            for i, cell in enumerate(pth):
                if cell == br:
                    cross['H' if pth[i-1][0] == pth[i+1][0] else 'V'] = col
        if len(cross) == 2 and cross['H'] != cross['V']:
            found = (bb, pb, cross)
            break
bb, pb, cross = found
pack('bridges', bb, pb, f"H pass = {cross['H']}, V pass = {cross['V']}")

# 5. rectangle
b, p, n = build(Board(4, 7), 5, 3)
pack('rectangle', b, p, 'R != C — nothing changes')

# 6. mania
b, p, n = build(Board(9, 9), 8, 11)
pack('mania', b, p, f'{n} nodes explored (vs ~24 for 5x5)')

# 7. hoop (links)
for seed in range(400):
    b, p, n = build(Board(5, 5, holes=[(r, c) for r in range(1,4) for c in range(1,4)]), 3, seed)
    if b:
        break
pack('hoop', b, p, 'a ring = plain HOLE directives')

# 8. cubes
seams = []
for r in range(3):
    a, z = (r, 2), (2-r, 6)
    seams.append((a, z, (0, 1), (0, -1)))
    seams.append((z, a, (0, 1), (0, -1)))
for seed in range(600):
    b, p, n = build(Board(3, 7, holes=[(r, 3) for r in range(3)], seams=seams), 4, seed)
    if not b:
        continue
    if any(abs(pp[i][0]-pp[i+1][0]) + abs(pp[i][1]-pp[i+1][1]) != 1
           for pp in p.values() for i in range(len(pp)-1)):
        break
pack('cubes', b, p, 'seam: A(r,2) <-> B(2-r,6), REVERSED')

with open(sys.argv[1] if len(sys.argv) > 1 else 'boards.json', 'w') as f:
    json.dump(OUT, f, indent=1)
print(f'\nwrote {len(OUT)} verified boards')
