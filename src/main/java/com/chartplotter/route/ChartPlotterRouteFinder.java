package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.route.ChartPlotterRouteWork.BaseMoveCache;
import com.chartplotter.route.ChartPlotterRouteWork.BucketHeap;
import com.chartplotter.route.ChartPlotterRouteWork.CompactCost;
import com.chartplotter.route.ChartPlotterSparseRouteFinder.Corridor;
import com.chartplotter.route.ChartPlotterRouteWork.DenseBest;
import com.chartplotter.route.ChartPlotterRouteWork.DenseCost;
import com.chartplotter.route.ChartPlotterRouteWork.DomCost;
import com.chartplotter.route.ChartPlotterRouteWork.Heap;
import com.chartplotter.route.ChartPlotterRouteWork.LongIntMap;
import com.chartplotter.route.ChartPlotterRouteWork.MoveCache;
import com.chartplotter.route.ChartPlotterRouteWork.Nodes;
import com.chartplotter.route.ChartPlotterSparseRouteFinder.Path;
import com.chartplotter.route.ChartPlotterRouteWork.Work;
import com.chartplotter.util.ChartPlotterMath;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
import static com.chartplotter.util.ChartPlotterMath.rotateX;
import static com.chartplotter.util.ChartPlotterMath.rotateY;
public final class ChartPlotterRouteFinder {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int MAX = 9000000;
	private static final int LAZY_MAX = 1 << 27;
	private static final int STEP = 32;
	private static final int MODE_BASE = 0;
	private static final int MODE_TILE = 1;
	private static final int MC_DENSE = 1;
	private static final int MC_SPARSE = 2;
	private static final int MC_COMPACT = 3;
	private static final int PRUNE = 4;
	private static final int[] DX = ChartPlotterRouteMoves.DX;
	private static final int[] DY = ChartPlotterRouteMoves.DY;
	private static final int[] COST = ChartPlotterRouteMoves.COST;
	private static final int[] OR = ChartPlotterRouteMoves.OR;
	private static final int[][] HX = hitOffsets(true);
	private static final int[][] HY = hitOffsets(false);
	private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);
	private ChartPlotterRouteFinder() {}
	public static ChartPlotterRoute find(ChartPlotterCollisionData data, WorldEntityConfig wc, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, int weight, int targetRadius, ChartPlotterSparseNodes.Snapshot sparse, int sparseBand, BooleanSupplier cancel) {
		sparseBand = Math.max(20, Math.min(200, sparseBand));
		ChartPlotterRouteGrid.Footprint fp = wc == null ? null : new ChartPlotterRouteGrid.Footprint(wc);
		Path sp = ChartPlotterSparseRouteFinder.path(data, sparse, sx, sy, tx, ty, targetRadius, sparseBand, cancel);
		if (sp != null && sp.pending) {
			return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, weight);
		}
		if (sp != null) {
			ChartPlotterRoute r = sparseRoute(data, fp, start, sx, sy, tx, ty, turnBias, reverse, weight, fp == null ? MODE_BASE : MODE_TILE, targetRadius, sparseBand, sp, cancel);
			if (r.status == ChartPlotterRoute.PENDING || r.status == ChartPlotterRoute.OK) return r;
		}
		return ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, weight);
	}
	private static ChartPlotterRoute search(ChartPlotterRouteGrid data, int sx, int sy, int tx, int ty, ChartPlotterRouteBounds b, int cap, BooleanSupplier cancel) {
		Work w = WORK.get();
		w.clear();
		Nodes nodes = w.a;
		Heap q = w.aq;
		LongIntMap best = w.ag;
		MoveCache moves = w.moves;
		moves.reset(b);
		data.cache(b);
		DenseBest dense = w.best;
		dense.reset(b);
		boolean db = dense.on;
		if (!db) best.clear();
		addStarts(q, dense, best, nodes, sx, sy, tx, ty, db);
		boolean capped = false;
		int seen = 0;
		int minX = b.minX;
		int minY = b.minY;
		int maxX = b.maxX;
		int maxY = b.maxY;
		while (q.hasNext()) {
			if (cancel.getAsBoolean()) return ChartPlotterRoute.pending(sx, sy, tx, ty, 0, 250);
			int a = q.poll();
			int ax = nodes.x[a];
			int ay = nodes.y[a];
			int ad = nodes.dir[a];
			int ag = nodes.g[a];
			int dist = nodes.d[a];
			if (nodes.prev[a] >= 0) {
				if (db) {
					if (dense.node(ax, ay, ad) != a) continue;
				} else {
					int bg = best.get(state(ax, ay, ad));
					if (bg == LongIntMap.MISS || ag != bg) continue;
				}
			}
			int td = dist(ax, ay, tx, ty);
			if (td <= 2) return route(data, -1, nodes, a, sx, sy, tx, ty, 0, false, 250, 2);
			if (++seen > MAX) {
				capped = true;
				break;
			}
			for (int i = 0; i < DX.length; i += 2) {
				int nx = ax + DX[i];
				int ny = ay + DY[i];
				if (nx < minX || ny < minY || nx > maxX || ny > maxY) continue;
				int step = COST[i];
				int nd = dist + step;
				if (nd > cap) continue;
				int ng = ag + step;
				long key = 0;
				int oldNode = -1;
				if (db) {
					oldNode = dense.node(nx, ny, i);
					if (oldNode >= 0 && nodes.g[oldNode] <= ng) continue;
				} else {
					key = state(nx, ny, i);
					int old = best.get(key);
					if (old != LongIntMap.MISS && old <= ng) continue;
				}
				int p = LongIntMap.MISS;
				if (moves.on) {
					p = moves.get(ax, ay, i);
				}
				if (p == LongIntMap.MISS) {
					p = move(data, ax, ay, nx, ny, i, false);
					if (moves.on) moves.put(ax, ay, i, p);
				}
				if (p != 1) continue;
				int nf = ng + wh(h(nx, ny, tx, ty), 250);
				if (db) {
					if (oldNode < 0 || !q.update(oldNode, ng, nd, nf, a)) {
						int nn = nodes.add(nx, ny, i, ng, nd, nf, a);
						dense.put(nx, ny, i, nn);
						q.add(nn);
					}
				} else {
					best.put(key, ng);
					q.add(nodes.add(nx, ny, i, ng, nd, nf, a));
				}
			}
		}
		return capped ? ChartPlotterRoute.complex(sx, sy, tx, ty, 0, 250) : ChartPlotterRoute.none(sx, sy, tx, ty, 0, 250);
	}
	private static ChartPlotterRoute searchBucket(ChartPlotterRouteGrid data, int start, int sx, int sy, int tx, int ty, int turnBias, ChartPlotterRouteBounds b, int cap, boolean reverse, int weight, int targetRadius, Corridor corridor, BooleanSupplier cancel) {
		Work w = WORK.get();
		BucketHeap q = w.bucket;
		q.base = weight > 100 ? 0 : h(sx, sy, tx, ty);
		w.clearBase();
		Nodes nodes = w.ba;
		LongIntMap best = w.bg;
		BaseMoveCache moves = w.bmoves;
		moves.reset(b, corridor);
		data.cache(b, LAZY_MAX);
		DenseCost dense = w.bbest;
		int turn = turn(turnBias);
		dense.reset(b, turn > 0);
		boolean db = dense.on;
		CompactCost compact = w.cbest;
		boolean cb = !db && moves.mode == MC_COMPACT;
		if (cb) compact.reset(corridor, turn > 0, moves.index);
		else compact.dom = false;
		if (!db && !cb) best.clear();
		DomCost dom = !db && !cb && turn > 0 ? w.dom : null;
		if (dom != null) dom.reset(b);
		boolean dstBlock = moves.mode == MC_SPARSE;
		addStartsBucket(q, dense, compact, best, nodes, sx, sy, tx, ty, turnBias, start, weight, db, cb);
		boolean capped = false;
		int seen = 0;
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
		boolean moveCompact = moves.mode == MC_COMPACT;
		for (int i = 0; i < DX.length; i++) {
			tileDelta[i] = DX[i] + DY[i] * width;
			if (db) bestDelta[i] = tileDelta[i] + i * dense.area;
			else if (cb) bestDelta[i] = i * compact.area;
			if (moveDense) moveDelta[i] = i * moves.area;
		}
		boolean domFirst = dense.dom || compact.dom;
		if (dom != null) domFirst = true;
		while (q.hasNext()) {
			if (cancel.getAsBoolean()) return ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, weight);
			int a = q.poll();
			int ax = nodes.x[a];
			int ay = nodes.y[a];
			int ad = nodes.dir[a];
			int ag = nodes.g[a];
			int dist = nodes.d[a];
			int pos = ax - minX + (ay - minY) * width;
			if (nodes.prev[a] >= 0) {
				int bg = db ? dense.v[pos + ad * dense.area] : cb ? compact.v[moves.index[pos] - 1 + bestDelta[ad]] : best.get(state(ax, ay, ad));
				if (bg == LongIntMap.MISS || ag != bg) continue;
			}
			int td = dist(ax, ay, tx, ty);
			if (td <= targetRadius) return route(data, start, nodes, a, sx, sy, tx, ty, turnBias, reverse, weight, 1);
			if (db && turn > 0) {
				if (dense.dominated(pos, ad, ag, turn)) continue;
			} else if (cb && turn > 0) {
				if (compact.dominated(pos, ad, ag, turn)) continue;
			} else if (dom != null) {
				if (dom.dominated(pos, ad, ag, turn)) continue;
			}
			if (++seen > MAX) {
				capped = true;
				break;
			}
			for (int i = 0; i < DX.length; i++) {
				if (circDist(ad, i) > PRUNE) continue;
				int nx = ax + DX[i];
				int ny = ay + DY[i];
				int np;
				int cp = 0;
				if (nx < minX || ny < minY || nx > maxX || ny > maxY) continue;
				np = pos + tileDelta[i];
				if (moveCompact) {
					cp = moves.index[np] - 1;
					if (cp < 0) {
						continue;
					}
				} else {
					if (corridorMask != null && corridorMask[np] == 0) continue;
				}
				int step = COST[i];
				int nd = dist + step;
				if (nd > cap) continue;
				int ng = ag + step + (ad != i ? turn : 0);
				long key = 0;
				int bestPos = 0;
				if (domFirst) {
					boolean dominated = db ? dense.dominated(np, i, ng, turn) : cb ? compact.dominatedAtIndex(cp, i, ng, turn) : dom != null && dom.dominated(np, i, ng, turn);
					if (dominated) continue;
				}
				if (db) {
					bestPos = pos + bestDelta[i];
					int old = dense.v[bestPos];
					if (old != LongIntMap.MISS && old <= ng) continue;
				} else if (cb) {
					bestPos = cp + bestDelta[i];
					int old = compact.v[bestPos];
					if (old != LongIntMap.MISS && old <= ng) continue;
				} else {
					key = state(nx, ny, i);
					int old = best.get(key);
					if (old != LongIntMap.MISS && old <= ng) continue;
				}
				if (!domFirst && db && turn > 0) {
					if (dense.dominated(np, i, ng, turn)) continue;
				}
				int p = LongIntMap.MISS;
				int movePos = 0;
				if (moveDense) {
					movePos = pos + moveDelta[i];
					int v = moves.v[movePos];
					if (v != 0) {
						p = v - 2;
					}
				} else if (moveCompact) {
					movePos = cb ? bestPos : cp + i * moves.area;
					int v = moves.v[movePos];
					if (v != 0) {
						p = v - 2;
					}
				} else if (moves.on) {
					p = moves.get(ax, ay, i);
				}
				if (p == LongIntMap.MISS) {
					if (dstBlock && dstBlocked(data, nx, ny, i, reverse)) {
						p = 0;
					} else {
						p = move(data, ax, ay, nx, ny, i, reverse);
					}
					if (moveDense || moveCompact) moves.v[movePos] = (byte) (p + 2);
					else if (moves.on) moves.put(ax, ay, i, p);
				}
				if (p != 1) continue;
				if (db) {
					dense.v[bestPos] = ng;
					if (dense.dom) dense.domPut(np, i, ng);
				} else if (cb) {
					compact.v[bestPos] = ng;
					if (compact.dom) compact.domPutAt(cp, i, ng);
				} else {
					best.put(key, ng);
					if (dom != null) dom.put(np, i, ng);
				}
				int hh = h(nx, ny, tx, ty, i, turnBias);
				q.add(nodes.add(nx, ny, i, ng, nd, ng + wh(hh, weight), a));
			}
		}
		return capped ? ChartPlotterRoute.complex(sx, sy, tx, ty, turnBias, weight) : ChartPlotterRoute.none(sx, sy, tx, ty, turnBias, weight);
	}
	private static ChartPlotterRoute sparseRoute(ChartPlotterCollisionData raw, ChartPlotterRouteGrid.Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, int weight, int mode, int targetRadius, int sparseBand, Path p, BooleanSupplier cancel) {
		p = ChartPlotterSparseRouteFinder.simplify(raw, p);
		Corridor c = ChartPlotterSparseRouteFinder.corridor(p, sparseBand);
		turnBias = Math.max(0, Math.min(10, turnBias));
		ChartPlotterRouteGrid data = fp == null ? new ChartPlotterRouteGrid(raw) : ChartPlotterRouteGrid.lazy(raw, fp, radius(fp), mode);
		int sf = data.flag(sx, sy);
		int tf = targetFlag(data, tx, ty, targetRadius);
		if (sf == ChartPlotterCollisionCache.UNKNOWN || tf == ChartPlotterCollisionCache.UNKNOWN) return ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias, weight);
		if (blocker(sf) || blocker(tf)) return ChartPlotterRoute.blocked(sx, sy, tx, ty, turnBias, weight);
		return searchBucket(data, start, sx, sy, tx, ty, turnBias, c.b, c.cap, reverse, weight, targetRadius, c, cancel).sparse(p.x, p.y, p.n, c.band);
	}
	static ChartPlotterRoute localConnect(ChartPlotterCollisionData data, int sx, int sy, int tx, int ty, int band, BooleanSupplier cancel) {
		int m = Math.max(32, band);
		ChartPlotterRouteBounds b = bounds(sx, sy, tx, ty, m);
		return search(new ChartPlotterRouteGrid(data), sx, sy, tx, ty, b, cap(sx, sy, tx, ty, m), cancel);
	}
	private static ChartPlotterRoute route(ChartPlotterRouteGrid data, int start, Nodes nodes, int end, int sx, int sy, int tx, int ty, int turnBias, boolean reverse, int weight, int dirStep) {
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
		return smooth(data, start, sx, sy, tx, ty, x, y, n, turnBias, reverse, weight, dirStep);
	}
	private static ChartPlotterRoute smooth(ChartPlotterRouteGrid data, int start, int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, boolean reverse, int weight, int dirStep) {
		if (n < 3) return ChartPlotterRoute.ok(sx, sy, tx, ty, x, y, n, turnBias, weight);
		int[] ox = new int[n];
		int[] oy = new int[n];
		int on = 0;
		int i = 0;
		int o = start;
		ChartPlotterRouteGrid.Footprint fp = data.fp;
		ChartPlotterRouteGrid raw = fp == null || data.data == null ? data : new ChartPlotterRouteGrid(data.data);
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
		return ChartPlotterRoute.ok(sx, sy, tx, ty, ox, oy, on, turnBias, weight);
	}
	private static boolean smoothClear(ChartPlotterRouteGrid data, ChartPlotterRouteGrid raw, ChartPlotterRouteGrid.Footprint fp, int start, int sx, int sy, int tx, int ty, int d, boolean reverse) {
		if (!clear(data, sx, sy, tx, ty, d, reverse)) return false;
		return fp == null || projectState(raw, fp, start, sx, sy, tx, ty, d, reverse) == 1;
	}
	private static int move(ChartPlotterRouteGrid data, int x, int y, int nx, int ny, int ndir, boolean reverse) {
		int p = clearPoint(data, x, y, nx, ny, ndir);
		if (p == 1 || !reverse || data.fp == null) return p;
		int r = clearPoint(data, x, y, nx, ny, revDir(ndir));
		return r == 1 ? 1 : p < 0 || r < 0 ? -1 : 0;
	}
	private static int pass(ChartPlotterRouteGrid data, int nx, int ny, int dir) {
		int f = dir < 0 ? data.flag(nx, ny) : data.flag(nx, ny, dir);
		if (f == ChartPlotterCollisionCache.UNKNOWN) return -1;
		return blocker(f) ? 0 : 1;
	}
	private static boolean dstBlocked(ChartPlotterRouteGrid data, int x, int y, int dir, boolean reverse) {
		if (pass(data, x, y, dir) != 0) return false;
		return !reverse || data.fp == null || pass(data, x, y, revDir(dir)) == 0;
	}
	private static void addStarts(Heap q, DenseBest dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, boolean db) {
		for (int i = 0; i < DX.length; i++) addStart(q, dense, best, nodes, sx, sy, tx, ty, i, db);
	}
	private static void addStartsBucket(BucketHeap q, DenseCost dense, CompactCost compact, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int start, int weight, boolean db, boolean cb) {
		if (start >= 0) {
			addStartBucket(q, dense, compact, best, nodes, sx, sy, tx, ty, turnBias, snapDir(start, 1), weight, db, cb);
			return;
		}
		for (int i = 0; i < DX.length; i++) addStartBucket(q, dense, compact, best, nodes, sx, sy, tx, ty, turnBias, i, weight, db, cb);
	}
	private static void addStart(Heap q, DenseBest dense, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int dir, boolean db) {
		int n = nodes.add(sx, sy, dir, 0, 0, wh(h(sx, sy, tx, ty), 250), -1);
		q.add(n);
		if ((dir & 1) != 0) return;
		if (db) dense.put(sx, sy, dir, n);
		else best.put(state(sx, sy, dir), 0);
	}
	private static void addStartBucket(BucketHeap q, DenseCost dense, CompactCost compact, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int dir, int weight, boolean db, boolean cb) {
		int hh = h(sx, sy, tx, ty, dir, turnBias);
		int n = nodes.add(sx, sy, dir, 0, 0, wh(hh, weight), -1);
		q.add(n);
		if (db) dense.put(sx, sy, dir);
		else if (cb) compact.putZero(sx, sy, dir);
		else best.put(state(sx, sy, dir), 0);
	}
	private static int linePoint(ChartPlotterRouteGrid data, int sx, int sy, int tx, int ty, int dir) {
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
	private static int clearPoint(ChartPlotterRouteGrid data, int sx, int sy, int tx, int ty, int dir) {
		if (dist(sx, sy, tx, ty) <= 1) return linePoint(data, sx, sy, tx, ty, dir);
		if (dir >= 0 && sx + DX[dir] == tx && sy + DY[dir] == ty) return shortPath(data, sx, sy, dir);
		return hitPath(data, center(sx), center(sy), center(tx), center(ty), dir);
	}
	private static int shortPath(ChartPlotterRouteGrid data, int sx, int sy, int dir) {
		int[] x = HX[dir];
		int[] y = HY[dir];
		for (int i = 0; i < x.length; i++) {
			int f = data.flag(sx + x[i], sy + y[i], dir);
			if (f == ChartPlotterCollisionCache.UNKNOWN) return -1;
			if (blocker(f)) return 0;
		}
		return 1;
	}
	private static boolean clear(ChartPlotterRouteGrid data, int sx, int sy, int tx, int ty, int d, boolean reverse) {
		int dir = orientIndex(d);
		if (clearPoint(data, sx, sy, tx, ty, dir) == 1) return true;
		return reverse && data.fp != null && clearPoint(data, sx, sy, tx, ty, revDir(dir)) == 1;
	}
	private static int projectState(ChartPlotterRouteGrid data, ChartPlotterRouteGrid.Footprint fp, int start, int sx, int sy, int tx, int ty, int d, boolean reverse) {
		if (fp == null) return clearPoint(data, sx, sy, tx, ty, orientIndex(d));
		int o = start >= 0 ? start : d;
		int p = projectPath(data, fp, center(sx), center(sy), o, center(tx), center(ty), d);
		if (p == 1 || !reverse) return p;
		int r = projectPath(data, fp, center(sx), center(sy), o, center(tx), center(ty), rev(d));
		return r == 1 ? 1 : p < 0 || r < 0 ? -1 : 0;
	}
	private static int projectPath(ChartPlotterRouteGrid data, ChartPlotterRouteGrid.Footprint fp, int ax, int ay, int ao, int bx, int by, int bo) {
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
	private static int clearBoundsState(ChartPlotterRouteGrid data, ChartPlotterRouteGrid.Footprint fp, int ax, int ay, int ao, int bx, int by, int bo) {
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
	private static int hitFootprint(ChartPlotterRouteGrid data, ChartPlotterRouteGrid.Footprint fp, int ax, int ay, int ao, int bx, int by, int bo) {
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
	private static int hitPath(ChartPlotterRouteGrid data, int ax, int ay, int bx, int by) {
		return hitPath(data, ax, ay, bx, by, -1);
	}
	private static int hitPath(ChartPlotterRouteGrid data, int ax, int ay, int bx, int by, int dir) {
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
	private static boolean blocker(int f) {return (f & ChartPlotterCollisionCache.MOVE) != 0;}
	private static boolean near(int ax, int ay, int bx, int by, int r) {return ChartPlotterMath.chebyshev(ax, ay, bx, by) <= r;}
	private static int targetFlag(ChartPlotterRouteGrid data, int tx, int ty, int r) {
		int f = data.flag(tx, ty);
		if (f != ChartPlotterCollisionCache.UNKNOWN && !blocker(f)) return f;
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
	private static int dist(int ax, int ay, int bx, int by) {return ChartPlotterMath.chebyshev(ax, ay, bx, by);}
	private static int circDist(int a, int b) {int d = Math.abs(a - b); return Math.min(d, DX.length - d);}
	private static int h(int x, int y, int tx, int ty) {
		int dx = Math.abs(tx - x);
		int dy = Math.abs(ty - y);
		int a = Math.max(dx, dy);
		int b = Math.min(dx, dy);
		return 12 * b <= 5 * a ? 10 * a + 2 * b : (535 * a + 354 * b) / 63;
	}
	private static int h(int x, int y, int tx, int ty, int dir, int turnBias) {
		return h(x, y, tx, ty) + turn(turnBias) * minTurns(dir, tx - x, ty - y);
	}
	private static int wh(int h, int weight) {return h * weight / 100;}
	private static int smoothLimit(int turnBias) {return turnBias <= 0 ? 16 : turnBias >= 10 ? 192 : 64;}
	private static int directSmoothDir(int sx, int sy, int tx, int ty, int dirStep) {
		int dir = dir(tx - sx, ty - sy);
		if (dir < 0) return -1;
		return orient(snapDir(OR[dir], dirStep), dir);
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
	private static int turn(int turnBias) {return turnBias <= 0 ? 0 : turnBias >= 10 ? 320 : 110;}
	private static ChartPlotterRouteBounds bounds(int sx, int sy, int tx, int ty, int m) {
		return new ChartPlotterRouteBounds(Math.min(sx, tx) - m, Math.min(sy, ty) - m, Math.max(sx, tx) + m, Math.max(sy, ty) + m);
	}
	private static int snapDir(int angle, int dirStep) {
		int best = 0;
		int bd = Integer.MAX_VALUE;
		for (int i = 0; i < DX.length; i += dirStep) {
			int d = Math.abs(ChartPlotterMath.norm(angle - OR[i]));
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
		return ChartPlotterMath.norm((int) Math.round((270 - d) / 360 * 2048));
	}
	private static int orient(int dir, int fallback) {
		if (dir < 0 || dir >= DX.length) dir = fallback;
		return OR[dir];
	}
	private static int rev(int o) {return ChartPlotterMath.norm(o + 1024);}
	private static int revDir(int d) {return d < 0 ? d : d + 8 & 15;}
	private static int center(int v) {return v * TS + TS / 2;}
	private static int radius(ChartPlotterRouteGrid.Footprint fp) {
		if (fp == null) return 0;
		int r = Math.max(Math.max(Math.abs(fp.minX), Math.abs(fp.maxX)), Math.max(Math.abs(fp.minY), Math.abs(fp.maxY)));
		return Math.max(1, (r + TS - 1) / TS);
	}
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
}
