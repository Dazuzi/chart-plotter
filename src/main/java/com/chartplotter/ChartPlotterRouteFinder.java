package com.chartplotter;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
final class ChartPlotterRouteFinder {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int MAX = 9000000;
	private static final int EDGE_MAX = 1 << 24;
	private static final int DENSE_MAX = 24 << 20;
	private static final int LAZY_MAX = 1 << 27;
	private static final int SPARSE_MOVE_MAX = 1 << 22;
	private static final int SPARSE_LINK = 128;
	private static final int SPARSE_CONNECT = 192;
	private static final int SPARSE_LOCAL_LINK = 256;
	private static final int SPARSE_LOCAL_TRIES = 8;
	private static final int STEP = 32;
	private static final int MODE_BASE = 0;
	private static final int MODE_TILE = 1;
	private static final int MODE_PHASE = 2;
	private static final int MC_OFF = 0;
	private static final int MC_DENSE = 1;
	private static final int MC_SPARSE = 2;
	private static final int Q_STALE = 0;
	private static final int Q_UPDATE = 1;
	private static final int Q_BUCKET = 2;
	private static final int EXP_NONE = 0;
	private static final int EXP_TIE_G_16_BASE = 1;
	private static final int EXP_TIE_G_8_BASE = 2;
	private static final int EXP_MOVE_DST_KEY = 3;
	private static final int EXP_MAP_DOM = 4;
	private static final int EXP_POINT_FP = 5;
	private static final int EXP_PHASE_FP = 6;
	private static final int EXP_ACTIVE = EXP_PHASE_FP;
	private static final int BUCKET_TIE_8 = 8;
	private static final int BUCKET_TIE_16 = 16;
	private static final int REACH_CHECK = 4095;
	private static final int[] BENCH_TX = {2861, 2390, 1453};
	private static final int[] BENCH_TY = {3399, 3547, 3459};
	private static final int[] DX = {0, 4, 7, 11, 10, 9, 7, 4, 0, -5, -7, -9, -10, -11, -7, -5};
	private static final int[] DY = {10, 9, 7, 5, 0, -4, -7, -9, -10, -11, -7, -4, 0, 5, 7, 11};
	private static final int[] COST = {100, 98, 98, 120, 100, 98, 98, 98, 100, 120, 98, 98, 100, 120, 98, 120};
	private static final int[] OR = {1024, 1152, 1280, 1408, 1536, 1664, 1792, 1920, 0, 128, 256, 384, 512, 640, 768, 896};
	private static final int[][] HX = hitOffsets(true);
	private static final int[][] HY = hitOffsets(false);
	private static final int MOVE = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
	private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);
	private ChartPlotterRouteFinder() {}
	static ChartPlotterRoute find(ChartPlotterCollisionData data, WorldEntityConfig wc, int start, int sx, int sy, int phaseX, int phaseY, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirs, boolean adaptive, int targetRadius, ChartPlotterSparseNodes.Snapshot sparse, boolean debugSparse, int sparseBand, BooleanSupplier cancel, Consumer<ChartPlotterRoute> publish) {
		if (sparse != null && debugSparse) {
			ChartPlotterRoute r = findOne(data, wc, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirs, adaptive, targetRadius, sparse, sparseBand, null, true, cancel);
			if (r.status == ChartPlotterRoute.PENDING) return r;
			publish(publish, r);
			ChartPlotterRoute b = benchSuite(data, wc, start, sx, sy, phaseX, phaseY, turnBias, reverse, fast, dirs, adaptive, targetRadius, sparse, sparseBand, cancel, publish);
			if (b.status == ChartPlotterRoute.PENDING) return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
			return b;
		}
		return findOne(data, wc, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirs, adaptive, targetRadius, sparse, sparseBand, null, false, cancel);
	}
	private static ChartPlotterRoute benchSuite(ChartPlotterCollisionData data, WorldEntityConfig wc, int start, int sx, int sy, int phaseX, int phaseY, int turnBias, boolean reverse, boolean fast, int dirs, boolean adaptive, int targetRadius, ChartPlotterSparseNodes.Snapshot sparse, int sparseBand, BooleanSupplier cancel, Consumer<ChartPlotterRoute> publish) {
		System.out.println("bench suite from=" + sx + "," + sy + " cases=" + BENCH_TX.length + " nodes=" + sparse.n);
		ChartPlotterRoute last = ChartPlotterRoute.pending(sx, sy, BENCH_TX[0], BENCH_TY[0], turnBias, fast);
		for (int i = 0; i < BENCH_TX.length; i++) {
			SparseDebug debug = new SparseDebug(sx, sy, BENCH_TX[i], BENCH_TY[i], sparse.n, "case=" + (i + 1) + "/" + BENCH_TX.length);
			ChartPlotterRoute r = findOne(data, wc, start, sx, sy, phaseX, phaseY, BENCH_TX[i], BENCH_TY[i], turnBias, reverse, fast, dirs, adaptive, targetRadius, sparse, sparseBand, debug, true, cancel);
			if (r.status == ChartPlotterRoute.PENDING) return r;
			publish(publish, r);
			last = r;
		}
		return last;
	}
	private static void publish(Consumer<ChartPlotterRoute> publish, ChartPlotterRoute r) {
		if (publish != null) publish.accept(r);
	}
	private static ChartPlotterRoute findOne(ChartPlotterCollisionData data, WorldEntityConfig wc, int start, int sx, int sy, int phaseX, int phaseY, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirs, boolean adaptive, int targetRadius, ChartPlotterSparseNodes.Snapshot sparse, int sparseBand, SparseDebug debug, boolean experiment, BooleanSupplier cancel) {
		sparseBand = Math.max(20, Math.min(200, sparseBand));
		Footprint fp = wc == null ? null : new Footprint(wc);
		int dirStep = dirs == 8 ? 2 : 1;
		boolean eight = adaptiveEight(fp, sx, sy, tx, ty, dirStep, adaptive);
		int step = eight ? 2 : dirStep;
		if (sparse == null) {
			Bench bench = debug == null ? null : new Bench(debug.bench("base", 0, 0, 0), sx, sy, tx, ty, reverse, fast, fp == null ? MODE_BASE : MODE_TILE, targetRadius, null);
			return findBase(data, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, step, fp == null ? MODE_BASE : MODE_TILE, targetRadius, bench, cancel);
		}
		SparsePath sp = sparsePath(data, sparse, sx, sy, tx, ty, targetRadius, sparseBand, cancel);
		boolean sparseFan = sp != null && adaptive && dirStep == 1;
		int sparseStep = sparseFan ? 1 : step;
		int sparseCandidates = sparseCandidates(dirs, fast, adaptive, fp);
		if (sp != null && sp.pending) {
			ChartPlotterRoute r = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
			if (debug != null) debug.result("pending", r);
			return r;
		}
		if (sp != null) {
			ChartPlotterRoute r = sparseRoute(data, sparse, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, sparseStep, sparseFan, fp == null ? MODE_BASE : MODE_TILE, targetRadius, sparseBand, sp, sparseCandidates, debug, experiment, cancel);
			if (r.status == ChartPlotterRoute.PENDING || r.status == ChartPlotterRoute.OK) {
				if (debug != null) debug.result("sparse-selected", r);
				return r;
			}
			if (debug != null) debug.result("sparse-corridors-failed", r);
		}
		ChartPlotterRoute r = ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, fast);
		if (debug != null) debug.result("sparse-missing", r);
		return r;
	}
	private static ChartPlotterRoute find(ChartPlotterCollisionData raw, Footprint fp, int start, int sx, int sy, int phaseX, int phaseY, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, boolean dirFan, int mode, int targetRadius, Corridor corridor, Bench bench, BooleanSupplier cancel) {
		turnBias = Math.max(0, Math.min(10, turnBias));
		int radius = radius(fp);
		Grid data = fp == null ? new Grid(raw) : Grid.lazy(raw, fp, radius, mode, phaseX, phaseY);
		int sf = data.flag(sx, sy);
		int tf = targetFlag(data, tx, ty, targetRadius, corridor != null);
		if (bench != null) bench.preNs = System.nanoTime() - bench.start;
		if (sf == ChartPlotterCollisionCache.UNKNOWN || tf == ChartPlotterCollisionCache.UNKNOWN) {
			ChartPlotterRoute r = ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias, fast);
			if (bench != null) bench.done(r);
			return r;
		}
		if (blocker(sf) || blocker(tf)) {
			ChartPlotterRoute r = ChartPlotterRoute.blocked(sx, sy, tx, ty, turnBias, fast);
			if (bench != null) bench.done(r);
			return r;
		}
		if (corridor == null) return findFull(raw, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirStep, mode, targetRadius, bench, cancel);
		return searchBucket(data, start, sx, sy, tx, ty, turnBias, corridor.b, corridor.cap, reverse, fast, dirStep, dirFan, targetRadius, corridor, EXP_MAP_DOM, bench, cancel);
	}
	private static ChartPlotterRoute findExperiment(ChartPlotterCollisionData raw, Footprint fp, int start, int sx, int sy, int phaseX, int phaseY, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, boolean dirFan, int mode, int targetRadius, Corridor corridor, Bench bench, BooleanSupplier cancel) {
		turnBias = Math.max(0, Math.min(10, turnBias));
		mode = experimentMode(mode);
		int radius = radius(fp);
		Grid data = fp == null ? new Grid(raw) : Grid.lazy(raw, fp, radius, mode, phaseX, phaseY);
		int sf = data.flag(sx, sy);
		int tf = targetFlag(data, tx, ty, targetRadius, corridor != null);
		if (bench != null) bench.preNs = System.nanoTime() - bench.start;
		if (sf == ChartPlotterCollisionCache.UNKNOWN || tf == ChartPlotterCollisionCache.UNKNOWN) {
			ChartPlotterRoute r = ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias, fast);
			if (bench != null) bench.done(r);
			return r;
		}
		if (blocker(sf) || blocker(tf)) {
			ChartPlotterRoute r = ChartPlotterRoute.blocked(sx, sy, tx, ty, turnBias, fast);
			if (bench != null) bench.done(r);
			return r;
		}
		if (corridor == null) return findFull(raw, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirStep, mode, targetRadius, bench, cancel);
		return searchBucket(data, start, sx, sy, tx, ty, turnBias, corridor.b, corridor.cap, reverse, fast, dirStep, dirFan, targetRadius, corridor, EXP_ACTIVE, bench, cancel);
	}
	private static ChartPlotterRoute findBase(ChartPlotterCollisionData raw, Footprint fp, int start, int sx, int sy, int phaseX, int phaseY, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, int mode, int targetRadius, Bench parent, BooleanSupplier cancel) {
		turnBias = Math.max(0, Math.min(10, turnBias));
		int radius = radius(fp);
		Bounds full = bounds(sx, sy, tx, ty, maxMargin(sx, sy, tx, ty, fp));
		Grid base = new Grid(raw);
		Grid probe = fp == null ? base : Grid.lazy(raw, fp, radius, mode, phaseX, phaseY);
		int sf = probe.flag(sx, sy);
		int tf = targetFlag(probe, tx, ty, targetRadius, false);
		if (sf == ChartPlotterCollisionCache.UNKNOWN || tf == ChartPlotterCollisionCache.UNKNOWN) return ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias, fast);
		if (blocker(sf) || blocker(tf)) return ChartPlotterRoute.blocked(sx, sy, tx, ty, turnBias, fast);
		int max = maxMargin(sx, sy, tx, ty, null);
		int fm = tightMargin(sx, sy, tx, ty);
		int attempt = 1;
		for (int m = fm;; m = Math.min(max, m * 2)) {
			Bounds b = bounds(sx, sy, tx, ty, m);
			int cap = cap(sx, sy, tx, ty, m);
			Grid data = fp == null ? base : Grid.lazy(raw, fp, radius, mode, phaseX, phaseY);
			Bench bench = parent == null ? null : new Bench(parent.name + " attempt=" + attempt + " margin=" + m, sx, sy, tx, ty, reverse, fast, mode, targetRadius, null);
			ChartPlotterRoute r = searchBase(data, start, sx, sy, tx, ty, turnBias, b, cap, reverse, fast, dirStep, targetRadius, bench, cancel);
			if (r.status == ChartPlotterRoute.PENDING) return r;
			if (r.status == ChartPlotterRoute.OK) return r;
			if (m == max) {
				int c = connected(base, WORK.get().reach, sx, sy, tx, ty, targetRadius, full, cancel);
				if (c < 0) return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
				if (c == 0) return ChartPlotterRoute.blocked(sx, sy, tx, ty, turnBias, fast);
				return r;
			}
			attempt++;
		}
	}
	private static ChartPlotterRoute findFull(ChartPlotterCollisionData raw, Footprint fp, int start, int sx, int sy, int phaseX, int phaseY, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, int mode, int targetRadius, Bench parent, BooleanSupplier cancel) {
		int radius = radius(fp);
		Grid base = new Grid(raw);
		Bounds full = bounds(sx, sy, tx, ty, maxMargin(sx, sy, tx, ty, fp));
		int max = maxMargin(sx, sy, tx, ty, null);
		int fm = tightMargin(sx, sy, tx, ty);
		int attempt = 1;
		for (int m = fm;; m = Math.min(max, m * 2)) {
			Bounds b = bounds(sx, sy, tx, ty, m);
			int cap = cap(sx, sy, tx, ty, m);
			Grid data = fp == null ? base : Grid.lazy(raw, fp, radius, mode, phaseX, phaseY);
			Bench bench = parent == null ? null : new Bench(parent.name + " attempt=" + attempt + " margin=" + m, sx, sy, tx, ty, reverse, fast, mode, targetRadius, null);
			ChartPlotterRoute r = search(data, start, sx, sy, tx, ty, turnBias, b, cap, reverse, fast, dirStep, targetRadius, bench, cancel);
			if (r.status == ChartPlotterRoute.PENDING) return r;
			if (r.status == ChartPlotterRoute.OK) return r;
			if (m == max) {
				int c = connected(base, WORK.get().reach, sx, sy, tx, ty, targetRadius, full, cancel);
				if (c < 0) return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
				if (c == 0) return ChartPlotterRoute.blocked(sx, sy, tx, ty, turnBias, fast);
				return r;
			}
			attempt++;
		}
	}
	private static ChartPlotterRoute searchBase(Grid data, int start, int sx, int sy, int tx, int ty, int turnBias, Bounds b, int cap, boolean reverse, boolean fast, int dirStep, int targetRadius, Bench bench, BooleanSupplier cancel) {
		long searchStart = bench == null ? 0 : System.nanoTime();
		if (bench != null) bench.begin(b, cap, null);
		Work w = WORK.get();
		w.clearBase();
		Nodes nodes = w.ba;
		SimpleHeap q = w.bq;
		LongIntMap best = w.bg;
		BaseMoveCache moves = w.bmoves;
		moves.reset(b, dirStep);
		data.cache(b, LAZY_MAX);
		DenseCost dense = w.bbest;
		dense.reset(b, dirStep);
		boolean db = dense.on;
		if (!db) best.clear();
		addStartsBase(q, dense, best, nodes, sx, sy, tx, ty, turnBias, start, fast, dirStep, db);
		boolean capped = false;
		int polls = 0;
		int seen = 0;
		int stale = 0;
		int maxQ = q.n;
		int nearest = Integer.MAX_VALUE;
		int minX = b.minX;
		int minY = b.minY;
		int maxX = b.maxX;
		int maxY = b.maxY;
		int turn = turn(turnBias);
		if (bench != null) bench.search(b, cap, dirStep, false, db, moves.mode, Q_STALE, false, false, expName(EXP_NONE), null);
		while (q.hasNext()) {
			if (cancel.getAsBoolean()) {
				ChartPlotterRoute r = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
				if (bench != null) bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
				return r;
			}
			int a = q.poll();
			polls++;
			int ax = nodes.x[a];
			int ay = nodes.y[a];
			int ad = nodes.dir[a];
			int ag = nodes.g[a];
			int dist = nodes.d[a];
			if (nodes.prev[a] >= 0) {
				int bg = db ? dense.get(ax, ay, ad) : best.get(state(ax, ay, ad));
				if (bg == LongIntMap.MISS || ag != bg) {
					stale++;
					continue;
				}
			}
			int td = dist(ax, ay, tx, ty);
			if (td < nearest) nearest = td;
			if (td <= targetRadius) {
				long routeStart = bench == null ? 0 : System.nanoTime();
				ChartPlotterRoute r = route(data, start, nodes, a, sx, sy, tx, ty, turnBias, reverse, fast, dirStep);
				if (bench != null) {
					bench.routeNs = System.nanoTime() - routeStart;
					bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
				}
				return r;
			}
			if (++seen > MAX) {
				capped = true;
				break;
			}
			int fn = DX.length / dirStep;
			for (int di = 0; di < fn; di++) {
				int i = di * dirStep;
				if (bench != null) bench.steps++;
				int nx = ax + DX[i];
				int ny = ay + DY[i];
				if (nx < minX || ny < minY || nx > maxX || ny > maxY) {
					if (bench != null) bench.boundsSkip++;
					continue;
				}
				int step = COST[i];
				int nd = dist + step;
				if (nd > cap) {
					if (bench != null) bench.capSkip++;
					continue;
				}
				int ng = ag + step + (ad != i ? turn : 0);
				long key = 0;
				if (db) {
					int old = dense.get(nx, ny, i);
					if (old != LongIntMap.MISS && old <= ng) {
						if (bench != null) bench.bestSkip++;
						continue;
					}
				} else {
					key = state(nx, ny, i);
					int old = best.get(key);
					if (old != LongIntMap.MISS && old <= ng) {
						if (bench != null) bench.bestSkip++;
						continue;
					}
				}
				int p = LongIntMap.MISS;
				if (moves.on) {
					p = moves.get(ax, ay, ad, i);
					if (bench != null && p != LongIntMap.MISS) bench.moveHit++;
				}
				if (p == LongIntMap.MISS) {
					if (bench != null) bench.moveMiss++;
					p = move(data, ax, ay, nx, ny, i, reverse);
					if (moves.on) moves.put(ax, ay, ad, i, p);
				}
				if (p == 1) {
					if (bench != null) bench.movePass++;
				} else {
					if (bench != null) {
						if (p < 0) bench.moveUnknown++;
						else bench.moveBlock++;
					}
					continue;
				}
				if (db) dense.put(nx, ny, i, ng);
				else best.put(key, ng);
				int hh = h(nx, ny, tx, ty, i, turnBias);
				q.add(nodes.add(nx, ny, i, ng, nd, ng + wh(hh, fast), a));
				if (bench != null) bench.queued++;
				if (q.n > maxQ) maxQ = q.n;
			}
		}
		ChartPlotterRoute r = capped ? ChartPlotterRoute.complex(sx, sy, tx, ty, turnBias, fast) : ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, fast);
		if (bench != null) bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
		return r;
	}
	private static ChartPlotterRoute search(Grid data, int start, int sx, int sy, int tx, int ty, int turnBias, Bounds b, int cap, boolean reverse, boolean fast, int dirStep, int targetRadius, Bench bench, BooleanSupplier cancel) {
		long searchStart = bench == null ? 0 : System.nanoTime();
		if (bench != null) bench.begin(b, cap, null);
		Work w = WORK.get();
		w.clear();
		Nodes nodes = w.a;
		Heap q = w.aq;
		LongIntMap best = w.ag;
		MoveCache moves = w.moves;
		moves.reset(b, dirStep);
		data.cache(b);
		DenseBest dense = w.best;
		dense.reset(b, dirStep);
		boolean db = dense.on;
		if (!db) best.clear();
		addStarts(q, dense, best, nodes, sx, sy, tx, ty, turnBias, start, fast, dirStep, db);
		boolean capped = false;
		int polls = 0;
		int seen = 0;
		int stale = 0;
		int maxQ = q.n;
		int nearest = Integer.MAX_VALUE;
		int minX = b.minX;
		int minY = b.minY;
		int maxX = b.maxX;
		int maxY = b.maxY;
		int turn = turn(turnBias);
		if (bench != null) bench.search(b, cap, dirStep, false, db, moves.mode, Q_UPDATE, false, false, expName(EXP_NONE), null);
		while (q.hasNext()) {
			if (cancel.getAsBoolean()) {
				ChartPlotterRoute r = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
				if (bench != null) bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
				return r;
			}
			int a = q.poll();
			polls++;
			int ax = nodes.x[a];
			int ay = nodes.y[a];
			int ad = nodes.dir[a];
			int ag = nodes.g[a];
			int dist = nodes.d[a];
			if (nodes.prev[a] >= 0) {
				if (db) {
					if (dense.node(ax, ay, ad) != a) {
						stale++;
						continue;
					}
				} else {
					int bg = best.get(state(ax, ay, ad));
					if (bg == LongIntMap.MISS || ag != bg) {
						stale++;
						continue;
					}
				}
			}
			int td = dist(ax, ay, tx, ty);
			if (td < nearest) nearest = td;
			if (td <= targetRadius) {
				long routeStart = bench == null ? 0 : System.nanoTime();
				ChartPlotterRoute r = route(data, start, nodes, a, sx, sy, tx, ty, turnBias, reverse, fast, dirStep);
				if (bench != null) {
					bench.routeNs = System.nanoTime() - routeStart;
					bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
				}
				return r;
			}
			if (++seen > MAX) {
				capped = true;
				break;
			}
			int fn = DX.length / dirStep;
			for (int di = 0; di < fn; di++) {
				int i = di * dirStep;
				if (bench != null) bench.steps++;
				int nx = ax + DX[i];
				int ny = ay + DY[i];
				if (nx < minX || ny < minY || nx > maxX || ny > maxY) {
					if (bench != null) bench.boundsSkip++;
					continue;
				}
				int step = COST[i];
				int nd = dist + step;
				if (nd > cap) {
					if (bench != null) bench.capSkip++;
					continue;
				}
				int ng = ag + step + (ad != i ? turn : 0);
				long key = 0;
				int oldNode = -1;
				if (db) {
					oldNode = dense.node(nx, ny, i);
					if (oldNode >= 0 && nodes.g[oldNode] <= ng) {
						if (bench != null) bench.bestSkip++;
						continue;
					}
				} else {
					key = state(nx, ny, i);
					int old = best.get(key);
					if (old != LongIntMap.MISS && old <= ng) {
						if (bench != null) bench.bestSkip++;
						continue;
					}
				}
				int p = LongIntMap.MISS;
				if (moves.on) {
					p = moves.get(ax, ay, i);
					if (bench != null && p != LongIntMap.MISS) bench.moveHit++;
				}
				if (p == LongIntMap.MISS) {
					if (bench != null) bench.moveMiss++;
					p = move(data, ax, ay, nx, ny, i, reverse);
					if (moves.on) moves.put(ax, ay, i, p);
				}
				if (p == 1) {
					if (bench != null) bench.movePass++;
				} else {
					if (bench != null) {
						if (p < 0) bench.moveUnknown++;
						else bench.moveBlock++;
					}
					continue;
				}
				int hh = h(nx, ny, tx, ty, i, turnBias);
				int nf = ng + wh(hh, fast);
				if (db) {
					if (oldNode >= 0 && q.update(oldNode, ng, nd, nf, a)) {
						if (bench != null) bench.updated++;
					} else {
						int nn = nodes.add(nx, ny, i, ng, nd, nf, a);
						dense.put(nx, ny, i, nn);
						q.add(nn);
						if (bench != null) bench.queued++;
						if (q.n > maxQ) maxQ = q.n;
					}
				} else {
					best.put(key, ng);
					q.add(nodes.add(nx, ny, i, ng, nd, nf, a));
					if (bench != null) bench.queued++;
					if (q.n > maxQ) maxQ = q.n;
				}
			}
		}
		ChartPlotterRoute r = capped ? ChartPlotterRoute.complex(sx, sy, tx, ty, turnBias, fast) : ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, fast);
		if (bench != null) bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
		return r;
	}
	private static ChartPlotterRoute searchBucket(Grid data, int start, int sx, int sy, int tx, int ty, int turnBias, Bounds b, int cap, boolean reverse, boolean fast, int dirStep, boolean dirFan, int targetRadius, Corridor corridor, int exp, Bench bench, BooleanSupplier cancel) {
		long searchStart = bench == null ? 0 : System.nanoTime();
		if (bench != null) bench.begin(b, cap, corridor);
		Work w = WORK.get();
		BucketHeap q = w.bucket;
		q.sentinel = bucketSentinel(exp);
		q.base = bucketBase(exp, sx, sy, tx, ty, fast);
		w.clearBase();
		Nodes nodes = w.ba;
		LongIntMap best = w.bg;
		BaseMoveCache moves = w.bmoves;
		moves.reset(b, dirStep, expMoveDst(exp));
		data.cache(b, LAZY_MAX);
		DenseCost dense = w.bbest;
		int turn = turn(turnBias);
		dense.reset(b, dirStep, turn > 0);
		boolean db = dense.on;
		if (!db) best.clear();
		DomCost dom = !db && expMapDom(exp) && turn > 0 ? w.dom : null;
		if (dom != null) dom.reset(b, dirStep);
		int tieG = tieG(exp);
		boolean dstBlock = moves.mode == MC_SPARSE;
		addStartsBucket(q, dense, best, nodes, sx, sy, tx, ty, turnBias, start, fast, dirStep, db, tieG);
		boolean capped = false;
		int polls = 0;
		int seen = 0;
		int stale = 0;
		int maxQ = q.n;
		int nearest = Integer.MAX_VALUE;
		int minX = b.minX;
		int minY = b.minY;
		int maxX = b.maxX;
		int maxY = b.maxY;
		int width = b.maxX - b.minX + 1;
		int[] tileDelta = w.tileDelta;
		int[] bestDelta = w.bestDelta;
		int[] moveDelta = w.moveDelta;
		byte[] corridorMask = corridor == null ? null : corridor.mask;
		boolean moveDense = moves.mode == MC_DENSE;
		for (int i = 0; i < DX.length; i++) {
			tileDelta[i] = DX[i] + DY[i] * width;
			if (db) bestDelta[i] = tileDelta[i] + i / dirStep * dense.area;
			if (moveDense) moveDelta[i] = i / dirStep * moves.area;
		}
		boolean domFirst = dense.dom;
		if (dom != null) domFirst = true;
		if (bench != null) bench.search(b, cap, dirStep, dirFan, db, moves.mode, Q_BUCKET, dense.dom || dom != null, dstBlock, expName(exp), corridor);
		while (q.hasNext()) {
			if (cancel.getAsBoolean()) {
				ChartPlotterRoute r = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
				if (bench != null) bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
				return r;
			}
			int a = q.poll();
			polls++;
			int ax = nodes.x[a];
			int ay = nodes.y[a];
			int ad = nodes.dir[a];
			int ag = nodes.g[a];
			int dist = nodes.d[a];
			int pos = ax - minX + (ay - minY) * width;
			if (nodes.prev[a] >= 0) {
				int bg = db ? dense.v[pos + ad / dirStep * dense.area] : best.get(state(ax, ay, ad));
				if (bg == LongIntMap.MISS || ag != bg) {
					stale++;
					continue;
				}
			}
			int td = dist(ax, ay, tx, ty);
			if (td < nearest) nearest = td;
			if (td <= targetRadius) {
				long routeStart = bench == null ? 0 : System.nanoTime();
				ChartPlotterRoute r = route(data, start, nodes, a, sx, sy, tx, ty, turnBias, reverse, fast, dirStep);
				if (bench != null) {
					bench.routeNs = System.nanoTime() - routeStart;
					bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
				}
				return r;
			}
			if (db && turn > 0) {
				if (bench != null) bench.domCheck++;
				if (dense.dominated(pos, ad, ag, turn)) {
					if (bench != null) bench.dominated++;
					continue;
				}
			} else if (dom != null) {
				if (bench != null) bench.domCheck++;
				if (dom.dominated(pos, ad, ag, turn)) {
					if (bench != null) bench.dominated++;
					continue;
				}
			}
			if (++seen > MAX) {
				capped = true;
				break;
			}
			int fn = DX.length / dirStep;
			for (int di = 0; di < fn; di++) {
				int i = di * dirStep;
				if (bench != null) bench.steps++;
				int nx = ax + DX[i];
				int ny = ay + DY[i];
				if (nx < minX || ny < minY || nx > maxX || ny > maxY) {
					if (bench != null) bench.boundsSkip++;
					continue;
				}
				int np = pos + tileDelta[i];
				if (corridorMask != null && corridorMask[np] == 0) {
					if (bench != null) bench.corridorSkip++;
					continue;
				}
				int step = COST[i];
				int nd = dist + step;
				if (nd > cap) {
					if (bench != null) bench.capSkip++;
					continue;
				}
				int ng = ag + step + (ad != i ? turn : 0);
				long key = 0;
				int bestPos = 0;
				if (domFirst) {
					if (bench != null) bench.domCheck++;
					boolean dominated = db ? dense.dominated(np, i, ng, turn) : dom != null && dom.dominated(np, i, ng, turn);
					if (dominated) {
						if (bench != null) bench.domSkip++;
						continue;
					}
				}
				if (db) {
					bestPos = pos + bestDelta[i];
					int old = dense.v[bestPos];
					if (old != LongIntMap.MISS && old <= ng) {
						if (bench != null) bench.bestSkip++;
						continue;
					}
				} else {
					key = state(nx, ny, i);
					int old = best.get(key);
					if (old != LongIntMap.MISS && old <= ng) {
						if (bench != null) bench.bestSkip++;
						continue;
					}
				}
				if (!domFirst && db && turn > 0) {
					if (bench != null) bench.domCheck++;
					if (dense.dominated(np, i, ng, turn)) {
						if (bench != null) bench.domSkip++;
						continue;
					}
				}
				int p = LongIntMap.MISS;
				int movePos = 0;
				if (moveDense) {
					movePos = pos + moveDelta[i];
					int v = moves.v[movePos];
					if (v != 0) {
						p = v - 2;
						if (bench != null) bench.moveHit++;
					}
				} else if (moves.on) {
					p = moves.get(ax, ay, ad, i);
					if (bench != null && p != LongIntMap.MISS) bench.moveHit++;
				}
				if (p == LongIntMap.MISS) {
					if (bench != null) bench.moveMiss++;
					if (dstBlock && dstBlocked(data, nx, ny, i, reverse)) {
						p = 0;
						if (bench != null) bench.movePreBlock++;
					} else {
						p = move(data, ax, ay, nx, ny, i, reverse);
					}
					if (moveDense) moves.v[movePos] = (byte) (p + 2);
					else if (moves.on) moves.put(ax, ay, ad, i, p);
				}
				if (p == 1) {
					if (bench != null) bench.movePass++;
				} else {
					if (bench != null) {
						if (p < 0) bench.moveUnknown++;
						else bench.moveBlock++;
					}
					continue;
				}
				if (db) {
					dense.v[bestPos] = ng;
					if (dense.dom) dense.domPut(np, i / dirStep, ng);
				}
				else {
					best.put(key, ng);
					if (dom != null) dom.put(np, i, ng);
				}
				int hh = h(nx, ny, tx, ty, i, turnBias);
				q.add(nodes.add(nx, ny, i, ng, nd, ng + wh(hh, fast), a), tieG);
				if (bench != null) bench.queued++;
				if (q.n > maxQ) maxQ = q.n;
			}
		}
		ChartPlotterRoute r = capped ? ChartPlotterRoute.complex(sx, sy, tx, ty, turnBias, fast) : ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, fast);
		if (bench != null) bench.done(r, searchStart, polls, seen, stale, nodes.n, maxQ, nearest);
		return r;
	}
	private static ChartPlotterRoute sparseRoute(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, Footprint fp, int start, int sx, int sy, int phaseX, int phaseY, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, boolean dirFan, int mode, int targetRadius, int sparseBand, SparsePath first, int maxCandidates, SparseDebug debug, boolean experiment, BooleanSupplier cancel) {
		SparsePath[] paths = sparseAlternates(data, nodes, sx, sy, tx, ty, targetRadius, sparseBand, first, maxCandidates, cancel);
		int n = 0;
		for (int i = 0; i < paths.length; i++) {
			if (paths[i].pending) return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast);
			SparsePath p = simplifySparse(data, paths[i]);
			if (sameSparse(paths, n, p)) continue;
			paths[n++] = p;
		}
		paths = Arrays.copyOf(paths, n);
		sortSparse(paths, turnBias);
		if (debug != null && paths.length > 0) debug.sparseCandidate(paths.length, paths[0], sparseScore(paths[0], turnBias));
		return sparseRouteFast(data, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirStep, dirFan, mode, targetRadius, sparseBand, paths, debug, experiment, cancel);
	}
	private static ChartPlotterRoute sparseRouteFast(ChartPlotterCollisionData data, Footprint fp, int start, int sx, int sy, int phaseX, int phaseY, int tx, int ty, int turnBias, boolean reverse, boolean fast, int dirStep, boolean dirFan, int mode, int targetRadius, int sparseBand, SparsePath[] paths, SparseDebug debug, boolean experiment, BooleanSupplier cancel) {
		int tries = paths.length > 0 && paths[0].cost >= 12000 ? 2 : 1;
		Corridor expC = null;
		SparsePath expP = null;
		int expI = 0;
		int expT = 0;
		for (int t = 0; t < tries; t++) {
			int band = t == 0 ? sparseBand : sparseRetryBand(sparseBand, paths[0]);
			if (t > 0 && band == sparseBand) continue;
			for (int i = 0; i < paths.length; i++) {
				SparsePath p = paths[i];
				Corridor c = corridor(p, radius(fp), targetRadius, band);
				Bench bench = debug == null ? null : new Bench(debug.bench("base", i + 1, paths.length, c.band), sx, sy, tx, ty, reverse, fast, mode, targetRadius, c);
				ChartPlotterRoute r = find(data, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirStep, dirFan, mode, targetRadius, c, bench, cancel).sparse(p.x, p.y, p.n, c.band);
				if (r.status == ChartPlotterRoute.PENDING) {
					if (debug != null) debug.candidate(i + 1, paths.length, r, Integer.MAX_VALUE);
					return r;
				}
				if (r.status != ChartPlotterRoute.OK && experiment && expC == null) {
					expC = c;
					expP = p;
					expI = i + 1;
					expT = paths.length;
				}
				if (r.status == ChartPlotterRoute.OK) {
					if (experiment) {
						Bench expBench = debug == null ? null : new Bench(debug.bench("experimental", i + 1, paths.length, c.band), sx, sy, tx, ty, reverse, fast, experimentMode(mode), targetRadius, c);
						ChartPlotterRoute exp = findExperiment(data, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirStep, dirFan, mode, targetRadius, c, expBench, cancel).sparse(p.x, p.y, p.n, c.band);
						if (exp.status == ChartPlotterRoute.PENDING) return exp;
						if (exp.status == ChartPlotterRoute.OK) r = r.experiment(exp);
						if (debug != null) {
							Bench base2Bench = new Bench(debug.bench("base2", i + 1, paths.length, c.band), sx, sy, tx, ty, reverse, fast, mode, targetRadius, c);
							ChartPlotterRoute base2 = find(data, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirStep, dirFan, mode, targetRadius, c, base2Bench, cancel).sparse(p.x, p.y, p.n, c.band);
							if (base2.status == ChartPlotterRoute.PENDING) return base2;
						}
					}
					int score = routeScore(r, turnBias);
					if (debug != null) debug.candidate(i + 1, paths.length, r, score);
					return r;
				}
			}
		}
		if (experiment && expC != null) {
			Bench expBench = debug == null ? null : new Bench(debug.bench("experimental", expI, expT, expC.band), sx, sy, tx, ty, reverse, fast, experimentMode(mode), targetRadius, expC);
			ChartPlotterRoute exp = findExperiment(data, fp, start, sx, sy, phaseX, phaseY, tx, ty, turnBias, reverse, fast, dirStep, dirFan, mode, targetRadius, expC, expBench, cancel).sparse(expP.x, expP.y, expP.n, expC.band);
			if (exp.status == ChartPlotterRoute.PENDING) return exp;
			if (exp.status == ChartPlotterRoute.OK) return exp;
		}
		return ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, fast);
	}
	private static int sparseRetryBand(int sparseBand, SparsePath p) {
		int band = Math.max(200, sparseBand + p.cost / 100);
		return Math.min(360, band);
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
	private static SparsePath[] sparseAlternates(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty, int targetRadius, int sparseBand, SparsePath first, int maxCandidates, BooleanSupplier cancel) {
		SparsePath[] paths = new SparsePath[maxCandidates];
		paths[0] = first;
		int n = 1;
		int edges = first.n - 1;
		for (int i = 1; i < maxCandidates && edges > 2; i++) {
			int edge = sparseBanEdge(edges, i);
			SparsePath p = sparsePath(data, nodes, sx, sy, tx, ty, targetRadius, sparseBand, first.id[edge - 1], first.id[edge], cancel);
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
	private static SparsePath sparsePath(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty, int targetRadius, int sparseBand, BooleanSupplier cancel) {
		return sparsePath(data, nodes, sx, sy, tx, ty, targetRadius, sparseBand, -1, -1, cancel);
	}
	private static SparsePath sparsePath(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty, int targetRadius, int sparseBand, int banA, int banB, BooleanSupplier cancel) {
		if (nodes == null) return null;
		if (nodes.n == 0) return null;
		if (near(sx, sy, tx, ty, targetRadius)) return null;
		Connector startCon = connector(data, nodes, sx, sy, sparseBand, cancel);
		Connector targetCon = connector(data, nodes, tx, ty, sparseBand, cancel);
		if (startCon.pending || targetCon.pending) return SparsePath.pending();
		if (startCon.n == 0 || targetCon.n == 0) return null;
		int n = nodes.n + 2;
		int[] g = new int[n];
		int[] prev = new int[n];
		boolean[] done = new boolean[n];
		SparseHeap q = new SparseHeap(n);
		Arrays.fill(g, LongIntMap.MISS);
		Arrays.fill(prev, -1);
		g[0] = 0;
		q.add(0, h(sx, sy, tx, ty));
		int seen = 0;
		while (q.hasNext()) {
			if ((seen++ & REACH_CHECK) == 0 && cancel.getAsBoolean()) {
				return SparsePath.pending();
			}
			int a = q.poll();
			if (done[a]) continue;
			if (a == 1) {
				return SparsePath.of(nodes, prev, g[1], sx, sy, tx, ty);
			}
			done[a] = true;
			int ax = sparseX(nodes, a, sx, tx);
			int ay = sparseY(nodes, a, sy, ty);
			int ag = g[a];
			for (int b = 1; b < n; b++) {
				if (b == a || done[b]) continue;
				if (banned(a, b, banA, banB)) continue;
				int bx = sparseX(nodes, b, sx, tx);
				int by = sparseY(nodes, b, sy, ty);
				int edge = sparseEdge(data, a, b, ax, ay, bx, by, startCon, targetCon);
				if (edge == LongIntMap.MISS) continue;
				int ng = ag + edge;
				if (g[b] != LongIntMap.MISS && g[b] <= ng) continue;
				g[b] = ng;
				prev[b] = a;
				q.add(b, ng + h(bx, by, tx, ty));
			}
		}
		return null;
	}
	private static boolean banned(int a, int b, int banA, int banB) {return banA >= 0 && (a == banA && b == banB || a == banB && b == banA);}
	private static SparsePath simplifySparse(ChartPlotterCollisionData data, SparsePath p) {
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
		if (n == p.n) return p;
		return new SparsePath(Arrays.copyOf(x, n), Arrays.copyOf(y, n), Arrays.copyOf(id, n), n, sparseCost(x, y, n), false);
	}
	private static int sparseCost(int[] x, int[] y, int n) {
		int c = 0;
		for (int i = 1; i < n; i++) c += h(x[i - 1], y[i - 1], x[i], y[i]);
		return c;
	}
	private static int sparseEdge(ChartPlotterCollisionData data, int a, int b, int ax, int ay, int bx, int by, Connector startCon, Connector targetCon) {
		if (a == 0 && b > 1) return startCon.cost[b - 2];
		if (b == 1 && a > 1) return targetCon.cost[a - 2];
		int link = a < 2 || b < 2 ? SPARSE_CONNECT : SPARSE_LINK;
		if (dist(ax, ay, bx, by) > link) return LongIntMap.MISS;
		int c = clearRaw(data, ax, ay, bx, by);
		if (c != 1) return LongIntMap.MISS;
		return h(ax, ay, bx, by);
	}
	private static Connector connector(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int band, BooleanSupplier cancel) {
		Connector c = new Connector(nodes.n);
		int[] ci = new int[SPARSE_LOCAL_TRIES];
		int[] cd = new int[SPARSE_LOCAL_TRIES];
		Arrays.fill(ci, -1);
		Arrays.fill(cd, Integer.MAX_VALUE);
		for (int i = 0; i < nodes.n; i++) {
			int d = dist(sx, sy, nodes.x[i], nodes.y[i]);
			if (d <= SPARSE_LOCAL_LINK) insertClosest(ci, cd, i, d);
			if (d > SPARSE_CONNECT) continue;
			int r = clearRaw(data, sx, sy, nodes.x[i], nodes.y[i]);
			if (r == 1) {
				c.add(i, h(sx, sy, nodes.x[i], nodes.y[i]));
				c.los++;
			}
		}
		if (c.los == 0) {
			for (int p : ci) {
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
		return c;
	}
	private static ChartPlotterRoute localConnect(ChartPlotterCollisionData data, int sx, int sy, int tx, int ty, int band, BooleanSupplier cancel) {
		int m = Math.max(32, band);
		Bounds b = bounds(sx, sy, tx, ty, m);
		return search(new Grid(data), -1, sx, sy, tx, ty, 0, b, cap(sx, sy, tx, ty, m), false, true, 2, 2, null, cancel);
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
	private static Corridor corridor(SparsePath p, int radius, int targetRadius, int sparseBand) {
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
		return new Corridor(p.x, p.y, p.n, new Bounds(minX - band, minY - band, maxX + band, maxY + band), c, band);
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
		if (data.mode == MODE_PHASE) return fp == null || projectState(raw, fp, start, data.phaseX, data.phaseY, sx, sy, tx, ty, d, reverse) == 1;
		return fp == null || projectState(raw, fp, start, sx, sy, tx, ty, d, reverse) == 1;
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
	private static boolean dstBlocked(Grid data, int x, int y, int dir, boolean reverse) {
		if (pass(data, x, y, dir) != 0) return false;
		return !reverse || data.fp == null || pass(data, x, y, revDir(dir)) == 0;
	}
	private static void addStarts(Heap q, DenseBest dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int start, boolean fast, int dirStep, boolean db) {
		if (start >= 0) {
			addStart(q, dense, best, nodes, sx, sy, tx, ty, turnBias, snapDir(start, 1), fast, dirStep, db);
			return;
		}
		for (int i = 0; i < DX.length; i++) addStart(q, dense, best, nodes, sx, sy, tx, ty, turnBias, i, fast, dirStep, db);
	}
	private static void addStartsBase(SimpleHeap q, DenseCost dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int start, boolean fast, int dirStep, boolean db) {
		if (start >= 0) {
			addStartBase(q, dense, best, nodes, sx, sy, tx, ty, turnBias, snapDir(start, 1), fast, dirStep, db);
			return;
		}
		for (int i = 0; i < DX.length; i++) addStartBase(q, dense, best, nodes, sx, sy, tx, ty, turnBias, i, fast, dirStep, db);
	}
	private static void addStartsBucket(BucketHeap q, DenseCost dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int start, boolean fast, int dirStep, boolean db, int tieG) {
		if (start >= 0) {
			addStartBucket(q, dense, best, nodes, sx, sy, tx, ty, turnBias, snapDir(start, 1), fast, dirStep, db, tieG);
			return;
		}
		for (int i = 0; i < DX.length; i++) addStartBucket(q, dense, best, nodes, sx, sy, tx, ty, turnBias, i, fast, dirStep, db, tieG);
	}
	private static void addStart(Heap q, DenseBest dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int dir, boolean fast, int dirStep, boolean db) {
		int hh = h(sx, sy, tx, ty, dir, turnBias);
		int n = nodes.add(sx, sy, dir, 0, 0, wh(hh, fast), -1);
		q.add(n);
		if (dir % dirStep != 0) return;
		if (db) dense.put(sx, sy, dir, n);
		else best.put(state(sx, sy, dir), 0);
	}
	private static void addStartBase(SimpleHeap q, DenseCost dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int dir, boolean fast, int dirStep, boolean db) {
		int hh = h(sx, sy, tx, ty, dir, turnBias);
		int n = nodes.add(sx, sy, dir, 0, 0, wh(hh, fast), -1);
		q.add(n);
		if (dir % dirStep != 0) return;
		if (db) dense.put(sx, sy, dir, 0);
		else best.put(state(sx, sy, dir), 0);
	}
	private static void addStartBucket(BucketHeap q, DenseCost dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int dir, boolean fast, int dirStep, boolean db, int tieG) {
		int hh = h(sx, sy, tx, ty, dir, turnBias);
		int n = nodes.add(sx, sy, dir, 0, 0, wh(hh, fast), -1);
		q.add(n, tieG);
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
	private static int projectState(Grid data, Footprint fp, int start, int phaseX, int phaseY, int sx, int sy, int tx, int ty, int d, boolean reverse) {
		int o = start >= 0 ? start : d;
		int ax = sx * TS + phaseX;
		int ay = sy * TS + phaseY;
		int bx = tx * TS + phaseX;
		int by = ty * TS + phaseY;
		int p = projectPath(data, fp, ax, ay, o, bx, by, d);
		if (p == 1 || !reverse) return p;
		int r = projectPath(data, fp, ax, ay, o, bx, by, rev(d));
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
			int d = snapLine(p.x[i] - p.x[i - 1], p.y[i] - p.y[i - 1]);
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
			if (d < 0) d = snapLine(r.x[i] - r.x[i - 1], r.y[i] - r.y[i - 1]);
			if (pd >= 0 && d != pd) t++;
			pd = d;
		}
		return t;
	}
	private static String status(int s) {
		if (s == ChartPlotterRoute.PENDING) return "pending";
		if (s == ChartPlotterRoute.OK) return "ok";
		if (s == ChartPlotterRoute.UNCHARTED) return "uncharted";
		if (s == ChartPlotterRoute.NO_ROUTE) return "no-route";
		if (s == ChartPlotterRoute.COMPLEX) return "complex";
		if (s == ChartPlotterRoute.BLOCKED) return "blocked";
		return "unknown";
	}
	private static int tieG(int exp) {return exp == EXP_TIE_G_16_BASE || exp == EXP_MOVE_DST_KEY || exp == EXP_MAP_DOM || exp == EXP_POINT_FP || exp == EXP_PHASE_FP ? BUCKET_TIE_16 : exp == EXP_TIE_G_8_BASE ? BUCKET_TIE_8 : 0;}
	private static boolean bucketSentinel(int exp) {return exp == EXP_TIE_G_16_BASE || exp == EXP_TIE_G_8_BASE || exp == EXP_MOVE_DST_KEY || exp == EXP_MAP_DOM || exp == EXP_POINT_FP || exp == EXP_PHASE_FP;}
	private static int bucketBase(int exp, int sx, int sy, int tx, int ty, boolean fast) {return bucketSentinel(exp) && !fast ? h(sx, sy, tx, ty) : 0;}
	private static boolean expMoveDst(int exp) {return exp == EXP_MOVE_DST_KEY;}
	private static boolean expMapDom(int exp) {return exp == EXP_MAP_DOM || exp == EXP_POINT_FP || exp == EXP_PHASE_FP;}
	private static int experimentMode(int mode) {return mode == MODE_TILE ? MODE_PHASE : mode;}
	private static String expName(int exp) {return exp == EXP_TIE_G_16_BASE ? "tie-g16-base" : exp == EXP_TIE_G_8_BASE ? "tie-g8-base" : exp == EXP_MOVE_DST_KEY ? "move-dstkey" : exp == EXP_MAP_DOM ? "map-dom" : exp == EXP_POINT_FP ? "point-fp" : exp == EXP_PHASE_FP ? "phase-fp" : "none";}
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
	private static int directSmoothDir(int sx, int sy, int tx, int ty, int dirStep) {
		int dir = dir(tx - sx, ty - sy);
		if (dir < 0) return -1;
		return orient(snapDir(OR[dir], dirStep), dir);
	}
	private static int snapLine(int dx, int dy) {
		if (dx == 0 && dy == 0) return 0;
		int best = 0;
		long bv = Long.MIN_VALUE;
		for (int i = 0; i < DX.length; i += 1) {
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
		final int radius;
		final LongIntMap cache;
		final LongIntMap dirCache;
		final Footprint fp;
		final int mode;
		final int phaseX;
		final int phaseY;
		byte[] cached;
		byte[] cachedDirs;
		byte[] raw;
		int minX;
		int minY;
		int width;
		int height;
		int stride;
		int rawMinX;
		int rawMinY;
		int rawWidth;
		int rawHeight;
		int cx;
		int cy;
		ChartPlotterCollisionCache.Chunk c;
		boolean have;
		private Grid(ChartPlotterCollisionData data) {
			this(data, 0, null, null, null, MODE_BASE, TS / 2, TS / 2);
		}
		private Grid(ChartPlotterCollisionData data, int radius, LongIntMap cache, LongIntMap dirCache, Footprint fp, int mode, int phaseX, int phaseY) {
			this.data = data;
			this.radius = radius;
			this.cache = cache;
			this.dirCache = dirCache;
			this.fp = fp;
			this.mode = mode;
			this.phaseX = phaseX;
			this.phaseY = phaseY;
		}
		static Grid lazy(ChartPlotterCollisionData data, Footprint fp, int radius, int mode, int phaseX, int phaseY) {
			return new Grid(data, radius, new LongIntMap(16), new LongIntMap(16), fp, mode, phaseX, phaseY);
		}
		void cache(Bounds b) {
			cache(b, DENSE_MAX);
		}
		void cache(Bounds b, int max) {
			if (fp == null) return;
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			height = b.maxY - b.minY + 1;
			long area = (long) width * height;
			long dirs = area * OR.length;
			if (area <= 0 || dirs <= 0) return;
			int r = radius + 2;
			rawMinX = minX - r;
			rawMinY = minY - r;
			rawWidth = width + r * 2;
			rawHeight = height + r * 2;
			long rawArea = (long) rawWidth * rawHeight;
			if (rawArea > 0 && rawArea <= max) raw = new byte[(int) rawArea];
			if (dirs > max) return;
			stride = (int) area;
			cached = new byte[stride];
			cachedDirs = new byte[(int) dirs];
		}
		int flag(int x, int y) {
			if (radius != 0) {
				int i = index(x, y);
				if (i >= 0) {
					int v = cached[i];
					if (v != 0) {
						return flag((byte) (v - 1));
					}
					byte f = inflated(x, y);
					cached[i] = (byte) (f + 1);
					return flag(f);
				}
				long key = chunk(x, y);
				int v = cache.get(key);
				if (v != LongIntMap.MISS) {
					return flag((byte) v);
				}
				byte f = inflated(x, y);
				cache.put(key, f);
				return flag(f);
			}
			return baseFlag(x, y);
		}
		int flag(int x, int y, int dir) {
			if (dir < 0 || dir >= OR.length) return flag(x, y);
			if (fp != null) {
				int i = index(x, y);
				if (i >= 0) {
					int p = i + dir * stride;
					int v = cachedDirs[p];
					if (v != 0) {
						return flag((byte) (v - 1));
					}
					byte f = stand(x, y, dir);
					cachedDirs[p] = (byte) (f + 1);
					return flag(f);
				}
				long key = state(x, y, dir);
				int v = dirCache.get(key);
				if (v != LongIntMap.MISS) {
					return flag((byte) v);
				}
				byte f = stand(x, y, dir);
				dirCache.put(key, f);
				return flag(f);
			}
			return flag(x, y);
		}
		private int index(int x, int y) {
			if (cached == null) return -1;
			int dx = x - minX;
			int dy = y - minY;
			return dx < 0 || dy < 0 || dx >= width || dy >= height ? -1 : dx + dy * width;
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
			if (mode == MODE_PHASE) return standPhase(x, y, dir);
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
		private byte standPhase(int x, int y, int dir) {
			int c = baseFlag(x, y);
			if (c == ChartPlotterCollisionCache.UNKNOWN) return 1;
			if (blocker(c)) return 2;
			boolean unknown = false;
			for (int i = 0; i < fp.n; i++) {
				int f = baseFlag(x + Math.floorDiv(phaseX + fp.rx[dir][i], TS), y + Math.floorDiv(phaseY + fp.ry[dir][i], TS));
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
			int i = rawIndex(x, y);
			if (i >= 0) {
				int v = raw[i];
				if (v != 0) {
					return flag((byte) (v - 1));
				}
				byte f = base(x, y);
				raw[i] = (byte) (f + 1);
				return flag(f);
			}
			return rawFlag(x, y);
		}
		private byte base(int x, int y) {
			int f = rawFlag(x, y);
			return f == ChartPlotterCollisionCache.UNKNOWN ? (byte) 1 : blocker(f) ? (byte) 2 : 0;
		}
		private int rawFlag(int x, int y) {
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
		private int rawIndex(int x, int y) {
			if (raw == null) return -1;
			int dx = x - rawMinX;
			int dy = y - rawMinY;
			return dx < 0 || dy < 0 || dx >= rawWidth || dy >= rawHeight ? -1 : dx + dy * rawWidth;
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
	private static final class Bench {
		final String name;
		final int sx;
		final int sy;
		final int tx;
		final int ty;
		final boolean reverse;
		final boolean fast;
		final int mode;
		final int targetRadius;
		final long start = System.nanoTime();
		long preNs;
		long searchNs;
		long routeNs;
		long area;
		int minX;
		int minY;
		int maxX;
		int maxY;
		int cap;
		int band;
		int cells;
		int mask;
		int dirStep;
		int moveMode;
		int queueMode;
		int polls;
		int seen;
		int stale;
		int nearest;
		int made;
		int maxQ;
		int steps;
		int queued;
		int updated;
		int dominated;
		int domCheck;
		int domSkip;
		int boundsSkip;
		int corridorSkip;
		int capSkip;
		int bestSkip;
		int moveHit;
		int moveMiss;
		int movePass;
		int moveBlock;
		int movePreBlock;
		int moveUnknown;
		boolean dirFan;
		boolean denseBest;
		boolean domMin;
		boolean dstBlock;
		String exp = "none";
		boolean printed;
		private Bench(String name, int sx, int sy, int tx, int ty, boolean reverse, boolean fast, int mode, int targetRadius, Corridor c) {
			this.name = name;
			this.sx = sx;
			this.sy = sy;
			this.tx = tx;
			this.ty = ty;
			this.reverse = reverse;
			this.fast = fast;
			this.mode = mode;
			this.targetRadius = targetRadius;
			if (c != null) bounds(c.b, c.cap, c.band, c.cells, c.mask.length);
		}
		void begin(Bounds b, int cap, Corridor c) {
			bounds(b, cap, c == null ? 0 : c.band, c == null ? 0 : c.cells, c == null ? 0 : c.mask.length);
		}
		void search(Bounds b, int cap, int dirStep, boolean dirFan, boolean denseBest, int moveMode, int queueMode, boolean domMin, boolean dstBlock, String exp, Corridor c) {
			this.dirStep = dirStep;
			this.dirFan = dirFan;
			this.denseBest = denseBest;
			this.moveMode = moveMode;
			this.queueMode = queueMode;
			this.domMin = domMin;
			this.dstBlock = dstBlock;
			this.exp = exp;
			bounds(b, cap, c == null ? 0 : c.band, c == null ? 0 : c.cells, c == null ? 0 : c.mask.length);
		}
		void done(ChartPlotterRoute r, long searchStart, int polls, int seen, int stale, int made, int maxQ, int nearest) {
			searchNs = System.nanoTime() - searchStart;
			this.polls = polls;
			this.seen = seen;
			this.stale = stale;
			this.made = made;
			this.maxQ = maxQ;
			this.nearest = nearest;
			done(r);
		}
		void done(ChartPlotterRoute r) {
			if (printed) return;
			printed = true;
			long total = System.nanoTime() - start;
			int near = nearest == Integer.MAX_VALUE ? -1 : nearest;
			System.out.println("bench " + name + " route=" + status(r.status) + " pts=" + r.n + " len=" + routeLength(r) + " turns=" + routeTurns(r) + " totalUs=" + us(total) + " searchUs=" + us(searchNs) + " polls=" + polls + " seen=" + seen + " stale=" + stale + " made=" + made + " qMax=" + maxQ + " near=" + near + " fp=" + modeName(mode) + " best=" + (denseBest ? "dense" : "map") + " move=" + moveName(moveMode) + " dom=" + (domMin ? "min" : "off") + " dst=" + dstBlock + " exp=" + exp + " skipBest=" + bestSkip + " skipDom=" + domSkip + " moveMiss=" + moveMiss + " preBlock=" + movePreBlock);
		}
		private void bounds(Bounds b, int cap, int band, int cells, int mask) {
			minX = b.minX;
			minY = b.minY;
			maxX = b.maxX;
			maxY = b.maxY;
			area = (long) (maxX - minX + 1) * (maxY - minY + 1);
			this.cap = cap;
			this.band = band;
			this.cells = cells;
			this.mask = mask;
		}
		private static long us(long ns) {return ns / 1000;}
		private static String modeName(int mode) {return mode == MODE_PHASE ? "phase" : mode == MODE_TILE ? "tile" : "base";}
		private static String moveName(int mode) {
			if (mode == MC_DENSE) return "dense";
			if (mode == MC_SPARSE) return "sparse";
			return "off";
		}
	}
	private static final class SparseDebug {
		final long start = System.nanoTime();
		final String label;
		private SparseDebug(int sx, int sy, int tx, int ty, int nodes, String label) {
			this.label = label;
			System.out.println("sparse " + prefix() + "from=" + sx + "," + sy + " to=" + tx + "," + ty + " nodes=" + nodes);
		}
		void sparseCandidate(int total, SparsePath p, int score) {
			System.out.println("sparse " + prefix() + "best=1/" + total + " nodes=" + p.n + " len=" + p.cost + " turns=" + sparseTurns(p) + " score=" + score + " ms=" + ms());
		}
		void candidate(int i, int total, ChartPlotterRoute r, int score) {
			if (r.status != ChartPlotterRoute.PENDING && r.status != ChartPlotterRoute.OK) return;
			System.out.println("sparse " + prefix() + "selected=" + i + "/" + total + " route=" + status(r.status) + " pts=" + r.n + " len=" + routeLength(r) + " turns=" + routeTurns(r) + " score=" + score + " ms=" + ms());
		}
		void result(String mode, ChartPlotterRoute r) {
			System.out.println("sparse " + prefix() + "result=" + mode + " route=" + status(r.status) + " pts=" + r.n + " ms=" + ms());
		}
		String bench(String mode, int candidate, int total, int band) {return mode + " " + prefix() + "cand=" + candidate + "/" + total + " band=" + band;}
		private String prefix() {return label.isEmpty() ? "" : label + " ";}
		private long ms() {return (System.nanoTime() - start) / 1000000;}
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
		int cells;
		private Corridor(int[] x, int[] y, int n, Bounds b, int cap, int band) {
			this.x = x;
			this.y = y;
			this.n = n;
			this.b = b;
			this.cap = cap;
			this.band = band;
			width = b.maxX - b.minX + 1;
			mask = new byte[width * (b.maxY - b.minY + 1)];
			fill();
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
		final Nodes ba = new Nodes(1 << 15);
		final SimpleHeap bq = new SimpleHeap(ba, 1 << 15);
		final BucketHeap bucket = new BucketHeap(ba, 1 << 15);
		final LongIntMap bg = new LongIntMap(1 << 15);
		final DenseCost bbest = new DenseCost();
		final BaseMoveCache bmoves = new BaseMoveCache();
		final DomCost dom = new DomCost();
		final int[] tileDelta = new int[DX.length];
		final int[] bestDelta = new int[DX.length];
		final int[] moveDelta = new int[DX.length];
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
		void clearBase() {
			ba.clear();
			bq.clear();
			bucket.clear();
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
	private static final class DenseCost {
		int[] v;
		int[] m1;
		int[] m2;
		byte[] md;
		int minX;
		int minY;
		int width;
		int area;
		int step;
		boolean dom;
		boolean on;
		void reset(Bounds b, int dirStep) {
			reset(b, dirStep, false);
		}
		void reset(Bounds b, int dirStep, boolean dom) {
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long cells = (long) width * height;
			long n = cells * (DX.length / dirStep);
			if (n <= 0 || n > EDGE_MAX) {
				on = false;
				this.dom = false;
				return;
			}
			area = (int) cells;
			step = dirStep;
			if (v == null || v.length < n) v = new int[(int) n];
			Arrays.fill(v, 0, (int) n, LongIntMap.MISS);
			if (dom) {
				if (m1 == null || m1.length < area) {
					m1 = new int[area];
					m2 = new int[area];
					md = new byte[area];
				}
				Arrays.fill(m1, 0, area, Integer.MAX_VALUE);
				Arrays.fill(m2, 0, area, Integer.MAX_VALUE);
				Arrays.fill(md, 0, area, (byte) -1);
				this.dom = true;
			} else {
				this.dom = false;
			}
			on = true;
		}
		int get(int x, int y, int dir) {
			return v[x - minX + (y - minY) * width + dir / step * area];
		}
		void put(int x, int y, int dir, int val) {
			int pos = x - minX + (y - minY) * width;
			v[pos + dir / step * area] = val;
			if (dom) domPut(pos, dir / step, val);
		}
		boolean dominated(int pos, int dir, int g, int turn) {
			int lim = g - turn;
			if (lim < 0) return false;
			int d = dir / step;
			int m = md[pos] == d ? m2[pos] : m1[pos];
			return m <= lim;
		}
		void domPut(int pos, int dir, int val) {
			int d = md[pos];
			if (d == dir) {
				m1[pos] = val;
				return;
			}
			int m = m1[pos];
			if (val < m) {
				m2[pos] = m;
				m1[pos] = val;
				md[pos] = (byte) dir;
				return;
			}
			if (val < m2[pos]) m2[pos] = val;
		}
	}
	private static final class DomCost {
		int[] m1;
		int[] m2;
		byte[] md;
		int step;
		void reset(Bounds b, int dirStep) {
			int width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			int area = width * height;
			step = dirStep;
			if (m1 == null || m1.length < area) {
				m1 = new int[area];
				m2 = new int[area];
				md = new byte[area];
			}
			Arrays.fill(m1, 0, area, Integer.MAX_VALUE);
			Arrays.fill(m2, 0, area, Integer.MAX_VALUE);
			Arrays.fill(md, 0, area, (byte) -1);
		}
		boolean dominated(int pos, int dir, int g, int turn) {
			int lim = g - turn;
			if (lim < 0) return false;
			int d = dir / step;
			int m = md[pos] == d ? m2[pos] : m1[pos];
			return m <= lim;
		}
		void put(int pos, int dir, int val) {
			int d = dir / step;
			int old = md[pos];
			if (old == d) {
				m1[pos] = val;
				return;
			}
			int m = m1[pos];
			if (val < m) {
				m2[pos] = m;
				m1[pos] = val;
				md[pos] = (byte) d;
				return;
			}
			if (val < m2[pos]) m2[pos] = val;
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
			if (n <= 0 || n > DENSE_MAX) {
				on = false;
				return;
			}
			area = (int) cells;
			step = dirStep;
			if (v == null || v.length < n) v = new int[(int) n];
			Arrays.fill(v, 0, (int) n, 0);
			on = true;
		}
		int node(int x, int y, int dir) {
			return v[x - minX + (y - minY) * width + dir / step * area] - 1;
		}
		void put(int x, int y, int dir, int node) {
			v[x - minX + (y - minY) * width + dir / step * area] = node + 1;
		}
	}
	private static final class BaseMoveCache {
		byte[] v;
		LongIntMap sparse;
		int minX;
		int minY;
		int width;
		int area;
		int step;
		int mode;
		boolean dstKey;
		boolean on;
		void reset(Bounds b, int dirStep) {
			reset(b, dirStep, false);
		}
		void reset(Bounds b, int dirStep, boolean dstKey) {
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long cells = (long) width * height;
			long n = cells * (DX.length / dirStep);
			step = dirStep;
			this.dstKey = dstKey;
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
			if (mode == MC_SPARSE) return sparse.get(key(x, y, odir, dir));
			int p = v[x - minX + (y - minY) * width + dir / step * area];
			return p == 0 ? LongIntMap.MISS : p - 2;
		}
		void put(int x, int y, int odir, int dir, int p) {
			if (mode == MC_SPARSE) {
				if (sparse.n < SPARSE_MOVE_MAX) sparse.put(key(x, y, odir, dir), p);
				return;
			}
			v[x - minX + (y - minY) * width + dir / step * area] = (byte) (p + 2);
		}
		private long key(int x, int y, int odir, int dir) {return dstKey ? state(x + DX[dir], y + DY[dir], dir) : moveKey(x, y, odir, dir);}
		private static long moveKey(int x, int y, int odir, int dir) {return ((long) x & 0xfffffL) << 44 | ((long) y & 0xfffffL) << 8 | (long) odir << 4 | dir;}
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
			if (n <= DENSE_MAX) {
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
		int get(int x, int y, int dir) {
			if (mode == MC_SPARSE) return sparse.get(state(x, y, dir));
			int p = v[x - minX + (y - minY) * width + dir / step * area];
			return p == 0 ? LongIntMap.MISS : p - 2;
		}
		void put(int x, int y, int dir, int p) {
			if (mode == MC_SPARSE) {
				if (sparse.n < SPARSE_MOVE_MAX) sparse.put(state(x, y, dir), p);
				return;
			}
			v[x - minX + (y - minY) * width + dir / step * area] = (byte) (p + 2);
		}
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
	private static final class SimpleHeap {
		final Nodes nodes;
		int[] q;
		int n;
		private SimpleHeap(Nodes nodes, int size) {
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
	private static final class BucketHeap {
		final Nodes nodes;
		int[] head = new int[1 << 16];
		int[] next;
		int[] used = new int[1 << 12];
		byte[] mark = new byte[1 << 16];
		int n;
		int usedN;
		int min;
		int base;
		boolean sentinel;
		private BucketHeap(Nodes nodes, int size) {
			this.nodes = nodes;
			next = new int[size];
		}
		boolean hasNext() {return n != 0;}
		void clear() {
			for (int i = 0; i < usedN; i++) {
				head[used[i]] = 0;
				if (!sentinel) mark[used[i]] = 0;
			}
			usedN = 0;
			n = 0;
			min = 0;
		}
		void add(int v, int tieG) {
			int f = bucket(v, tieG);
			if (f >= head.length) growHead(f);
			if (v >= next.length) next = Arrays.copyOf(next, Math.max(v + 1, next.length << 1));
			int h = head[f];
			if (sentinel) {
				if (h == 0) use(f);
				if (h <= 0 && (n == 0 || f < min)) min = f;
				next[v] = h > 0 ? h - 1 : -1;
			} else {
				if (mark[f] == 0) {
					use(f);
					mark[f] = 1;
				}
				if (h == 0 && (n == 0 || f < min)) min = f;
				next[v] = h - 1;
			}
			head[f] = v + 1;
			n++;
		}
		int poll() {
			while (head[min] <= 0) min++;
			int v = head[min] - 1;
			head[min] = sentinel && next[v] < 0 ? -1 : next[v] + 1;
			n--;
			return v;
		}
		private void use(int f) {
			if (usedN == used.length) used = Arrays.copyOf(used, used.length << 1);
			used[usedN++] = f;
		}
		private void growHead(int f) {
			int c = head.length;
			while (c <= f) c <<= 1;
			head = Arrays.copyOf(head, c);
			mark = Arrays.copyOf(mark, c);
		}
		private int bucket(int v, int tieG) {
			int f = nodes.f[v] - base;
			if (f < 0) f = 0;
			if (tieG <= 0) return f;
			int g = nodes.g[v] / 64;
			if (g >= tieG) g = tieG - 1;
			return f * tieG + tieG - 1 - g;
		}
	}
	private static final class Heap {
		final Nodes nodes;
		int[] q;
		int[] pos;
		int n;
		private Heap(Nodes nodes, int size) {
			this.nodes = nodes;
			q = new int[size];
			pos = new int[size];
		}
		boolean hasNext() {return n != 0;}
		void clear() {
			for (int i = 0; i < n; i++) pos[q[i]] = 0;
			n = 0;
		}
		void add(int v) {
			if (n == q.length) q = Arrays.copyOf(q, q.length << 1);
			if (v >= pos.length) pos = Arrays.copyOf(pos, Math.max(v + 1, pos.length << 1));
			q[n] = v;
			up(n++);
		}
		boolean update(int v, int gg, int dist, int ff, int pp) {
			if (v >= pos.length) return false;
			int p = pos[v] - 1;
			if (p < 0) return false;
			nodes.g[v] = gg;
			nodes.d[v] = dist;
			nodes.f[v] = ff;
			nodes.prev[v] = pp;
			up(p);
			return true;
		}
		int poll() {
			int v = q[0];
			pos[v] = 0;
			int r = q[--n];
			if (n > 0) {
				q[0] = r;
				pos[r] = 1;
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
				pos[pv] = i + 1;
				i = p;
			}
			q[i] = v;
			pos[v] = i + 1;
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
				pos[q[i]] = i + 1;
				i = c;
			}
			q[i] = v;
			pos[v] = i + 1;
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
