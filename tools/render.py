"""ASCII renderer: PUZZLE panel + SOLUTION panel, side by side."""
from flow import Board, solve, verify, make_puzzle, DIRS

DOT, HOLE = '.', '#'


def _canvas(board):
    return [[' '] * (4 * board.cols) for _ in range(2 * board.rows - 1)]


def _walls_onto(board, cv):
    for w in board.walls:
        (r1, c1), (r2, c2) = sorted(w)
        if r1 == r2:                                   # vertical wall (blocks L<->R)
            cv[2 * r1][4 * c1 + 3] = '┃'
        else:                                          # horizontal wall (blocks U<->D)
            for j in range(3):
                cv[2 * r1 + 1][4 * c1 + j] = '━'


def render_puzzle(board):
    cv = _canvas(board)
    ep = {cell: col for col, (a, z) in board.endpoints.items() for cell in (a, z)}
    for r in range(board.rows):
        for c in range(board.cols):
            cell = (r, c)
            if cell in board.holes:
                g = HOLE
            elif cell in ep:
                g = ep[cell]
            elif cell in board.bridges:
                g = '╬'
            else:
                g = DOT
            cv[2 * r][4 * c + 1] = g
    _walls_onto(board, cv)
    return '\n'.join(''.join(row).rstrip() for row in cv)


def render_solution(board, paths):
    cv = _canvas(board)
    ep = {cell: col for col, (a, z) in board.endpoints.items() for cell in (a, z)}
    for r in range(board.rows):
        for c in range(board.cols):
            if (r, c) in board.holes:
                cv[2 * r][4 * c + 1] = HOLE
    for col, p in paths.items():
        for cell in p:
            r, c = cell
            if cell in board.bridges:
                cv[2 * r][4 * c + 1] = '╬'
            elif cell in ep:
                cv[2 * r][4 * c + 1] = col            # uppercase = endpoint
            else:
                cv[2 * r][4 * c + 1] = col.lower()    # lowercase = pipe
        for i in range(len(p) - 1):
            (r1, c1), (r2, c2) = p[i], p[i + 1]
            if abs(r1 - r2) + abs(c1 - c2) != 1:
                continue                              # seam edge: drawn per-face
            if r1 == r2:
                cc = min(c1, c2)
                for j in range(3):
                    cv[2 * r1][4 * cc + 2 + j] = '─'
            else:
                rr = min(r1, r2)
                cv[2 * rr + 1][4 * c1 + 1] = '│'
    _walls_onto(board, cv)
    return '\n'.join(''.join(row).rstrip() for row in cv)


def side_by_side(left, right, lhead='PUZZLE', rhead='SOLUTION', gap=6):
    ls, rs = left.split('\n'), right.split('\n')
    w = max(len(x) for x in ls + [lhead])
    n = max(len(ls), len(rs))
    ls += [''] * (n - len(ls))
    rs += [''] * (n - len(rs))
    out = [lhead.ljust(w + gap) + rhead]
    out += ['─' * len(lhead) + ' ' * (w + gap - len(lhead)) + '─' * len(rhead)]
    for a, b in zip(ls, rs):
        out.append(a.ljust(w + gap) + b)
    return '\n'.join(x.rstrip() for x in out)


def panel(board, paths):
    return side_by_side(render_puzzle(board), render_solution(board, paths))


def build(board, k, seed):
    import random
    rng = random.Random(seed)
    b = make_puzzle(board, k, rng)
    if b is None:
        return None, None, 0
    paths, nodes = solve(b)
    if paths is None:
        return None, None, nodes
    verify(b, paths)
    return b, paths, nodes
