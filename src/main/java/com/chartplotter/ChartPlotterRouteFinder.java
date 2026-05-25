package com.chartplotter;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
final class ChartPlotterRouteFinder {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int MAX = 9000000;
	private static final int DENSE_MAX = 1 << 18;
	private static final int EDGE_MAX = 1 << 24;
	private static final int LAZY_MAX = 1 << 27;
	private static final int SPARSE_MOVE_MAX = 1 << 22;
	private static final int SPARSE_LINK = 128;
	private static final int SPARSE_CONNECT = 192;
	private static final int SPARSE_BAND_DEFAULT = 80;
	private static final int SPARSE_LOCAL_LINK = 256;
	private static final int SPARSE_LOCAL_TRIES = 8;
	private static final int STEP = 32;
	private static final int MODE_BASE = 0;
	private static final int MODE_TILE = 1;
	private static final int MC_OFF = 0;
	private static final int MC_DENSE = 1;
	private static final int MC_SPARSE = 2;
	private static final int REACH_CHECK = 4095;
	private static final int[] DX = {0, 4, 7, 11, 10, 9, 7, 4, 0, -5, -7, -9, -10, -11, -7, -5};
	private static final int[] DY = {10, 9, 7, 5, 0, -4, -7, -9, -10, -11, -7, -4, 0, 5, 7, 11};
	private static final int[] COST = {100, 98, 98, 120, 100, 98, 98, 98, 100, 120, 98, 98, 100, 120, 98, 120};
	private static final int[] OR = {1024, 1152, 1280, 1408, 1536, 1664, 1792, 1920, 0, 128, 256, 384, 512, 640, 768, 896};
	private static final int[][] FAN = fanDirs();
	private static final int[][] HX = hitOffsets(true);
	private static final int[][] HY = hitOffsets(false);
	private static final int MOVE = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
	private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);
	private ChartPlotterRouteFinder() {}
	static ChartPlotterRoute find(ChartPlotterCollisionData data, WorldEntityConfig wc, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirs, boolean adaptive, int targetRadius, BooleanSupplier cancel) {
		return find(data, wc, start, sx, sy, tx, ty, turnBias, reverse, fast, dirs, adaptive, targetRadius, null, cancel);
	}
	static ChartPlotterRoute find(ChartPlotterCollisionData data, WorldEntityConfig wc, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirs, boolean adaptive, int targetRadius, ChartPlotterSparseNodes.Snapshot sparse, BooleanSupplier cancel) {
		return find(data, wc, start, sx, sy, tx, ty, turnBias, reverse, fast, dirs, adaptive, targetRadius, sparse, true, cancel);
	}
	static ChartPlotterRoute find(ChartPlotterCollisionData data, WorldEntityConfig wc, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirs, boolean adaptive, int targetRadius, ChartPlotterSparseNodes.Snapshot sparse, boolean debugSparse, BooleanSupplier cancel) {
		return find(data, wc, start, sx, sy, tx, ty, turnBias, reverse, fast, dirs, adaptive, targetRadius, sparse, debugSparse, SPARSE_BAND_DEFAULT, cancel);
	}
	static ChartPlotterRoute find(ChartPlotterCollisionData data, WorldEntityConfig wc, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirs, boolean adaptive, int targetRadius, ChartPlotterSparseNodes.Snapshot sparse, boolean debugSparse, int sparseBand, BooleanSupplier cancel) {
		SparseDebug debug = sparse == null || !debugSparse ? null : new SparseDebug(sx, sy, tx, ty, sparse.n, dirs, adaptive, targetRadius);
		sparseBand = Math.max(20, Math.min(200, sparseBand));
		Footprint fp = wc == null ? null : new Footprint(wc);
		int dirStep = dirs == 8 ? 2 : 1;
		boolean eight = adaptiveEight(fp, sx, sy, tx, ty, dirStep, adaptive);
		int step = eight ? 2 : dirStep;
		SparsePath sp = sparsePath(data, sparse, sx, sy, tx, ty, targetRadius, sparseBand, debug, cancel);
		boolean sparseFan = sp != null && adaptive && dirStep == 1;
		int sparseStep = sparseFan ? 1 : step;
		int sparseCandidates = sparseCandidates(dirs, fast, adaptive, fp);
		if (sp != null && sp.pending) {
			ChartPlotterRoute r = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
			if (debug != null) debug.result("pending", r);
			return r;
		}
		if (sp != null) {
			if (debug != null) debug.effort(sparseCandidates, sparseStep, sparseFan);
			ChartPlotterRoute r = sparseRoute(data, sparse, fp, start, sx, sy, tx, ty, turnBias, reverse, fast, sparseStep, sparseFan, fp == null ? MODE_BASE : MODE_TILE, targetRadius, sparseBand, sp, sparseCandidates, debug, cancel);
			if (r.status == ChartPlotterRoute.PENDING || r.status == ChartPlotterRoute.OK) {
				if (debug != null) debug.result("sparse-selected", r);
				return r;
			}
			if (debug != null) debug.result("sparse-corridors-failed", r);
		}
		if (sparse != null) {
			ChartPlotterRoute r = ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, fast);
			if (debug != null) debug.result("sparse-missing", r);
			return r;
		}
		return find(data, fp, start, sx, sy, tx, ty, turnBias, reverse, fast, step, false, fp == null ? MODE_BASE : MODE_TILE, targetRadius, null, cancel);
	}
	private static ChartPlotterRoute find(ChartPlotterCollisionData raw, Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, int mode, int targetRadius, Corridor corridor, BooleanSupplier cancel) {
		return find(raw, fp, start, sx, sy, tx, ty, turnBias, reverse, fast, dirStep, false, mode, targetRadius, corridor, cancel);
	}
	private static ChartPlotterRoute find(ChartPlotterCollisionData raw, Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, boolean dirFan, int mode, int targetRadius, Corridor corridor, BooleanSupplier cancel) {
		turnBias = Math.max(0, Math.min(10, turnBias));
		int radius = radius(fp);
		Bounds full = bounds(sx, sy, tx, ty, maxMargin(sx, sy, tx, ty, fp));
		Grid base = new Grid(raw);
		Grid probe = fp == null ? base : Grid.lazy(raw, fp, radius, mode);
		int sf = probe.flag(sx, sy);
		int tf = targetFlag(probe, tx, ty, targetRadius, corridor != null);
		if (sf == ChartPlotterCollisionCache.UNKNOWN || tf == ChartPlotterCollisionCache.UNKNOWN) return ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias, fast);
		if (blocker(sf) || blocker(tf)) return ChartPlotterRoute.blocked(sx, sy, tx, ty, turnBias, fast);
		if (corridor != null) {
			Grid data = fp == null ? base : Grid.lazy(raw, fp, radius, mode);
			return search(data, start, sx, sy, tx, ty, turnBias, corridor.b, corridor.cap, reverse, fast, dirStep, dirFan, targetRadius, corridor, cancel);
		}
		int max = maxMargin(sx, sy, tx, ty, null);
		int fm = tightMargin(sx, sy, tx, ty);
		for (int m = fm;; m = Math.min(max, m * 2)) {
			Bounds b = bounds(sx, sy, tx, ty, m);
			int cap = cap(sx, sy, tx, ty, m);
			Grid data = fp == null ? base : Grid.inflated(raw, fp, radius, b, mode);
			ChartPlotterRoute r = search(data, start, sx, sy, tx, ty, turnBias, b, cap, reverse, fast, dirStep, false, targetRadius, null, cancel);
			if (r.status == ChartPlotterRoute.PENDING) return r;
			if (r.status == ChartPlotterRoute.OK) return r;
			if (m == max) {
				int c = connected(base, WORK.get().reach, sx, sy, tx, ty, targetRadius, full, cancel);
				if (c < 0) return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
				if (c == 0) return ChartPlotterRoute.blocked(sx, sy, tx, ty, turnBias, fast);
				return r;
			}
		}
	}
	private static ChartPlotterRoute search(Grid data, int start, int sx, int sy, int tx, int ty, int turnBias, Bounds b, int cap, boolean reverse, boolean fast, int dirStep, boolean dirFan, int targetRadius, Corridor corridor, BooleanSupplier cancel) {
		Work w = WORK.get();
		w.clear();
		Nodes nodes = w.a;
		Heap q = w.aq;
		LongIntMap best = w.ag;
		MoveCache moves = w.moves;
		moves.reset(b, dirStep);
		DenseBest dense = w.best;
		dense.reset(b, dirStep);
		boolean db = dense.on;
		if (!db) best.clear();
		addStarts(q, dense, best, nodes, sx, sy, tx, ty, turnBias, start, fast, dirStep, db);
		boolean capped = false;
		int seen = 0;
		int steps = 0;
		int movesT = 0;
		int movesP = 0;
		int minX = b.minX;
		int minY = b.minY;
		int maxX = b.maxX;
		int maxY = b.maxY;
		int turn = turn(turnBias);
		while (q.hasNext()) {
			if (cancel.getAsBoolean()) {
				if (corridor != null) corridor.search("pending", seen, nodes.n, steps, movesT, movesP, 0);
				return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
			}
			int a = q.poll();
			int ax = nodes.x[a];
			int ay = nodes.y[a];
			int ad = nodes.dir[a];
			int ag = nodes.g[a];
			int dist = nodes.d[a];
			if (nodes.prev[a] >= 0) {
				int bg = db ? dense.get(ax, ay, ad) : best.get(state(ax, ay, ad));
				if (bg == LongIntMap.MISS || ag != bg) continue;
			}
			if (near(ax, ay, tx, ty, targetRadius)) {
				long rs = System.nanoTime();
				ChartPlotterRoute r = route(data, start, nodes, a, sx, sy, tx, ty, turnBias, reverse, fast, dirStep);
				if (corridor != null) corridor.search("ok", seen, nodes.n, steps, movesT, movesP, (System.nanoTime() - rs) / 1000000);
				return r;
			}
			if (++seen > MAX) {
				capped = true;
				break;
			}
			int[] fd = dirFan ? FAN[ad] : null;
			int fn = dirFan ? fd.length : DX.length / dirStep;
			for (int di = 0; di < fn; di++) {
				int i = dirFan ? fd[di] : di * dirStep;
				steps++;
				int nx = ax + DX[i];
				int ny = ay + DY[i];
				if (nx < minX || ny < minY || nx > maxX || ny > maxY) continue;
				if (corridor != null && !corridor.contains(nx, ny)) continue;
				int step = COST[i];
				int nd = dist + step;
				if (nd > cap) continue;
				int ng = ag + step + (ad != i ? turn : 0);
				long key = 0;
				if (db) {
					int old = dense.get(nx, ny, i);
					if (old != LongIntMap.MISS && old <= ng) continue;
				} else {
					key = state(nx, ny, i);
					int old = best.get(key);
					if (old != LongIntMap.MISS && old <= ng) continue;
				}
				int p = LongIntMap.MISS;
				if (moves.on) {
					p = moves.get(ax, ay, ad, i);
				}
				if (p == LongIntMap.MISS) {
					movesT++;
					p = move(data, ax, ay, nx, ny, i, reverse);
					if (moves.on) moves.put(ax, ay, ad, i, p);
				}
				if (p != 1) continue;
				movesP++;
				if (db) dense.put(nx, ny, i, ng);
				else best.put(key, ng);
				int hh = h(nx, ny, tx, ty, i, turnBias);
				q.add(nodes.add(nx, ny, i, ng, nd, ng + wh(hh, fast), a));
			}
		}
		if (corridor != null) corridor.search(capped ? "complex" : "none", seen, nodes.n, steps, movesT, movesP, 0);
		return capped ? ChartPlotterRoute.complex(sx, sy, tx, ty, turnBias, fast) : ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, fast);
	}
	private static ChartPlotterRoute sparseRoute(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, boolean dirFan, int mode, int targetRadius, int sparseBand, SparsePath first, int maxCandidates, SparseDebug debug, BooleanSupplier cancel) {
		SparsePath[] paths = sparseAlternates(data, nodes, sx, sy, tx, ty, targetRadius, sparseBand, first, maxCandidates, debug, cancel);
		int n = 0;
		for (int i = 0; i < paths.length; i++) {
			if (paths[i].pending) return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
			SparsePath p = simplifySparse(data, paths[i], debug);
			if (sameSparse(paths, n, p)) continue;
			paths[n++] = p;
		}
		paths = Arrays.copyOf(paths, n);
		sortSparse(paths, turnBias);
		for (int i = 0; i < paths.length; i++) {
			if (debug != null) debug.sparseCandidate(i + 1, paths.length, paths[i], sparseScore(paths[i], turnBias));
		}
		return sparseRouteFast(data, fp, start, sx, sy, tx, ty, turnBias, reverse, fast, dirStep, dirFan, mode, targetRadius, sparseBand, paths, debug, cancel);
	}
	private static ChartPlotterRoute sparseRouteFast(ChartPlotterCollisionData data, Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, boolean dirFan, int mode, int targetRadius, int sparseBand, SparsePath[] paths, SparseDebug debug, BooleanSupplier cancel) {
		for (int i = 0; i < paths.length; i++) {
			SparsePath p = paths[i];
			Corridor c = corridor(p, radius(fp), targetRadius, sparseBand, "path=best-" + (i + 1) + "/" + paths.length, debug);
			if (debug != null) debug.corridor(c);
			ChartPlotterRoute r = find(data, fp, start, sx, sy, tx, ty, turnBias, reverse, fast, dirStep, dirFan, mode, targetRadius, c, cancel).sparse(p.x, p.y, p.n, c.band);
			if (r.status == ChartPlotterRoute.PENDING || r.status == ChartPlotterRoute.OK) {
				if (debug != null) debug.candidate(i + 1, paths.length, r, r.status == ChartPlotterRoute.OK ? routeScore(r, turnBias) : Integer.MAX_VALUE);
				return r;
			}
			if (debug != null) debug.candidate(i + 1, paths.length, r, Integer.MAX_VALUE);
		}
		return ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, fast);
	}
	private static void sortSparse(SparsePath[] paths, int turnBias) {
		for (int i = 1; i < paths.length; i++) {
			SparsePath p = paths[i];
			int s = sparseScore(p, turnBias);
			int j = i - 1;
			while (j >= 0 && sparseScore(paths[j], turnBias) > s) {
				paths[j + 1] = paths[j];
				j--;
			}
			paths[j + 1] = p;
		}
	}
	private static int sparseCandidates(int dirs, boolean fast, boolean adaptive, Footprint fp) {
		if (dirs == 16 && !fast && !adaptive) return 10;
		if (dirs == 16 && !fast) return 8;
		if (dirs == 16) return 6;
		return fp == null ? 2 : 4;
	}
	private static SparsePath[] sparseAlternates(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty, int targetRadius, int sparseBand, SparsePath first, int maxCandidates, SparseDebug debug, BooleanSupplier cancel) {
		SparsePath[] paths = new SparsePath[maxCandidates];
		paths[0] = first;
		int n = 1;
		int edges = first.n - 1;
		for (int i = 1; i < maxCandidates && edges > 2; i++) {
			int edge = sparseBanEdge(edges, i);
			SparsePath p = sparsePath(data, nodes, sx, sy, tx, ty, targetRadius, sparseBand, first.id[edge - 1], first.id[edge], debug, cancel);
			if (p == null) continue;
			if (p.pending) {
				paths[n++] = p;
				break;
			}
			if (sameSparse(paths, n, p)) continue;
			paths[n++] = p;
		}
		return Arrays.copyOf(paths, n);
	}
	private static int sparseBanEdge(int edges, int i) {
		int slots = edges - 1;
		int level = 1;
		while (i > level) {
			i -= level;
			level <<= 1;
		}
		int edge = 1 + (int) ((long) slots * (i * 2 - 1) / (level << 1));
		return edge >= edges ? edges - 1 : edge;
	}
	private static boolean sameSparse(SparsePath[] paths, int n, SparsePath p) {
		for (int i = 0; i < n; i++) {
			if (sameSparse(paths[i], p)) return true;
		}
		return false;
	}
	private static boolean sameSparse(SparsePath a, SparsePath b) {
		if (a.n != b.n) return false;
		for (int i = 0; i < a.n; i++) {
			if (a.id[i] != b.id[i]) return false;
		}
		return true;
	}
	private static SparsePath sparsePath(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty, int targetRadius, int sparseBand, SparseDebug debug, BooleanSupplier cancel) {
		return sparsePath(data, nodes, sx, sy, tx, ty, targetRadius, sparseBand, -1, -1, debug, cancel);
	}
	private static SparsePath sparsePath(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty, int targetRadius, int sparseBand, int banA, int banB, SparseDebug debug, BooleanSupplier cancel) {
		if (debug != null) debug.reset();
		if (nodes == null) {
			if (debug != null) debug.graph("skip-no-snapshot", null);
			return null;
		}
		if (nodes.n == 0) {
			if (debug != null) debug.graph("skip-no-nodes", null);
			return null;
		}
		if (near(sx, sy, tx, ty, targetRadius)) {
			if (debug != null) debug.graph("skip-near-target", null);
			return null;
		}
		Connector startCon = connector(data, nodes, sx, sy, sparseBand, debug, true, cancel);
		Connector targetCon = connector(data, nodes, tx, ty, sparseBand, debug, false, cancel);
		if (startCon.pending || targetCon.pending) {
			if (debug != null) debug.graph("pending", null);
			return SparsePath.pending();
		}
		if (startCon.n == 0 || targetCon.n == 0) {
			if (debug != null) debug.graph(startCon.n == 0 ? "no-start-connector" : "no-target-connector", null);
			return null;
		}
		int n = nodes.n + 2;
		int[] g = new int[n];
		int[] prev = new int[n];
		boolean[] done = new boolean[n];
		SparseHeap q = new SparseHeap(n);
		Arrays.fill(g, LongIntMap.MISS);
		Arrays.fill(prev, -1);
		g[0] = 0;
		q.add(0, h(sx, sy, tx, ty));
		if (debug != null) debug.queue(q.n);
		int seen = 0;
		while (q.hasNext()) {
			if ((seen++ & REACH_CHECK) == 0 && cancel.getAsBoolean()) {
				if (debug != null) debug.graph("pending", null);
				return SparsePath.pending();
			}
			int a = q.poll();
			if (done[a]) continue;
			if (a == 1) {
				SparsePath p = SparsePath.of(nodes, prev, g[1], sx, sy, tx, ty);
				if (debug != null) debug.graph("found", p);
				return p;
			}
			done[a] = true;
			if (debug != null) debug.polls++;
			int ax = sparseX(nodes, a, sx, tx);
			int ay = sparseY(nodes, a, sy, ty);
			int ag = g[a];
			for (int b = 1; b < n; b++) {
				if (debug != null) debug.scans++;
				if (b == a || done[b]) continue;
				if (banned(a, b, banA, banB)) continue;
				int bx = sparseX(nodes, b, sx, tx);
				int by = sparseY(nodes, b, sy, ty);
				int edge = sparseEdge(data, a, b, ax, ay, bx, by, startCon, targetCon, debug);
				if (edge == LongIntMap.MISS) continue;
				if (debug != null) {
					debug.edges++;
					if (a == 0) debug.startEdges++;
					if (b == 1) debug.targetEdges++;
				}
				int ng = ag + edge;
				if (g[b] != LongIntMap.MISS && g[b] <= ng) continue;
				g[b] = ng;
				prev[b] = a;
				q.add(b, ng + h(bx, by, tx, ty));
				if (debug != null) {
					debug.relax++;
					debug.queue(q.n);
				}
			}
		}
		if (debug != null) debug.graph("not-found", null);
		return null;
	}
	private static boolean banned(int a, int b, int banA, int banB) {return banA >= 0 && (a == banA && b == banB || a == banB && b == banA);}
	private static SparsePath simplifySparse(ChartPlotterCollisionData data, SparsePath p, SparseDebug debug) {
		if (p.n < 3) return p;
		int[] x = new int[p.n];
		int[] y = new int[p.n];
		int[] id = new int[p.n];
		int n = 0;
		int i = 0;
		x[n] = p.x[0];
		y[n] = p.y[0];
		id[n++] = p.id[0];
		while (i < p.n - 1) {
			int best = i + 1;
			for (int j = p.n - 1; j > i + 1; j--) {
				if (clearRaw(data, p.x[i], p.y[i], p.x[j], p.y[j]) != 1) continue;
				best = j;
				break;
			}
			x[n] = p.x[best];
			y[n] = p.y[best];
			id[n++] = p.id[best];
			i = best;
		}
		if (debug != null && n != p.n) debug.simplify(p.n, n);
		if (n == p.n) return p;
		return new SparsePath(Arrays.copyOf(x, n), Arrays.copyOf(y, n), Arrays.copyOf(id, n), n, sparseCost(x, y, n), false);
	}
	private static int sparseCost(int[] x, int[] y, int n) {
		int c = 0;
		for (int i = 1; i < n; i++) c += h(x[i - 1], y[i - 1], x[i], y[i]);
		return c;
	}
	private static int sparseEdge(ChartPlotterCollisionData data, int a, int b, int ax, int ay, int bx, int by, Connector startCon, Connector targetCon, SparseDebug debug) {
		if (a == 0 && b > 1) return startCon.cost[b - 2];
		if (b == 1 && a > 1) return targetCon.cost[a - 2];
		int link = a < 2 || b < 2 ? SPARSE_CONNECT : SPARSE_LINK;
		if (dist(ax, ay, bx, by) > link) return LongIntMap.MISS;
		if (debug != null) debug.range++;
		int c = clearRaw(data, ax, ay, bx, by);
		if (debug != null) debug.tests++;
		if (c != 1) {
			if (debug != null) {
				if (c < 0) debug.unknown++;
				else debug.blocked++;
			}
			return LongIntMap.MISS;
		}
		return h(ax, ay, bx, by);
	}
	private static Connector connector(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int band, SparseDebug debug, boolean start, BooleanSupplier cancel) {
		Connector c = new Connector(nodes.n);
		int[] ci = new int[SPARSE_LOCAL_TRIES];
		int[] cd = new int[SPARSE_LOCAL_TRIES];
		Arrays.fill(ci, -1);
		Arrays.fill(cd, Integer.MAX_VALUE);
		for (int i = 0; i < nodes.n; i++) {
			int d = dist(sx, sy, nodes.x[i], nodes.y[i]);
			if (d <= SPARSE_LOCAL_LINK) insertClosest(ci, cd, i, d);
			if (d > SPARSE_CONNECT) continue;
			if (debug != null) debug.range++;
			int r = clearRaw(data, sx, sy, nodes.x[i], nodes.y[i]);
			if (debug != null) debug.tests++;
			if (r == 1) {
				c.add(i, h(sx, sy, nodes.x[i], nodes.y[i]));
				c.los++;
			} else if (debug != null) {
				if (r < 0) debug.unknown++;
				else debug.blocked++;
			}
		}
		if (c.los == 0) {
			for (int i = 0; i < ci.length; i++) {
				int p = ci[i];
				if (p < 0 || c.cost[p] != LongIntMap.MISS) continue;
				ChartPlotterRoute r = localConnect(data, sx, sy, nodes.x[p], nodes.y[p], band, cancel);
				if (r.status == ChartPlotterRoute.PENDING) {
					c.pending = true;
					break;
				}
				if (r.status != ChartPlotterRoute.OK) continue;
				c.add(p, h(sx, sy, nodes.x[p], nodes.y[p]) * 13 / 10 + 50);
				c.local++;
			}
		}
		if (debug != null) debug.connector(start ? "start" : "target", c);
		return c;
	}
	private static ChartPlotterRoute localConnect(ChartPlotterCollisionData data, int sx, int sy, int tx, int ty, int band, BooleanSupplier cancel) {
		int m = Math.max(32, band);
		Bounds b = bounds(sx, sy, tx, ty, m);
		return search(new Grid(data), -1, sx, sy, tx, ty, 0, b, cap(sx, sy, tx, ty, m), false, true, 2, false, 2, null, cancel);
	}
	private static void insertClosest(int[] ci, int[] cd, int i, int d) {
		for (int p = 0; p < ci.length; p++) {
			if (d >= cd[p]) continue;
			for (int q = ci.length - 1; q > p; q--) {
				ci[q] = ci[q - 1];
				cd[q] = cd[q - 1];
			}
			ci[p] = i;
			cd[p] = d;
			return;
		}
	}
	private static Corridor corridor(SparsePath p, int radius, int targetRadius, int sparseBand, String name, SparseDebug debug) {
		int band = sparseBand + radius + targetRadius;
		int minX = p.x[0];
		int minY = p.y[0];
		int maxX = minX;
		int maxY = minY;
		for (int i = 1; i < p.n; i++) {
			int x = p.x[i];
			int y = p.y[i];
			if (x < minX) minX = x;
			if (y < minY) minY = y;
			if (x > maxX) maxX = x;
			if (y > maxY) maxY = y;
		}
		int c = Math.max(cap(p.x[0], p.y[0], p.x[p.n - 1], p.y[p.n - 1], band), p.cost * 3 / 2 + band * 8 + 1200);
		return new Corridor(p.x, p.y, p.n, new Bounds(minX - band, minY - band, maxX + band, maxY + band), c, band, name, debug);
	}
	private static int sparseX(ChartPlotterSparseNodes.Snapshot nodes, int i, int sx, int tx) {return i == 0 ? sx : i == 1 ? tx : nodes.x[i - 2];}
	private static int sparseY(ChartPlotterSparseNodes.Snapshot nodes, int i, int sy, int ty) {return i == 0 ? sy : i == 1 ? ty : nodes.y[i - 2];}
	private static int clearRaw(ChartPlotterCollisionData data, int ax, int ay, int bx, int by) {
		int lax = center(ax);
		int lay = center(ay);
		int lbx = center(bx);
		int lby = center(by);
		int dx = lbx - lax;
		int dy = lby - lay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / STEP;
		if (steps < 1) steps = 1;
		int px = Integer.MIN_VALUE;
		int py = Integer.MIN_VALUE;
		boolean unknown = false;
		for (int i = 0; i <= steps; i++) {
			int x = Math.floorDiv(lax + dx * i / steps, TS);
			int y = Math.floorDiv(lay + dy * i / steps, TS);
			if (x == px && y == py) continue;
			px = x;
			py = y;
			int f = rawFlag(data, x, y);
			if (f == ChartPlotterCollisionCache.UNKNOWN) unknown = true;
			else if (f != ChartPlotterCollisionCache.OPEN) return 0;
		}
		return unknown ? -1 : 1;
	}
	private static int rawFlag(ChartPlotterCollisionData data, int x, int y) {
		ChartPlotterCollisionCache.Chunk c = data.chunk(x >> 3, y >> 3);
		return c == null ? ChartPlotterCollisionCache.UNKNOWN : c.flag((x & 7) + ((y & 7) << 3));
	}
	private static int connected(Grid data, Reach r, int sx, int sy, int tx, int ty, int targetRadius, Bounds b, BooleanSupplier cancel) {
		if (near(sx, sy, tx, ty, targetRadius)) return 1;
		r.clear();
		r.add(chunk(sx, sy));
		int i = 0;
		while (i < r.n) {
			if ((i & REACH_CHECK) == 0 && cancel.getAsBoolean()) return -1;
			long k = r.q[i++];
			int x = (int) (k >> 32);
			int y = (int) k;
			for (int d = 0; d < 4; d++) {
				int nx = x + (d == 1 ? 1 : d == 3 ? -1 : 0);
				int ny = y + (d == 0 ? 1 : d == 2 ? -1 : 0);
				if (nx < b.minX || ny < b.minY || nx > b.maxX || ny > b.maxY) continue;
				long nk = chunk(nx, ny);
				if (r.seen.get(nk) != LongIntMap.MISS) continue;
				int f = data.flag(nx, ny);
				if (f == ChartPlotterCollisionCache.UNKNOWN || blocker(f)) continue;
				if (near(nx, ny, tx, ty, targetRadius)) return 1;
				r.add(nk);
			}
		}
		return 0;
	}
	private static ChartPlotterRoute route(Grid data, int start, Nodes nodes, int end, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep) {
		int n = 0;
		for (int a = end; a >= 0; a = nodes.prev[a]) n++;
		int[] x = new int[n];
		int[] y = new int[n];
		int i = n;
		for (int a = end; a >= 0; a = nodes.prev[a]) {
			i--;
			x[i] = nodes.x[a];
			y[i] = nodes.y[a];
		}
		return smooth(data, start, sx, sy, tx, ty, x, y, n, turnBias, reverse, fast, dirStep);
	}
	private static ChartPlotterRoute smooth(Grid data, int start, int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, boolean reverse, boolean fast, int dirStep) {
		if (n < 3) return ChartPlotterRoute.ok(sx, sy, tx, ty, x, y, n, turnBias, fast);
		int[] ox = new int[n];
		int[] oy = new int[n];
		int on = 0;
		int i = 0;
		int o = start;
		Footprint fp = data.fp;
		Grid raw = fp == null || data.data == null ? data : new Grid(data.data);
		ox[on] = x[0];
		oy[on++] = y[0];
		while (i < n - 1) {
			int best = i + 1;
			int end = Math.min(n - 1, i + smoothLimit(turnBias));
			for (int j = end; j > i + 1; j--) {
				int d = directSmoothDir(x[i], y[i], x[j], y[j], dirStep);
				if (d < 0) continue;
				if (smoothClear(data, raw, fp, o, x[i], y[i], x[j], y[j], d, reverse)) {
					best = j;
					o = d;
					break;
				}
			}
			if (best == i + 1) {
				int dir = dir(x[best] - x[i], y[best] - y[i]);
				o = dir >= 0 ? orient(dir, dir) : orientTo(x[i], y[i], x[best], y[best]);
			}
			ox[on] = x[best];
			oy[on++] = y[best];
			i = best;
		}
		return ChartPlotterRoute.ok(sx, sy, tx, ty, ox, oy, on, turnBias, fast);
	}
	private static boolean smoothClear(Grid data, Grid raw, Footprint fp, int start, int sx, int sy, int tx, int ty, int d, boolean reverse) {
		if (!clear(data, sx, sy, tx, ty, d, reverse)) return false;
		return fp == null || projectState(raw, fp, start, sx, sy, tx, ty, d, reverse) == 1;
	}
	private static ChartPlotterRoute corners(int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, boolean fast) {
		if (n < 3) return ChartPlotterRoute.ok(sx, sy, tx, ty, x, y, n, turnBias, fast);
		int[] ox = new int[n];
		int[] oy = new int[n];
		int on = 0;
		ox[on] = x[0];
		oy[on++] = y[0];
		int pd = dir(x[1] - x[0], y[1] - y[0]);
		for (int i = 2; i < n; i++) {
			int d = dir(x[i] - x[i - 1], y[i] - y[i - 1]);
			if (d == pd) continue;
			ox[on] = x[i - 1];
			oy[on++] = y[i - 1];
			pd = d;
		}
		ox[on] = x[n - 1];
		oy[on++] = y[n - 1];
		return ChartPlotterRoute.ok(sx, sy, tx, ty, ox, oy, on, turnBias, fast);
	}
	private static int move(Grid data, int x, int y, int nx, int ny, int ndir, boolean reverse) {
		int p = clearPoint(data, x, y, nx, ny, ndir);
		if (p == 1 || !reverse || data.fp == null) return p;
		int r = clearPoint(data, x, y, nx, ny, revDir(ndir));
		return r == 1 ? 1 : p < 0 || r < 0 ? -1 : 0;
	}
	private static int pass(Grid data, int nx, int ny, int dir) {
		int f = dir < 0 ? data.flag(nx, ny) : data.flag(nx, ny, dir);
		if (f == ChartPlotterCollisionCache.UNKNOWN) return -1;
		return blocker(f) ? 0 : 1;
	}
	private static void addStarts(Heap q, DenseBest dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int start, boolean fast, int dirStep, boolean db) {
		if (start >= 0) {
			addStart(q, dense, best, nodes, sx, sy, tx, ty, turnBias, snapDir(start, 1), fast, dirStep, db);
			return;
		}
		for (int i = 0; i < DX.length; i++) addStart(q, dense, best, nodes, sx, sy, tx, ty, turnBias, i, fast, dirStep, db);
	}
	private static void addStart(Heap q, DenseBest dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int dir, boolean fast, int dirStep, boolean db) {
		int hh = h(sx, sy, tx, ty, dir, turnBias);
		int n = nodes.add(sx, sy, dir, 0, 0, wh(hh, fast), -1);
		q.add(n);
		if (dir % dirStep != 0) return;
		if (db) dense.put(sx, sy, dir, 0);
		else best.put(state(sx, sy, dir), 0);
	}
	private static int linePoint(Grid data, int sx, int sy, int tx, int ty, int dir) {
		int x = sx;
		int y = sy;
		while (x != tx || y != ty) {
			int nx = x + Integer.signum(tx - x);
			int ny = y + Integer.signum(ty - y);
			int p = pass(data, nx, ny, dir);
			if (p != 1) return p;
			if (nx != x && ny != y) {
				int px = pass(data, nx, y, dir);
				int py = pass(data, x, ny, dir);
				if (px != 1) return px;
				if (py != 1) return py;
			}
			x = nx;
			y = ny;
		}
		return 1;
	}
	private static int clearPoint(Grid data, int sx, int sy, int tx, int ty, int dir) {
		if (Math.max(Math.abs(tx - sx), Math.abs(ty - sy)) <= 1) return linePoint(data, sx, sy, tx, ty, dir);
		if (dir >= 0 && sx + DX[dir] == tx && sy + DY[dir] == ty) return shortPath(data, sx, sy, dir);
		return hitPath(data, center(sx), center(sy), center(tx), center(ty), dir);
	}
	private static int shortPath(Grid data, int sx, int sy, int dir) {
		int[] x = HX[dir];
		int[] y = HY[dir];
		for (int i = 0; i < x.length; i++) {
			int f = data.flag(sx + x[i], sy + y[i], dir);
			if (f == ChartPlotterCollisionCache.UNKNOWN) return -1;
			if (blocker(f)) return 0;
		}
		return 1;
	}
	private static boolean clear(Grid data, int sx, int sy, int tx, int ty, int d, boolean reverse) {
		int dir = orientIndex(d);
		if (clearPoint(data, sx, sy, tx, ty, dir) == 1) return true;
		return reverse && data.fp != null && clearPoint(data, sx, sy, tx, ty, revDir(dir)) == 1;
	}
	private static int projectState(Grid data, Footprint fp, int start, int sx, int sy, int tx, int ty, int d, boolean reverse) {
		if (fp == null) return clearPoint(data, sx, sy, tx, ty, orientIndex(d));
		int o = start >= 0 ? start : d;
		int p = projectPath(data, fp, center(sx), center(sy), o, center(tx), center(ty), d);
		if (p == 1 || !reverse) return p;
		int r = projectPath(data, fp, center(sx), center(sy), o, center(tx), center(ty), rev(d));
		return r == 1 ? 1 : p < 0 || r < 0 ? -1 : 0;
	}
	private static int projectPath(Grid data, Footprint fp, int ax, int ay, int ao, int bx, int by, int bo) {
		int dx = bx - ax;
		int dy = by - ay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / STEP;
		if (steps < 1) steps = 1;
		int px = ax;
		int py = ay;
		int po = ao;
		for (int i = 1; i <= steps; i++) {
			int qx = ax + dx * i / steps;
			int qy = ay + dy * i / steps;
			int b = clearBoundsState(data, fp, px, py, po, qx, qy, bo);
			if (b != 1) {
				int h = hitFootprint(data, fp, px, py, po, qx, qy, bo);
				if (h != 1) return h;
			}
			px = qx;
			py = qy;
			po = bo;
		}
		return 1;
	}
	private static int clearBoundsState(Grid data, Footprint fp, int ax, int ay, int ao, int bx, int by, int bo) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int i = 0; i < fp.n; i++) {
			if (!fp.corner[i]) continue;
			int x = fp.x[i];
			int y = fp.y[i];
			int px = rotateX(ax, ao, x, y);
			int py = rotateY(ay, ao, x, y);
			int qx = rotateX(bx, bo, x, y);
			int qy = rotateY(by, bo, x, y);
			if (px < minX) minX = px;
			if (qx < minX) minX = qx;
			if (py < minY) minY = py;
			if (qy < minY) minY = qy;
			if (px > maxX) maxX = px;
			if (qx > maxX) maxX = qx;
			if (py > maxY) maxY = py;
			if (qy > maxY) maxY = qy;
		}
		boolean unknown = false;
		int x0 = Math.floorDiv(minX, TS);
		int y0 = Math.floorDiv(minY, TS);
		int x1 = Math.floorDiv(maxX, TS);
		int y1 = Math.floorDiv(maxY, TS);
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				int f = data.baseFlag(x, y);
				if (f == ChartPlotterCollisionCache.UNKNOWN) unknown = true;
				else if (blocker(f)) return 0;
			}
		}
		return unknown ? -1 : 1;
	}
	private static int hitFootprint(Grid data, Footprint fp, int ax, int ay, int ao, int bx, int by, int bo) {
		int ai = orientIndex(ao);
		int bi = orientIndex(bo);
		boolean unknown = false;
		for (int i = 0; i < fp.n; i++) {
			int h = hitPath(data, fp.x(ax, ao, ai, i), fp.y(ay, ao, ai, i), fp.x(bx, bo, bi, i), fp.y(by, bo, bi, i));
			if (h == 0) return 0;
			if (h < 0) unknown = true;
		}
		return unknown ? -1 : 1;
	}
	private static int hitPath(Grid data, int ax, int ay, int bx, int by) {
		return hitPath(data, ax, ay, bx, by, -1);
	}
	private static int hitPath(Grid data, int ax, int ay, int bx, int by, int dir) {
		int dx = bx - ax;
		int dy = by - ay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / STEP;
		if (steps < 1) steps = 1;
		int px = Integer.MIN_VALUE;
		int py = Integer.MIN_VALUE;
		for (int i = 1; i <= steps; i++) {
			int lx = ax + dx * i / steps;
			int ly = ay + dy * i / steps;
			int x = Math.floorDiv(lx, TS);
			int y = Math.floorDiv(ly, TS);
			if (x == px && y == py) continue;
			px = x;
			py = y;
			int f = dir < 0 ? data.flag(x, y) : data.flag(x, y, dir);
			if (f == ChartPlotterCollisionCache.UNKNOWN) return -1;
			if (blocker(f)) return 0;
		}
		return 1;
	}
	private static boolean blocker(int f) {return (f & MOVE) != 0;}
	private static boolean near(int ax, int ay, int bx, int by, int r) {return Math.max(Math.abs(ax - bx), Math.abs(ay - by)) <= r;}
	private static int targetFlag(Grid data, int tx, int ty, int r, boolean relaxed) {
		int f = data.flag(tx, ty);
		if (!relaxed || f != ChartPlotterCollisionCache.UNKNOWN && !blocker(f)) return f;
		boolean unknown = f == ChartPlotterCollisionCache.UNKNOWN;
		for (int y = ty - r; y <= ty + r; y++) {
			for (int x = tx - r; x <= tx + r; x++) {
				if (x == tx && y == ty || !near(x, y, tx, ty, r)) continue;
				int v = data.flag(x, y);
				if (v == ChartPlotterCollisionCache.UNKNOWN) unknown = true;
				else if (!blocker(v)) return ChartPlotterCollisionCache.OPEN;
			}
		}
		return unknown ? ChartPlotterCollisionCache.UNKNOWN : ChartPlotterCollisionCache.BLOCKED;
	}
	private static int dist(int ax, int ay, int bx, int by) {return Math.max(Math.abs(ax - bx), Math.abs(ay - by));}
	private static int sparseScore(SparsePath p, int turnBias) {
		int l = p.cost;
		int t = sparseTurns(p);
		if (turnBias <= 0) return l * 10 + t * 50;
		if (turnBias >= 10) return l + t * 5000;
		return l * 2 + t * 1200;
	}
	private static int sparseTurns(SparsePath p) {
		int t = 0;
		int pd = -1;
		for (int i = 1; i < p.n; i++) {
			int d = snapLine(p.x[i] - p.x[i - 1], p.y[i] - p.y[i - 1], 1);
			if (pd >= 0 && d != pd) t++;
			pd = d;
		}
		return t;
	}
	private static int routeScore(ChartPlotterRoute r, int turnBias) {
		int l = routeLength(r);
		int t = routeTurns(r);
		if (turnBias <= 0) return l * 10 + t * 50;
		if (turnBias >= 10) return l + t * 5000;
		return l * 2 + t * 1200;
	}
	private static int routeLength(ChartPlotterRoute r) {
		int l = 0;
		for (int i = 1; i < r.n; i++) l += h(r.x[i - 1], r.y[i - 1], r.x[i], r.y[i]);
		return l;
	}
	private static int routeTurns(ChartPlotterRoute r) {
		int t = 0;
		int pd = -1;
		for (int i = 1; i < r.n; i++) {
			int d = dir(r.x[i] - r.x[i - 1], r.y[i] - r.y[i - 1]);
			if (d < 0) d = snapLine(r.x[i] - r.x[i - 1], r.y[i] - r.y[i - 1], 1);
			if (pd >= 0 && d != pd) t++;
			pd = d;
		}
		return t;
	}
	private static boolean nearSegment(int x, int y, int ax, int ay, int bx, int by, int band) {
		long dx = bx - ax;
		long dy = by - ay;
		long len = dx * dx + dy * dy;
		if (len == 0) return dist(x, y, ax, ay) <= band;
		long px = x - ax;
		long py = y - ay;
		long t = px * dx + py * dy;
		if (t <= 0) return dist(x, y, ax, ay) <= band;
		if (t >= len) return dist(x, y, bx, by) <= band;
		long cross = px * dy - py * dx;
		return cross * cross <= (long) band * band * len;
	}
	private static int h(int x, int y, int tx, int ty) {
		int dx = Math.abs(tx - x);
		int dy = Math.abs(ty - y);
		int a = Math.max(dx, dy);
		int b = Math.min(dx, dy);
		return 2 * b <= a ? 10 * a + 2 * b : 8 * a + 6 * b;
	}
	private static int h(int x, int y, int tx, int ty, int dir, int turnBias) {
		return h(x, y, tx, ty) + turn(turnBias) * minTurns(dir, tx - x, ty - y);
	}
	private static int wh(int h, boolean fast) {return fast ? h * 5 / 2 : h;}
	private static int smoothLimit(int turnBias) {return turnBias <= 0 ? 16 : turnBias >= 10 ? 256 : 96;}
	private static boolean fanDir(int from, int to) {
		int d = to - from & 15;
		if (d > 8) d -= 16;
		int a = Math.abs(d);
		return a <= 4 || a == 6 || a == 8;
	}
	private static int[][] fanDirs() {
		int[][] r = new int[DX.length][];
		for (int from = 0; from < DX.length; from++) {
			int[] d = new int[DX.length];
			int n = 0;
			for (int to = 0; to < DX.length; to++) {
				if (fanDir(from, to)) d[n++] = to;
			}
			r[from] = Arrays.copyOf(d, n);
		}
		return r;
	}
	private static int directSmoothDir(int sx, int sy, int tx, int ty, int dirStep) {
		int dir = dir(tx - sx, ty - sy);
		if (dir < 0) return -1;
		return orient(snapDir(OR[dir], dirStep), dir);
	}
	private static int snapLine(int dx, int dy, int dirStep) {
		if (dx == 0 && dy == 0) return 0;
		int best = 0;
		long bv = Long.MIN_VALUE;
		for (int i = 0; i < DX.length; i += dirStep) {
			long v = (long) dx * DX[i] + (long) dy * DY[i];
			if (v <= bv) continue;
			bv = v;
			best = i;
		}
		return best;
	}
	private static int minTurns(int dir, int dx, int dy) {
		if (dx == 0 && dy == 0) return 0;
		if (aligned(dir, dx, dy)) return 0;
		return 1;
	}
	private static boolean aligned(int dir, int dx, int dy) {
		int ux = DX[dir];
		int uy = DY[dir];
		return (long) dx * uy == (long) dy * ux && dx * ux + dy * uy > 0;
	}
	private static int cap(int sx, int sy, int tx, int ty, int margin) {
		int h = h(sx, sy, tx, ty);
		int tight = h * 14 / 10 + 200;
		int slack = h + margin * 10 + 160;
		return Math.max(tight, slack);
	}
	private static int turn(int turnBias) {return turnBias <= 0 ? 0 : 20 + turnBias * 22;}
	private static int tightMargin(int sx, int sy, int tx, int ty) {
		int d = Math.max(Math.abs(tx - sx), Math.abs(ty - sy));
		return Math.min(maxMargin(sx, sy, tx, ty, null), Math.max(32, d / 8 + 24));
	}
	private static boolean adaptiveEight(Footprint fp, int sx, int sy, int tx, int ty, int dirStep, boolean adaptive) {
		if (!adaptive || fp == null || dirStep != 1) return false;
		int m = tightMargin(sx, sy, tx, ty);
		Bounds b = bounds(sx, sy, tx, ty, m);
		long cells = (long) (b.maxX - b.minX + 1) * (b.maxY - b.minY + 1);
		return cells * DX.length > EDGE_MAX;
	}
	private static int maxMargin(int sx, int sy, int tx, int ty, Footprint fp) {
		int d = Math.max(Math.abs(tx - sx), Math.abs(ty - sy));
		return Math.max(512, d + 64 + radius(fp));
	}
	private static Bounds bounds(int sx, int sy, int tx, int ty, int m) {
		return new Bounds(Math.min(sx, tx) - m, Math.min(sy, ty) - m, Math.max(sx, tx) + m, Math.max(sy, ty) + m);
	}
	private static int snapDir(int angle, int dirStep) {
		int best = 0;
		int bd = Integer.MAX_VALUE;
		for (int i = 0; i < DX.length; i += dirStep) {
			int d = Math.abs(ChartPlotterPlugin.norm(angle - OR[i]));
			d = Math.min(d, 2048 - d);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}
	private static int dir(int dx, int dy) {
		if (dx == 0 && dy == 0) return -1;
		for (int i = 0; i < DX.length; i++) {
			if (aligned(i, dx, dy)) return i;
		}
		return -1;
	}
	private static int orientTo(int sx, int sy, int tx, int ty) {
		double d = Math.toDegrees(Math.atan2(ty - sy, tx - sx));
		return ChartPlotterPlugin.norm((int) Math.round((270 - d) / 360 * 2048));
	}
	private static int orient(int dir, int fallback) {
		if (dir < 0 || dir >= DX.length) dir = fallback;
		return OR[dir];
	}
	private static int rev(int o) {return ChartPlotterPlugin.norm(o + 1024);}
	private static int revDir(int d) {return d < 0 ? d : d + 8 & 15;}
	private static int center(int v) {return v * TS + TS / 2;}
	private static int radius(Footprint fp) {
		if (fp == null) return 0;
		int r = Math.max(Math.max(Math.abs(fp.minX), Math.abs(fp.maxX)), Math.max(Math.abs(fp.minY), Math.abs(fp.maxY)));
		return Math.max(1, (r + TS - 1) / TS);
	}
	private static int rotateX(int cx, int o, int x, int y) {return cx + (int) (((long) Perspective.COSINE[o] * x + (long) Perspective.SINE[o] * y) >> 16);}
	private static int rotateY(int cy, int o, int x, int y) {return cy + (int) (((long) Perspective.COSINE[o] * y - (long) Perspective.SINE[o] * x) >> 16);}
	private static int next(int v, int max) {return Math.min(v + STEP, max);}
	private static boolean edge(int x, int y, int minX, int maxX, int minY, int maxY) {return x == minX || x == maxX || y == minY || y == maxY;}
	private static int span(int min, int max) {return (max - min + STEP - 1) / STEP + 1;}
	private static int[][] hitOffsets(boolean xAxis) {
		int[][] r = new int[DX.length][];
		for (int d = 0; d < DX.length; d++) {
			int mx = Math.max(Math.abs(DX[d]), Math.abs(DY[d]));
			if (mx <= 1) {
				r[d] = new int[0];
				continue;
			}
			int steps = mx * TS / STEP;
			int[] x = new int[steps];
			int[] y = new int[steps];
			int n = 0;
			int px = Integer.MIN_VALUE;
			int py = Integer.MIN_VALUE;
			for (int i = 1; i <= steps; i++) {
				int ox = Math.floorDiv(TS / 2 + DX[d] * TS * i / steps, TS);
				int oy = Math.floorDiv(TS / 2 + DY[d] * TS * i / steps, TS);
				if (ox == px && oy == py) continue;
				px = ox;
				py = oy;
				x[n] = ox;
				y[n++] = oy;
			}
			r[d] = Arrays.copyOf(xAxis ? x : y, n);
		}
		return r;
	}
	private static int orientIndex(int o) {
		if (o >= 0 && o < 2048 && (o & 127) == 0) return (o >> 7) + 8 & 15;
		return -1;
	}
	private static long state(int x, int y, int d) {return ((long) x & 0xfffffL) << 44 | ((long) y & 0xfffffL) << 4 | d;}
	private static long chunk(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	private static final class Grid {
		final ChartPlotterCollisionData data;
		final byte[] dense;
		final byte[] dirs;
		final byte[] cached;
		final byte[] cachedDirs;
		final int minX;
		final int minY;
		final int width;
		final int height;
		final int stride;
		final int radius;
		final LongIntMap cache;
		final LongIntMap dirCache;
		final Footprint fp;
		final int mode;
		int cx;
		int cy;
		ChartPlotterCollisionCache.Chunk c;
		boolean have;
		private Grid(ChartPlotterCollisionData data) {
			this(data, null, null, null, null, 0, 0, 0, 0, 0, null, null, null, MODE_BASE);
		}
		private Grid(ChartPlotterCollisionData data, byte[] dense, byte[] dirs, byte[] cached, byte[] cachedDirs, int minX, int minY, int width, int height, int radius, LongIntMap cache, LongIntMap dirCache, Footprint fp, int mode) {
			this.data = data;
			this.dense = dense;
			this.dirs = dirs;
			this.cached = cached;
			this.cachedDirs = cachedDirs;
			this.minX = minX;
			this.minY = minY;
			this.width = width;
			this.height = height;
			this.stride = width * height;
			this.radius = radius;
			this.cache = cache;
			this.dirCache = dirCache;
			this.fp = fp;
			this.mode = mode;
		}
		static Grid inflated(ChartPlotterCollisionData data, Footprint fp, int radius, Bounds b, int mode) {
			int width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long area = (long) width * height;
			if (area <= DENSE_MAX) return dense(data, fp, radius, b, width, height, mode);
			long dirs = area * OR.length;
			byte[] cached = area <= LAZY_MAX ? new byte[(int) area] : null;
			byte[] cachedDirs = dirs <= LAZY_MAX ? new byte[(int) dirs] : null;
			return new Grid(data, null, null, cached, cachedDirs, b.minX, b.minY, width, height, radius, new LongIntMap(1 << 15), new LongIntMap(1 << 15), fp, mode);
		}
		static Grid lazy(ChartPlotterCollisionData data, Footprint fp, int radius, int mode) {
			return new Grid(data, null, null, null, null, 0, 0, 0, 0, radius, new LongIntMap(16), new LongIntMap(16), fp, mode);
		}
		private static Grid dense(ChartPlotterCollisionData data, Footprint fp, int radius, Bounds b, int width, int height, int mode) {
			int stride = width * height;
			byte[] dense = new byte[stride];
			byte[] dirs = new byte[stride * OR.length];
			Grid src = new Grid(data, null, null, null, null, 0, 0, 0, 0, radius, null, null, fp, mode);
			for (int y = 0; y < height; y++) {
				int row = y * width;
				for (int x = 0; x < width; x++) {
					int idx = row + x;
					boolean unknown = false;
					boolean open = false;
					for (int dir = 0; dir < OR.length; dir++) {
						byte v = src.stand(b.minX + x, b.minY + y, dir);
						dirs[idx + dir * stride] = v;
						if (v == 0) open = true;
						else if (v == 1) unknown = true;
					}
					dense[idx] = open ? 0 : unknown ? (byte) 1 : (byte) 2;
				}
			}
			return new Grid(data, dense, dirs, null, null, b.minX, b.minY, width, height, radius, null, null, fp, mode);
		}
		int flag(int x, int y) {
			if (dense != null) {
				int dx = x - minX;
				int dy = y - minY;
				return dx < 0 || dy < 0 || dx >= width || dy >= height ? ChartPlotterCollisionCache.UNKNOWN : flag(dense[dx + dy * width]);
			}
			if (radius != 0) {
				if (cached != null) {
					int dx = x - minX;
					int dy = y - minY;
					if (dx >= 0 && dy >= 0 && dx < width && dy < height) {
						int i = dx + dy * width;
						int v = cached[i];
						if (v != 0) return flag((byte) (v - 1));
						byte f = inflated(x, y);
						cached[i] = (byte) (f + 1);
						return flag(f);
					}
				}
				long key = chunk(x, y);
				int v = cache.get(key);
				if (v != LongIntMap.MISS) return flag((byte) v);
				byte f = inflated(x, y);
				cache.put(key, f);
				return flag(f);
			}
			return baseFlag(x, y);
		}
		int flag(int x, int y, int dir) {
			if (dir < 0 || dir >= OR.length) return flag(x, y);
			if (dirs != null) {
				int dx = x - minX;
				int dy = y - minY;
				return dx < 0 || dy < 0 || dx >= width || dy >= height ? ChartPlotterCollisionCache.UNKNOWN : flag(dirs[dx + dy * width + dir * stride]);
			}
			if (fp != null) {
				if (cachedDirs != null) {
					int dx = x - minX;
					int dy = y - minY;
					if (dx >= 0 && dy >= 0 && dx < width && dy < height) {
						int i = dx + dy * width + dir * stride;
						int v = cachedDirs[i];
						if (v != 0) return flag((byte) (v - 1));
						byte f = stand(x, y, dir);
						cachedDirs[i] = (byte) (f + 1);
						return flag(f);
					}
				}
				long key = state(x, y, dir);
				int v = dirCache.get(key);
				if (v != LongIntMap.MISS) return flag((byte) v);
				byte f = stand(x, y, dir);
				dirCache.put(key, f);
				return flag(f);
			}
			return flag(x, y);
		}
		private byte inflated(int x, int y) {
			boolean unknown = false;
			boolean open = false;
			for (int dir = 0; dir < OR.length; dir++) {
				byte v = stand(x, y, dir);
				if (v == 0) open = true;
				else if (v == 1) unknown = true;
			}
			return open ? 0 : unknown ? (byte) 1 : (byte) 2;
		}
		private byte stand(int x, int y, int dir) {
			if (mode == MODE_TILE) return standTiles(x, y, dir);
			int c = baseFlag(x, y);
			if (c == ChartPlotterCollisionCache.UNKNOWN) return 1;
			if (blocker(c)) return 2;
			int cx = center(x);
			int cy = center(y);
			boolean unknown = false;
			for (int i = 0; i < fp.n; i++) {
				int f = baseFlag(Math.floorDiv(cx + fp.rx[dir][i], TS), Math.floorDiv(cy + fp.ry[dir][i], TS));
				if (f == ChartPlotterCollisionCache.UNKNOWN) unknown = true;
				else if (blocker(f)) return 2;
			}
			return unknown ? (byte) 1 : 0;
		}
		private byte standTiles(int x, int y, int dir) {
			int c = baseFlag(x, y);
			if (c == ChartPlotterCollisionCache.UNKNOWN) return 1;
			if (blocker(c)) return 2;
			int[] xs = fp.tx[dir];
			int[] ys = fp.ty[dir];
			boolean unknown = false;
			for (int i = 0; i < fp.tn[dir]; i++) {
				int f = baseFlag(x + xs[i], y + ys[i]);
				if (f == ChartPlotterCollisionCache.UNKNOWN) unknown = true;
				else if (blocker(f)) return 2;
			}
			return unknown ? (byte) 1 : 0;
		}
		private int baseFlag(int x, int y) {
			int nx = x >> 3;
			int ny = y >> 3;
			if (!have || nx != cx || ny != cy) {
				cx = nx;
				cy = ny;
				c = data.chunk(nx, ny);
				have = true;
			}
			return c == null ? ChartPlotterCollisionCache.UNKNOWN : c.flag((x & 7) + ((y & 7) << 3));
		}
		private static int flag(byte v) {return v == 0 ? ChartPlotterCollisionCache.OPEN : v == 1 ? ChartPlotterCollisionCache.UNKNOWN : ChartPlotterCollisionCache.BLOCKED;}
	}
	private static final class LongIntMap {
		static final int MISS = Integer.MIN_VALUE;
		long[] k;
		int[] v;
		byte[] u;
		int n;
		int mask;
		private LongIntMap(int size) {
			init(size);
		}
		int get(long key) {
			int i = hash(key) & mask;
			while (u[i] != 0) {
				if (k[i] == key) return v[i];
				i = i + 1 & mask;
			}
			return MISS;
		}
		void put(long key, int val) {
			if (n * 2 >= k.length) grow();
			int i = hash(key) & mask;
			while (u[i] != 0) {
				if (k[i] == key) {
					v[i] = val;
					return;
				}
				i = i + 1 & mask;
			}
			u[i] = 1;
			k[i] = key;
			v[i] = val;
			n++;
		}
		void clear() {
			Arrays.fill(u, (byte) 0);
			n = 0;
		}
		private void init(int size) {
			int c = 1;
			while (c < size) c <<= 1;
			k = new long[c];
			v = new int[c];
			u = new byte[c];
			mask = c - 1;
			n = 0;
		}
		private void grow() {
			long[] ok = k;
			int[] ov = v;
			byte[] ou = u;
			init(k.length << 1);
			for (int i = 0; i < ok.length; i++) {
				if (ou[i] != 0) put(ok[i], ov[i]);
			}
		}
		private static int hash(long x) {
			x += 0x9e3779b97f4a7c15L;
			x = (x ^ x >>> 30) * -4658895280553007687L;
			x = (x ^ x >>> 27) * -7723592293110705685L;
			return (int) (x ^ x >>> 31);
		}
	}
	private static final class SparseDebug {
		final long start = System.nanoTime();
		int polls;
		int scans;
		int range;
		int tests;
		int edges;
		int blocked;
		int unknown;
		int relax;
		int startEdges;
		int targetEdges;
		int maxQueue;
		private SparseDebug(int sx, int sy, int tx, int ty, int nodes, int dirs, boolean adaptive, int targetRadius) {
			System.out.println("chart-plotter sparse start from=" + sx + "," + sy + " to=" + tx + "," + ty + " nodes=" + nodes + " dirs=" + dirs + " adaptive=" + adaptive + " radius=" + targetRadius);
		}
		void reset() {
			polls = 0;
			scans = 0;
			range = 0;
			tests = 0;
			edges = 0;
			blocked = 0;
			unknown = 0;
			relax = 0;
			startEdges = 0;
			targetEdges = 0;
			maxQueue = 0;
		}
		void queue(int n) {
			if (n > maxQueue) maxQueue = n;
		}
		void graph(String result, SparsePath p) {
			int pn = p == null ? 0 : p.n;
			int pc = p == null ? 0 : p.cost;
			System.out.println("chart-plotter sparse graph result=" + result + " polls=" + polls + " scans=" + scans + " range=" + range + " tests=" + tests + " edges=" + edges + " blocked=" + blocked + " unknown=" + unknown + " relax=" + relax + " startEdges=" + startEdges + " targetEdges=" + targetEdges + " queueMax=" + maxQueue + " pathNodes=" + pn + " pathCost=" + pc + " ms=" + ms());
		}
		void simplify(int from, int to) {
			System.out.println("chart-plotter sparse simplify from=" + from + " to=" + to + " ms=" + ms());
		}
		void connector(String name, Connector c) {
			System.out.println("chart-plotter sparse connector " + name + " edges=" + c.n + " los=" + c.los + " local=" + c.local + " pending=" + c.pending + " ms=" + ms());
		}
		void effort(int candidates, int step, boolean fan) {
			System.out.println("chart-plotter sparse effort candidates=" + candidates + " searchDirs=" + (fan ? 12 : 16 / step) + " fan=" + fan + " ms=" + ms());
		}
		void corridor(Corridor c) {
			System.out.println("chart-plotter sparse corridor " + c.name + " band=" + c.band + " cap=" + c.cap + " bounds=" + c.b.minX + "," + c.b.minY + "-" + c.b.maxX + "," + c.b.maxY + " area=" + c.mask.length + " cells=" + c.cells + " ms=" + ms());
		}
		void search(String name, String result, int seen, int made, int steps, int moves, int pass, long routeMs, int hit, int miss) {
			System.out.println("chart-plotter sparse search " + name + " result=" + result + " seen=" + seen + " made=" + made + " steps=" + steps + " moves=" + moves + " pass=" + pass + " routeMs=" + routeMs + " corridorHit=" + hit + " corridorMiss=" + miss + " ms=" + ms());
		}
		void sparseCandidate(int i, int total, SparsePath p, int score) {
			System.out.println("chart-plotter sparse path candidate=" + i + "/" + total + " nodes=" + p.n + " length=" + p.cost + " turns=" + sparseTurns(p) + " score=" + score + " ms=" + ms());
		}
		void candidate(int i, int total, ChartPlotterRoute r, int score) {
			System.out.println("chart-plotter sparse candidate=" + i + "/" + total + " route=" + status(r.status) + " points=" + r.n + " length=" + routeLength(r) + " turns=" + routeTurns(r) + " score=" + score + " ms=" + ms());
		}
		void result(String mode, ChartPlotterRoute r) {
			System.out.println("chart-plotter sparse result mode=" + mode + " route=" + status(r.status) + " points=" + r.n + " ms=" + ms());
		}
		private long ms() {return (System.nanoTime() - start) / 1000000;}
		private static String status(int s) {
			if (s == ChartPlotterRoute.PENDING) return "pending";
			if (s == ChartPlotterRoute.OK) return "ok";
			if (s == ChartPlotterRoute.UNCHARTED) return "uncharted";
			if (s == ChartPlotterRoute.NO_ROUTE) return "no-route";
			if (s == ChartPlotterRoute.COMPLEX) return "complex";
			if (s == ChartPlotterRoute.BLOCKED) return "blocked";
			return "unknown";
		}
	}
	private static final class Connector {
		final int[] cost;
		int n;
		int los;
		int local;
		boolean pending;
		private Connector(int n) {
			cost = new int[n];
			Arrays.fill(cost, LongIntMap.MISS);
		}
		void add(int i, int c) {
			if (cost[i] == LongIntMap.MISS) n++;
			cost[i] = c;
		}
	}
	private static final class SparsePath {
		final int[] x;
		final int[] y;
		final int[] id;
		final int n;
		final int cost;
		final boolean pending;
		private SparsePath(int[] x, int[] y, int[] id, int n, int cost, boolean pending) {
			this.x = x;
			this.y = y;
			this.id = id;
			this.n = n;
			this.cost = cost;
			this.pending = pending;
		}
		static SparsePath pending() {return new SparsePath(null, null, null, 0, 0, true);}
		static SparsePath of(ChartPlotterSparseNodes.Snapshot nodes, int[] prev, int cost, int sx, int sy, int tx, int ty) {
			int n = 0;
			for (int i = 1; i >= 0; i = prev[i]) n++;
			int[] x = new int[n];
			int[] y = new int[n];
			int[] id = new int[n];
			int p = n;
			for (int i = 1; i >= 0; i = prev[i]) {
				p--;
				x[p] = sparseX(nodes, i, sx, tx);
				y[p] = sparseY(nodes, i, sy, ty);
				id[p] = i;
			}
			return new SparsePath(x, y, id, n, cost, false);
		}
	}
	private static final class Corridor {
		final int[] x;
		final int[] y;
		final int n;
		final Bounds b;
		final int cap;
		final int band;
		final int width;
		final byte[] mask;
		final String name;
		final SparseDebug debug;
		int cells;
		int hit;
		int miss;
		private Corridor(int[] x, int[] y, int n, Bounds b, int cap, int band, String name, SparseDebug debug) {
			this.x = x;
			this.y = y;
			this.n = n;
			this.b = b;
			this.cap = cap;
			this.band = band;
			this.name = name;
			this.debug = debug;
			width = b.maxX - b.minX + 1;
			mask = new byte[width * (b.maxY - b.minY + 1)];
			fill();
		}
		boolean contains(int px, int py) {
			int dx = px - b.minX;
			int dy = py - b.minY;
			if (dx < 0 || dy < 0 || dx >= width || dy > b.maxY - b.minY) {
				miss++;
				return false;
			}
			boolean ok = mask[dx + dy * width] != 0;
			if (ok) hit++;
			else miss++;
			return ok;
		}
		void search(String result, int seen, int made, int steps, int moves, int pass, long routeMs) {
			if (debug != null) debug.search(name, result, seen, made, steps, moves, pass, routeMs, hit, miss);
		}
		private void fill() {
			for (int i = 1; i < n; i++) {
				int ax = x[i - 1];
				int ay = y[i - 1];
				int bx = x[i];
				int by = y[i];
				int minX = Math.max(b.minX, Math.min(ax, bx) - band);
				int minY = Math.max(b.minY, Math.min(ay, by) - band);
				int maxX = Math.min(b.maxX, Math.max(ax, bx) + band);
				int maxY = Math.min(b.maxY, Math.max(ay, by) + band);
				for (int py = minY; py <= maxY; py++) {
					int row = (py - b.minY) * width;
					for (int px = minX; px <= maxX; px++) {
						if (!nearSegment(px, py, ax, ay, bx, by, band)) continue;
						int p = px - b.minX + row;
						if (mask[p] != 0) continue;
						mask[p] = 1;
						cells++;
					}
				}
			}
		}
	}
	private static final class SparseHeap {
		int[] id;
		int[] f;
		int n;
		private SparseHeap(int size) {
			id = new int[size];
			f = new int[size];
		}
		boolean hasNext() {return n != 0;}
		void add(int v, int ff) {
			if (n == id.length) grow();
			id[n] = v;
			f[n] = ff;
			up(n++);
		}
		int poll() {
			int v = id[0];
			int ri = id[--n];
			int rf = f[n];
			if (n > 0) {
				id[0] = ri;
				f[0] = rf;
				down(0);
			}
			return v;
		}
		private void up(int i) {
			int vi = id[i];
			int vf = f[i];
			while (i > 0) {
				int p = (i - 1) >>> 1;
				if (!less(vf, vi, f[p], id[p])) break;
				id[i] = id[p];
				f[i] = f[p];
				i = p;
			}
			id[i] = vi;
			f[i] = vf;
		}
		private void down(int i) {
			int vi = id[i];
			int vf = f[i];
			for (;;) {
				int l = i * 2 + 1;
				if (l >= n) break;
				int r = l + 1;
				int c = r < n && less(f[r], id[r], f[l], id[l]) ? r : l;
				if (!less(f[c], id[c], vf, vi)) break;
				id[i] = id[c];
				f[i] = f[c];
				i = c;
			}
			id[i] = vi;
			f[i] = vf;
		}
		private void grow() {
			id = Arrays.copyOf(id, id.length << 1);
			f = Arrays.copyOf(f, f.length << 1);
		}
		private static boolean less(int af, int ai, int bf, int bi) {
			if (af != bf) return af < bf;
			return ai < bi;
		}
	}
	private static final class Work {
		final Nodes a = new Nodes(1 << 15);
		final Heap aq = new Heap(a, 1 << 15);
		final LongIntMap ag = new LongIntMap(1 << 15);
		final DenseBest best = new DenseBest();
		final MoveCache moves = new MoveCache();
		final Reach reach = new Reach();
		void clear() {
			a.clear();
			aq.clear();
		}
	}
	private static final class Reach {
		final LongIntMap seen = new LongIntMap(1 << 15);
		long[] q = new long[1 << 15];
		int n;
		void clear() {
			seen.clear();
			n = 0;
		}
		void add(long k) {
			if (n == q.length) q = Arrays.copyOf(q, q.length << 1);
			q[n++] = k;
			seen.put(k, 1);
		}
	}
	private static final class DenseBest {
		int[] v;
		int minX;
		int minY;
		int width;
		int area;
		int step;
		boolean on;
		void reset(Bounds b, int dirStep) {
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long cells = (long) width * height;
			long n = cells * (DX.length / dirStep);
			if (n <= 0 || n > EDGE_MAX) {
				on = false;
				return;
			}
			area = (int) cells;
			step = dirStep;
			if (v == null || v.length < n) v = new int[(int) n];
			Arrays.fill(v, 0, (int) n, LongIntMap.MISS);
			on = true;
		}
		int get(int x, int y, int dir) {
			return v[x - minX + (y - minY) * width + dir / step * area];
		}
		void put(int x, int y, int dir, int val) {
			v[x - minX + (y - minY) * width + dir / step * area] = val;
		}
	}
	private static final class MoveCache {
		byte[] v;
		LongIntMap sparse;
		int minX;
		int minY;
		int width;
		int area;
		int step;
		int mode;
		boolean on;
		void reset(Bounds b, int dirStep) {
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long cells = (long) width * height;
			long n = cells * (DX.length / dirStep);
			step = dirStep;
			if (n <= 0) {
				on = false;
				mode = MC_OFF;
				return;
			}
			if (n <= EDGE_MAX) {
				area = (int) cells;
				if (v == null || v.length < n) v = new byte[(int) n];
				else Arrays.fill(v, 0, (int) n, (byte) 0);
				mode = MC_DENSE;
				on = true;
				return;
			}
			if (sparse == null) sparse = new LongIntMap(1 << 16);
			else sparse.clear();
			mode = MC_SPARSE;
			on = true;
		}
		int get(int x, int y, int odir, int dir) {
			if (mode == MC_SPARSE) return sparse.get(moveKey(x, y, odir, dir));
			int p = v[x - minX + (y - minY) * width + dir / step * area];
			return p == 0 ? LongIntMap.MISS : p - 2;
		}
		void put(int x, int y, int odir, int dir, int p) {
			if (mode == MC_SPARSE) {
				if (sparse.n < SPARSE_MOVE_MAX) sparse.put(moveKey(x, y, odir, dir), p);
				return;
			}
			v[x - minX + (y - minY) * width + dir / step * area] = (byte) (p + 2);
		}
		private static long moveKey(int x, int y, int odir, int dir) {return ((long) x & 0xfffffL) << 44 | ((long) y & 0xfffffL) << 8 | (long) odir << 4 | dir;}
	}
	private static final class Nodes {
		int[] x;
		int[] y;
		int[] dir;
		int[] g;
		int[] d;
		int[] f;
		int[] prev;
		int n;
		private Nodes(int size) {
			x = new int[size];
			y = new int[size];
			dir = new int[size];
			g = new int[size];
			d = new int[size];
			f = new int[size];
			prev = new int[size];
		}
		int add(int xx, int yy, int dd, int gg, int dist, int ff, int pp) {
			if (n == x.length) grow();
			x[n] = xx;
			y[n] = yy;
			dir[n] = dd;
			g[n] = gg;
			d[n] = dist;
			f[n] = ff;
			prev[n] = pp;
			return n++;
		}
		void clear() {n = 0;}
		private void grow() {
			int size = x.length << 1;
			x = Arrays.copyOf(x, size);
			y = Arrays.copyOf(y, size);
			dir = Arrays.copyOf(dir, size);
			g = Arrays.copyOf(g, size);
			d = Arrays.copyOf(d, size);
			f = Arrays.copyOf(f, size);
			prev = Arrays.copyOf(prev, size);
		}
	}
	private static final class Heap {
		final Nodes nodes;
		int[] q;
		int n;
		private Heap(Nodes nodes, int size) {
			this.nodes = nodes;
			q = new int[size];
		}
		boolean hasNext() {return n != 0;}
		void clear() {n = 0;}
		void add(int v) {
			if (n == q.length) q = Arrays.copyOf(q, q.length << 1);
			q[n] = v;
			up(n++);
		}
		int poll() {
			int v = q[0];
			int r = q[--n];
			if (n > 0) {
				q[0] = r;
				down(0);
			}
			return v;
		}
		private void up(int i) {
			int v = q[i];
			while (i > 0) {
				int p = (i - 1) >>> 1;
				int pv = q[p];
				if (!less(v, pv)) break;
				q[i] = pv;
				i = p;
			}
			q[i] = v;
		}
		private void down(int i) {
			int v = q[i];
			for (;;) {
				int l = i * 2 + 1;
				if (l >= n) break;
				int r = l + 1;
				int c = r < n && less(q[r], q[l]) ? r : l;
				if (!less(q[c], v)) break;
				q[i] = q[c];
				i = c;
			}
			q[i] = v;
		}
		private boolean less(int a, int b) {
			int af = nodes.f[a];
			int bf = nodes.f[b];
			if (af != bf) return af < bf;
			return nodes.g[a] > nodes.g[b];
		}
	}
	private static final class Footprint {
		final int minX;
		final int maxX;
		final int minY;
		final int maxY;
		final int[] x;
		final int[] y;
		final int[][] rx;
		final int[][] ry;
		final int[][] tx;
		final int[][] ty;
		final int[] tn;
		final boolean[] corner;
		final int n;
		private Footprint(WorldEntityConfig wc) {
			float ox = wc.getBoundsX();
			float oy = wc.getBoundsY();
			float hw = wc.getBoundsWidth() / 2f;
			float hh = wc.getBoundsHeight() / 2f;
			minX = (int) Math.floor(Math.min(ox - hw, ox + hw));
			maxX = (int) Math.ceil(Math.max(ox - hw, ox + hw));
			minY = (int) Math.floor(Math.min(oy - hh, oy + hh));
			maxY = (int) Math.ceil(Math.max(oy - hh, oy + hh));
			int nx = span(minX, maxX);
			int ny = span(minY, maxY);
			n = nx == 1 || ny == 1 ? nx * ny : 2 * nx + 2 * ny - 4;
			x = new int[n];
			y = new int[n];
			corner = new boolean[n];
			int p = 0;
			for (int ix = minX;; ix = next(ix, maxX)) {
				for (int iy = minY;; iy = next(iy, maxY)) {
					if (edge(ix, iy, minX, maxX, minY, maxY)) {
						x[p] = ix;
						y[p] = iy;
						corner[p] = (ix == minX || ix == maxX) && (iy == minY || iy == maxY);
						p++;
					}
					if (iy == maxY) break;
				}
				if (ix == maxX) break;
			}
			rx = new int[OR.length][n];
			ry = new int[OR.length][n];
			tx = new int[OR.length][];
			ty = new int[OR.length][];
			tn = new int[OR.length];
			for (int o = 0; o < OR.length; o++) {
				for (int i = 0; i < n; i++) {
					rx[o][i] = rotateX(0, OR[o], x[i], y[i]);
					ry[o][i] = rotateY(0, OR[o], x[i], y[i]);
				}
				tiles(o, nx * ny);
			}
		}
		int x(int cx, int o, int oi, int i) {return oi >= 0 ? cx + rx[oi][i] : rotateX(cx, o, x[i], y[i]);}
		int y(int cy, int o, int oi, int i) {return oi >= 0 ? cy + ry[oi][i] : rotateY(cy, o, x[i], y[i]);}
		private void tiles(int o, int cap) {
			int[] xs = new int[cap];
			int[] ys = new int[cap];
			int p = 0;
			for (int ix = minX;; ix = next(ix, maxX)) {
				for (int iy = minY;; iy = next(iy, maxY)) {
					int x = Math.floorDiv(TS / 2 + rotateX(0, OR[o], ix, iy), TS);
					int y = Math.floorDiv(TS / 2 + rotateY(0, OR[o], ix, iy), TS);
					if ((x != 0 || y != 0) && !has(xs, ys, p, x, y)) {
						xs[p] = x;
						ys[p++] = y;
					}
					if (iy == maxY) break;
				}
				if (ix == maxX) break;
			}
			tx[o] = Arrays.copyOf(xs, p);
			ty[o] = Arrays.copyOf(ys, p);
			tn[o] = p;
		}
		private static boolean has(int[] xs, int[] ys, int n, int x, int y) {
			for (int i = 0; i < n; i++) {
				if (xs[i] == x && ys[i] == y) return true;
			}
			return false;
		}
	}
	private static final class Bounds {
		final int minX;
		final int minY;
		final int maxX;
		final int maxY;
		private Bounds(int minX, int minY, int maxX, int maxY) {
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
		}
	}
}
