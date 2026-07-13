"""Flow Free solver (graph model) + diagram generator.

Validates the design in docs/04-solver-design.md:
  - neighbours come from `adj`, never r+/-1 arithmetic
  - coverage counts `nodes`, never rows*cols
Every variant is a parse-time transform: remove edges (walls), remove nodes
(holes), add edges (seams/portals), mark dual-pass nodes (bridges).
"""
import random, sys
from functools import lru_cache

U, D, L, R = (-1, 0), (1, 0), (0, -1), (0, 1)
DIRS = [U, D, L, R]
AXIS = {U: 'V', D: 'V', L: 'H', R: 'H'}


class Board:
    def __init__(self, rows, cols, holes=(), walls=(), bridges=(), seams=()):
        self.rows, self.cols = rows, cols
        self.holes = set(holes)
        self.walls = {frozenset(w) for w in walls}      # frozenset({cellA, cellB})
        self.bridges = set(bridges)
        self.nodes = {(r, c) for r in range(rows) for c in range(cols)} - self.holes
        self.adj = {n: [] for n in self.nodes}
        for (r, c) in self.nodes:
            for d in DIRS:
                nb = (r + d[0], c + d[1])
                if nb in self.nodes and frozenset({(r, c), nb}) not in self.walls:
                    self.adj[(r, c)].append((nb, d, d))   # (neighbour, dir_out, dir_in)
        self.seams = list(seams)
        for (a, b, d_out, d_in) in self.seams:            # add-edges transform
            self.adj[a].append((b, d_out, d_in))
        self.endpoints = {}                               # color -> (cellA, cellB)

    def normals(self):
        return self.nodes - self.bridges

    def step(self, cell, d):
        for (nb, d_out, d_in) in self.adj[cell]:
            if d_out == d:
                return nb, d_in
        return None, None


class State:
    """occ: normal cell -> color.  bocc: bridge cell -> {'H':color,'V':color}."""

    def __init__(self, board):
        self.b = board
        self.occ = {}
        self.bocc = {x: {'H': None, 'V': None} for x in board.bridges}
        self.heads, self.targets, self.done, self.paths = {}, {}, {}, {}
        for col, (a, z) in board.endpoints.items():
            self.occ[a] = col
            self.occ[z] = col
            self.heads[col] = a
            self.targets[col] = z
            self.done[col] = False
            self.paths[col] = [a]

    def free_normal(self, cell):
        return cell not in self.occ and cell not in self.b.bridges

    # a cell is "passable" for relaxed reachability if it can still take a flow
    def passable(self, cell):
        if cell in self.b.bridges:
            return self.bocc[cell]['H'] is None or self.bocc[cell]['V'] is None
        return cell not in self.occ

    def traverse(self, color, head, d):
        """Step from head in direction d, sliding straight through any bridges.
        Returns (landing_cell, [(bridge, axis), ...]) or None if illegal."""
        marks = []
        cur, dd = head, d
        while True:
            nb, d_in = self.b.step(cur, dd)
            if nb is None:
                return None
            if nb in self.b.bridges:
                ax = AXIS[d_in]
                if self.bocc[nb][ax] is not None:
                    return None                    # that axis already used
                marks.append((nb, ax))
                cur, dd = nb, d_in                 # forced: continue straight
                continue
            if nb == self.targets[color] and not self.done[color]:
                return nb, marks                   # closing the flow
            if nb in self.occ:
                return None
            return nb, marks

    def moves(self, color):
        out = []
        for d in DIRS:
            res = self.traverse(color, self.heads[color], d)
            if res:
                out.append((d, res[0], res[1]))
        return out

    def push(self, color, landing, marks):
        undo = (color, self.heads[color], landing, marks, self.done[color])
        for (br, ax) in marks:
            self.bocc[br][ax] = color
            self.paths[color].append(br)
        self.paths[color].append(landing)
        if landing == self.targets[color]:
            self.done[color] = True
        else:
            self.occ[landing] = color
        self.heads[color] = landing
        return undo

    def pop(self, undo):
        color, oldhead, landing, marks, wasdone = undo
        self.heads[color] = oldhead
        self.done[color] = wasdone
        if landing != self.targets[color]:
            del self.occ[landing]
        self.paths[color].pop()
        for (br, ax) in marks:
            self.bocc[br][ax] = None
            self.paths[color].pop()

    def uncovered(self):
        n = sum(1 for c in self.b.normals() if c not in self.occ)
        for x in self.b.bridges:
            n += (self.bocc[x]['H'] is None) + (self.bocc[x]['V'] is None)
        return n

    # ---- pruning (all relaxed => sound: never prunes a reachable solution) ----
    def reach_from(self, start, extra_ok=()):
        seen, stack = {start}, [start]
        while stack:
            cur = stack.pop()
            for (nb, _, _) in self.b.adj[cur]:
                if nb in seen:
                    continue
                if self.passable(nb) or nb in extra_ok:
                    seen.add(nb)
                    stack.append(nb)
        return seen

    def prune(self):
        live = [c for c in self.heads if not self.done[c]]
        # 1. every incomplete color must still reach its target
        for c in live:
            if self.targets[c] not in self.reach_from(self.heads[c], {self.targets[c]}):
                return True
        # 2. every unfilled node must be reachable by some live head
        if live:
            covered = set()
            for c in live:
                covered |= self.reach_from(self.heads[c], {self.targets[c]})
            for cell in self.b.normals():
                if cell not in self.occ and cell not in covered:
                    return True
            for x in self.b.bridges:
                if (self.bocc[x]['H'] is None or self.bocc[x]['V'] is None) and x not in covered:
                    return True
        elif self.uncovered():
            return True
        # 3. deadend: a free normal cell needs >=2 usable neighbours
        usable_extra = {self.heads[c] for c in live} | {self.targets[c] for c in live}
        for cell in self.b.normals():
            if cell in self.occ:
                continue
            k = sum(1 for (nb, _, _) in self.b.adj[cell]
                    if self.passable(nb) or nb in usable_extra)
            if k < 2:
                return True
        return False


def solve(board, node_cap=4_000_000):
    st = State(board)
    calls = [0]

    def rec():
        calls[0] += 1
        if calls[0] > node_cap:
            raise RuntimeError('node cap')
        if st.prune():
            return False
        live = [c for c in st.heads if not st.done[c]]
        if not live:
            return st.uncovered() == 0
        best, bestmv = None, None
        for c in live:
            mv = st.moves(c)
            if not mv:
                return False
            if best is None or len(mv) < len(bestmv):
                best, bestmv = c, mv
        for (_, landing, marks) in bestmv:
            undo = st.push(best, landing, marks)
            if rec():
                return True
            st.pop(undo)
        return False

    if rec():
        return {c: list(p) for c, p in st.paths.items()}, calls[0]
    return None, calls[0]


# ---------------------------------------------------------------- verification
def verify(board, paths):
    """Independent check - does not trust the solver."""
    fill = {}
    bfill = {x: {} for x in board.bridges}
    for col, p in paths.items():
        a, z = board.endpoints[col]
        assert p[0] == a and p[-1] == z, f'{col}: path does not join its endpoints'
        for i in range(len(p) - 1):
            x, y = p[i], p[i + 1]
            assert any(nb == y for (nb, _, _) in board.adj[x]), \
                f'{col}: step {x}->{y} is not an edge (wall/hole violated)'
        for i, cell in enumerate(p):
            if cell in board.bridges:
                prv, nxt = p[i - 1], p[i + 1]
                ax = 'H' if prv[0] == nxt[0] else 'V'
                assert prv[0] == nxt[0] or prv[1] == nxt[1], f'{col}: turned on a bridge {cell}'
                assert ax not in bfill[cell], f'bridge {cell} axis {ax} used twice'
                bfill[cell][ax] = col
            else:
                assert cell not in fill, f'cell {cell} covered by two flows'
                fill[cell] = col
    for cell in board.normals():
        assert cell in fill, f'COVERAGE: node {cell} left empty'
    for x in board.bridges:
        assert set(bfill[x]) == {'H', 'V'}, f'bridge {x} not crossed on both axes'
    for h in board.holes:
        assert h not in fill, f'hole {h} was filled'
    return True


# ------------------------------------------------------------------ generation
def hamiltonian(board, rng, tries=400):
    nodes = sorted(board.nodes)
    n = len(nodes)

    def connected_ok(cur, unvis):
        if not unvis:
            return True
        seen, stack = set(), [next(iter(unvis))]
        while stack:
            x = stack.pop()
            if x in seen:
                continue
            seen.add(x)
            for (nb, _, _) in board.adj[x]:
                if nb in unvis and nb not in seen:
                    stack.append(nb)
        return len(seen) == len(unvis)

    for _ in range(tries):
        start = rng.choice(nodes)
        path, unvis = [start], set(nodes) - {start}
        budget = [200_000]

        def dfs(cur):
            budget[0] -= 1
            if budget[0] < 0:
                raise TimeoutError
            if not unvis:
                return True
            nbs = [nb for (nb, _, _) in board.adj[cur] if nb in unvis]
            rng.shuffle(nbs)
            nbs.sort(key=lambda x: sum(1 for (q, _, _) in board.adj[x] if q in unvis))
            for nb in nbs:
                unvis.discard(nb)
                path.append(nb)
                if connected_ok(nb, unvis) and dfs(nb):
                    return True
                path.pop()
                unvis.add(nb)
            return False

        try:
            if dfs(start) and len(path) == n:
                return path
        except TimeoutError:
            continue
    return None


def make_puzzle(board, k, rng, seed_tries=300):
    """Cut a Hamiltonian path into k segments (each >=2 cells) -> guaranteed solvable."""
    for _ in range(seed_tries):
        ham = hamiltonian(board, rng)
        if not ham:
            continue
        n = len(ham)
        if n < 2 * k:
            return None
        for _ in range(200):
            cuts = sorted(rng.sample(range(1, n), k - 1))
            segs, prev = [], 0
            for cpt in cuts + [n]:
                segs.append(ham[prev:cpt])
                prev = cpt
            if all(len(s) >= 2 for s in segs):
                letters = 'ABCDEFGHIJKLMNOP'
                board.endpoints = {letters[i]: (s[0], s[-1]) for i, s in enumerate(segs)}
                return board
    return None
