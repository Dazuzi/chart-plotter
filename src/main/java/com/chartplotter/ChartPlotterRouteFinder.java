package com.chartplotter;
import java.util.Arrays;
import java.util.Map;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
final class ChartPlotterRouteFinder {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int MAX = 160000;
	private static final int STEP = 32;
	private static final int[] DX = {0, 1, 1, 2, 1, 2, 1, 1, 0, -1, -1, -2, -1, -2, -1, -1};
	private static final int[] DY = {1, 2, 1, 1, 0, -1, -1, -2, -1, -2, -1, -1, 0, 1, 1, 2};
	private static final int[] COST = {10, 22, 14, 22, 10, 22, 14, 22, 10, 22, 14, 22, 10, 22, 14, 22};
	private static final int[] OR = {1024, 1152, 1280, 1408, 1536, 1664, 1792, 1920, 0, 128, 256, 384, 512, 640, 768, 896};
	private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);
	private ChartPlotterRouteFinder() {}
	static ChartPlotterRoute find(Map<Long, int[]> data, WorldEntityConfig wc, int start, int sx, int sy, int tx, int ty, int turnBias, boolean bidirectional, boolean reverse) {
		return find(new Grid(data), wc, start, sx, sy, tx, ty, turnBias, bidirectional, reverse);
	}
	static boolean clear(Map<Long, int[]> data, WorldEntityConfig wc, int start, int sx, int sy, int tx, int ty, boolean reverse) {
		int d = dir(tx - sx, ty - sy);
		return d >= 0 && clear(new Grid(data), wc == null ? null : new Footprint(wc), start, sx, sy, tx, ty, orient(d, d), reverse);
	}
	private static ChartPlotterRoute find(Grid data, WorldEntityConfig wc, int start, int sx, int sy, int tx, int ty, int turnBias, boolean bidirectional, boolean reverse) {
		turnBias = Math.max(0, Math.min(10, turnBias));
		Footprint fp = wc == null ? null : new Footprint(wc);
		if (data.flag(sx, sy) == ChartPlotterCollisionCache.UNKNOWN || data.flag(tx, ty) == ChartPlotterCollisionCache.UNKNOWN) return ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias);
		if (blocked(data, sx, sy) || blocked(data, tx, ty)) return ChartPlotterRoute.none(sx, sy, tx, ty, turnBias);
		int end = open(data, fp, tx, ty);
		if (end < 0) return ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias);
		if (end == 0) return ChartPlotterRoute.none(sx, sy, tx, ty, turnBias);
		ChartPlotterRoute direct = direct(data, fp, start, sx, sy, tx, ty, turnBias, reverse);
		if (direct != null) return direct;
		direct = direct2(data, fp, start, sx, sy, tx, ty, turnBias, reverse);
		if (direct != null) return direct;
		int cap = cap(sx, sy, tx, ty, turnBias);
		return (bidirectional ? searchBi(data, fp, start, sx, sy, tx, ty, turnBias, cap, reverse) : search(data, fp, start, sx, sy, tx, ty, turnBias, cap, reverse)).route;
	}
	private static Search search(Grid data, Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, int cap, boolean reverse) {
		Bounds b = bounds(sx, sy, tx, ty, fp);
		Work w = WORK.get();
		w.clearA();
		addStarts(w.aq, w.ag, w.a, sx, sy, tx, ty, turnBias, start);
		boolean unknown = false;
		boolean capped = false;
		int seen = 0;
		while (w.aq.hasNext()) {
			int a = w.aq.poll();
			int bg = w.ag.get(state(w.a.x[a], w.a.y[a], w.a.dir[a]));
			if (bg == LongIntMap.MISS || w.a.g[a] != bg) continue;
			if (w.a.x[a] == tx && w.a.y[a] == ty) return new Search(route(data, fp, start, w.a, a, sx, sy, tx, ty, turnBias, reverse), w.a.d[a]);
			if (++seen > MAX) {
				capped = true;
				break;
			}
			for (int i = 0; i < DX.length; i++) {
				int nx = w.a.x[a] + DX[i];
				int ny = w.a.y[a] + DY[i];
				if (b.outside(nx, ny)) continue;
				int p = move(data, fp, w.a.x[a], w.a.y[a], w.a.dir[a], nx, ny, i, reverse);
				if (p < 0) unknown = true;
				if (p != 1) continue;
				int step = step(i);
				int nd = w.a.d[a] + step;
				if (nd > cap) continue;
				int ng = w.a.g[a] + step + (w.a.dir[a] != i ? turn(turnBias) : 0);
				long key = state(nx, ny, i);
				int old = w.ag.get(key);
				if (old != LongIntMap.MISS && old <= ng) continue;
				int hh = h(nx, ny, tx, ty, i, turnBias);
				w.ag.put(key, ng);
				w.aq.add(w.a.add(nx, ny, i, ng, nd, ng + hh, a));
			}
		}
		return new Search(capped ? ChartPlotterRoute.complex(sx, sy, tx, ty, turnBias) : unknown ? ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias) : ChartPlotterRoute.none(sx, sy, tx, ty, turnBias), 0);
	}
	private static Search searchBi(Grid data, Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, int cap, boolean reverse) {
		Bounds b = bounds(sx, sy, tx, ty, fp);
		Work w = WORK.get();
		w.clearAll();
		addFronts(w.aq, w.ag, w.an, w.a, sx, sy, tx, ty, turnBias, start);
		addEnds(w.bq, w.bg, w.bn, w.b, tx, ty, sx, sy, turnBias, start);
		boolean unknown = false;
		boolean capped = false;
		int seen = 0;
		int best = Integer.MAX_VALUE;
		int mf = -1;
		int mb = -1;
		while (w.aq.hasNext() && w.bq.hasNext()) {
			if (best != Integer.MAX_VALUE && (w.a.f[w.aq.peek()] >= best || w.b.f[w.bq.peek()] >= best)) break;
			if (w.a.f[w.aq.peek()] <= w.b.f[w.bq.peek()]) {
				int a = w.aq.poll();
				long akey = state(w.a.x[a], w.a.y[a], w.a.dir[a]);
				int ag = w.ag.get(akey);
				if (ag == LongIntMap.MISS || w.a.g[a] != ag) continue;
				if (++seen > MAX) {
					capped = true;
					break;
				}
				for (int i = 0; i < DX.length; i++) {
					int nx = w.a.x[a] + DX[i];
					int ny = w.a.y[a] + DY[i];
					if (b.outside(nx, ny)) continue;
					int p = move(data, fp, w.a.x[a], w.a.y[a], w.a.dir[a], nx, ny, i, reverse);
					if (p < 0) unknown = true;
					if (p != 1) continue;
					int step = step(i);
					int nd = w.a.d[a] + step;
					if (nd > cap) continue;
					int ng = w.a.g[a] + step + (w.a.dir[a] != i ? turn(turnBias) : 0);
					long key = state(nx, ny, i);
					int old = w.ag.get(key);
					if (old != LongIntMap.MISS && old <= ng) continue;
					int n = w.a.add(nx, ny, i, ng, nd, ng + h(nx, ny, tx, ty, i, turnBias), a);
					w.ag.put(key, ng);
					w.an.put(key, n);
					w.aq.add(n);
					for (int j = 0; j < DX.length; j++) {
						long rk = state(nx, ny, j);
						int rg = w.bg.get(rk);
						if (rg == LongIntMap.MISS) continue;
						int r = w.bn.get(rk);
						int cost = ng + rg + (i != j ? turn(turnBias) : 0);
						if (r == LongIntMap.MISS || nd + w.b.d[r] > cap || cost >= best) continue;
						best = cost;
						mf = n;
						mb = r;
					}
				}
			} else {
				int a = w.bq.poll();
				long akey = state(w.b.x[a], w.b.y[a], w.b.dir[a]);
				int ag = w.bg.get(akey);
				if (ag == LongIntMap.MISS || w.b.g[a] != ag) continue;
				if (++seen > MAX) {
					capped = true;
					break;
				}
				int px = w.b.x[a] - DX[w.b.dir[a]];
				int py = w.b.y[a] - DY[w.b.dir[a]];
				if (b.outside(px, py)) continue;
				for (int i = 0; i < DX.length; i++) {
					int p = move(data, fp, px, py, i, w.b.x[a], w.b.y[a], w.b.dir[a], reverse);
					if (p < 0) unknown = true;
					if (p != 1) continue;
					int step = step(w.b.dir[a]);
					int nd = w.b.d[a] + step;
					if (nd > cap) continue;
					int ng = w.b.g[a] + step + (i != w.b.dir[a] ? turn(turnBias) : 0);
					long key = state(px, py, i);
					int old = w.bg.get(key);
					if (old != LongIntMap.MISS && old <= ng) continue;
					int n = w.b.add(px, py, i, ng, nd, ng + bh(px, py, sx, sy, start, turnBias), a);
					w.bg.put(key, ng);
					w.bn.put(key, n);
					w.bq.add(n);
					for (int j = 0; j < DX.length; j++) {
						long lk = state(px, py, j);
						int lg = w.ag.get(lk);
						if (lg == LongIntMap.MISS) continue;
						int l = w.an.get(lk);
						int cost = lg + ng + (j != i ? turn(turnBias) : 0);
						if (l == LongIntMap.MISS || w.a.d[l] + nd > cap || cost >= best) continue;
						best = cost;
						mf = l;
						mb = n;
					}
				}
			}
		}
		if (best != Integer.MAX_VALUE) return new Search(route(data, fp, start, w.a, mf, w.b, mb, sx, sy, tx, ty, turnBias, reverse), 0);
		return new Search(capped ? ChartPlotterRoute.complex(sx, sy, tx, ty, turnBias) : unknown ? ChartPlotterRoute.uncharted(sx, sy, tx, ty, turnBias) : ChartPlotterRoute.none(sx, sy, tx, ty, turnBias), 0);
	}
	private static ChartPlotterRoute route(Grid data, Footprint fp, int start, Nodes nodes, int end, int sx, int sy, int tx, int ty, int turnBias, boolean reverse) {
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
		return smooth(data, fp, start, sx, sy, tx, ty, x, y, n, turnBias, reverse);
	}
	private static ChartPlotterRoute route(Grid data, Footprint fp, int start, Nodes fs, int front, Nodes bs, int back, int sx, int sy, int tx, int ty, int turnBias, boolean reverse) {
		int nf = 0;
		for (int a = front; a >= 0; a = fs.prev[a]) nf++;
		int nb = 0;
		for (int a = bs.prev[back]; a >= 0; a = bs.prev[a]) nb++;
		int n = nf + nb;
		int[] x = new int[n];
		int[] y = new int[n];
		int i = nf;
		for (int a = front; a >= 0; a = fs.prev[a]) {
			i--;
			x[i] = fs.x[a];
			y[i] = fs.y[a];
		}
		i = nf;
		for (int a = bs.prev[back]; a >= 0; a = bs.prev[a]) {
			x[i] = bs.x[a];
			y[i++] = bs.y[a];
		}
		return smooth(data, fp, start, sx, sy, tx, ty, x, y, n, turnBias, reverse);
	}
	private static ChartPlotterRoute smooth(Grid data, Footprint fp, int start, int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias, boolean reverse) {
		if (n < 3) return ChartPlotterRoute.ok(sx, sy, tx, ty, x, y, n, turnBias);
		int[] ox = new int[n];
		int[] oy = new int[n];
		int on = 0;
		int i = 0;
		int o = start;
		ox[on] = x[0];
		oy[on++] = y[0];
		while (i < n - 1) {
			int best = i + 1;
			int end = Math.min(n - 1, i + 64);
			for (int j = end; j > i + 1; j--) {
				int dir = dir(x[j] - x[i], y[j] - y[i]);
				if (dir < 0) continue;
				int d = orient(dir, dir);
				if (clear(data, fp, o, x[i], y[i], x[j], y[j], d, reverse)) {
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
		return corners(sx, sy, tx, ty, ox, oy, on, turnBias);
	}
	private static ChartPlotterRoute corners(int sx, int sy, int tx, int ty, int[] x, int[] y, int n, int turnBias) {
		if (n < 3) return ChartPlotterRoute.ok(sx, sy, tx, ty, x, y, n, turnBias);
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
		return ChartPlotterRoute.ok(sx, sy, tx, ty, ox, oy, on, turnBias);
	}
	private static int move(Grid data, Footprint fp, int x, int y, int dir, int nx, int ny, int ndir, boolean reverse) {
		if (fp == null) return clearPoint(data, x, y, nx, ny);
		int p = pass(data, fp, x, y, dir, nx, ny, ndir, reverse);
		if (p != 1) return p;
		if (Math.abs(DX[ndir]) != 1 || Math.abs(DY[ndir]) != 1) return 1;
		if (DX[ndir] == 0 || DY[ndir] == 0) return 1;
		int px = pass(data, fp, x, y, dir, nx, y, DX[ndir] > 0 ? 4 : 12, reverse);
		int py = pass(data, fp, x, y, dir, x, ny, DY[ndir] > 0 ? 0 : 8, reverse);
		if (px == 1 && py == 1) return 1;
		return px < 0 || py < 0 ? -1 : 0;
	}
	private static int pass(Grid data, Footprint fp, int x, int y, int dir, int nx, int ny, int ndir, boolean reverse) {
		if (fp == null) return pass(data, x, y, nx, ny);
		int ax = center(x);
		int ay = center(y);
		int bx = center(nx);
		int by = center(ny);
		int ao = orient(dir, ndir);
		int bo = orient(ndir, ndir);
		int fwd = hitFootprint(data, fp, ax, ay, ao, bx, by, bo);
		if (fwd == 1) return 1;
		if (!reverse) return fwd;
		int rev = hitFootprint(data, fp, ax, ay, ao, bx, by, rev(bo));
		return rev == 1 ? 1 : fwd < 0 || rev < 0 ? -1 : 0;
	}
	private static int pass(Grid data, int x, int y, int nx, int ny) {
		int f = data.flag(nx, ny);
		if (f == ChartPlotterCollisionCache.UNKNOWN) return -1;
		if ((f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return 0;
		int dx = Integer.signum(nx - x);
		int dy = Integer.signum(ny - y);
		int mask = 0;
		if (dx < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		if (dx > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		if (dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		if (dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		if (dx < 0 && dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
		if (dx < 0 && dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
		if (dx > 0 && dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		if (dx > 0 && dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
		return (f & mask) == 0 ? 1 : 0;
	}
	private static void addStarts(Heap q, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int start) {
		if (start >= 0) {
			addStart(q, best, nodes, sx, sy, tx, ty, turnBias, dir(start));
			return;
		}
		for (int i = 0; i < DX.length; i++) addStart(q, best, nodes, sx, sy, tx, ty, turnBias, i);
	}
	private static void addStart(Heap q, LongIntMap best, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int dir) {
		int n = nodes.add(sx, sy, dir, 0, 0, h(sx, sy, tx, ty, dir, turnBias), -1);
		q.add(n);
		best.put(state(sx, sy, dir), 0);
	}
	private static void addFronts(Heap q, LongIntMap best, LongIntMap map, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int start) {
		if (start >= 0) {
			addFront(q, best, map, nodes, sx, sy, tx, ty, turnBias, dir(start));
			return;
		}
		for (int i = 0; i < DX.length; i++) addFront(q, best, map, nodes, sx, sy, tx, ty, turnBias, i);
	}
	private static void addFront(Heap q, LongIntMap best, LongIntMap map, Nodes nodes, int sx, int sy, int tx, int ty, int turnBias, int dir) {
		int n = nodes.add(sx, sy, dir, 0, 0, h(sx, sy, tx, ty, dir, turnBias), -1);
		long key = state(sx, sy, dir);
		q.add(n);
		best.put(key, 0);
		map.put(key, n);
	}
	private static void addEnds(Heap q, LongIntMap best, LongIntMap map, Nodes nodes, int tx, int ty, int sx, int sy, int turnBias, int start) {
		for (int i = 0; i < DX.length; i++) {
			int n = nodes.add(tx, ty, i, 0, 0, bh(tx, ty, sx, sy, start, turnBias), -1);
			long key = state(tx, ty, i);
			q.add(n);
			best.put(key, 0);
			map.put(key, n);
		}
	}
	private static ChartPlotterRoute direct(Grid data, Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse) {
		int dx = tx - sx;
		int dy = ty - sy;
		int d = dir(dx, dy);
		if (dx == 0 && dy == 0) return ChartPlotterRoute.ok(sx, sy, tx, ty, new int[]{sx}, new int[]{sy}, 1, turnBias);
		if (d < 0) return null;
		if (clear(data, fp, start, sx, sy, tx, ty, orient(d, d), reverse)) return ChartPlotterRoute.ok(sx, sy, tx, ty, new int[]{sx, tx}, new int[]{sy, ty}, 2, turnBias);
		return null;
	}
	private static ChartPlotterRoute direct2(Grid data, Footprint fp, int start, int sx, int sy, int tx, int ty, int turnBias, boolean reverse) {
		int dx = tx - sx;
		int dy = ty - sy;
		int best = Integer.MAX_VALUE;
		int bx = 0;
		int by = 0;
		int cap = cap(sx, sy, tx, ty, turnBias);
		for (int i = 0; i < DX.length; i++) {
			for (int j = 0; j < DX.length; j++) {
				int det = DX[i] * DY[j] - DY[i] * DX[j];
				if (det == 0) continue;
				int an = dx * DY[j] - dy * DX[j];
				int bn = DX[i] * dy - DY[i] * dx;
				if (an % det != 0 || bn % det != 0) continue;
				int a = an / det;
				int b = bn / det;
				if (a <= 0 || b <= 0) continue;
				int nd = step(i) * a + step(j) * b;
				if (nd > cap) continue;
				int cost = nd + (start >= 0 && dir(start) != i ? turn(turnBias) : 0) + (i != j ? turn(turnBias) : 0);
				if (cost >= best) continue;
				int mx = sx + DX[i] * a;
				int my = sy + DY[i] * a;
				if (!clear(data, fp, start, sx, sy, mx, my, orient(i, i), reverse)) continue;
				if (!clear(data, fp, orient(i, i), mx, my, tx, ty, orient(j, j), reverse)) continue;
				best = cost;
				bx = mx;
				by = my;
			}
		}
		return best == Integer.MAX_VALUE ? null : ChartPlotterRoute.ok(sx, sy, tx, ty, new int[]{sx, bx, tx}, new int[]{sy, by, ty}, 3, turnBias);
	}
	private static int directPoint(Grid data, int sx, int sy, int tx, int ty) {
		int x = sx;
		int y = sy;
		while (x != tx || y != ty) {
			int nx = x + Integer.signum(tx - x);
			int ny = y + Integer.signum(ty - y);
			int p = pass(data, x, y, nx, ny);
			if (p != 1) return p;
			if (nx != x && ny != y) {
				int px = pass(data, x, y, nx, y);
				int py = pass(data, x, y, x, ny);
				if (px != 1) return px;
				if (py != 1) return py;
			}
			x = nx;
			y = ny;
		}
		return 1;
	}
	private static int clearPoint(Grid data, int sx, int sy, int tx, int ty) {
		if (Math.max(Math.abs(tx - sx), Math.abs(ty - sy)) <= 1) return directPoint(data, sx, sy, tx, ty);
		return hitPath(data, center(sx), center(sy), center(tx), center(ty));
	}
	private static boolean clear(Grid data, Footprint fp, int start, int sx, int sy, int tx, int ty, int d, boolean reverse) {
		if (fp == null) return clearPoint(data, sx, sy, tx, ty) == 1;
		int o = start >= 0 ? start : d;
		int p = hitFootprint(data, fp, center(sx), center(sy), o, center(tx), center(ty), d);
		if (p == 1) return true;
		if (!reverse) return false;
		return hitFootprint(data, fp, center(sx), center(sy), o, center(tx), center(ty), rev(d)) == 1;
	}
	private static boolean blocked(Grid data, int x, int y) {
		int f = data.flag(x, y);
		return f != ChartPlotterCollisionCache.UNKNOWN && (f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
	}
	private static int open(Grid data, Footprint fp, int x, int y) {
		if (fp == null) return blocked(data, x, y) ? 0 : 1;
		boolean unknown = false;
		for (int i = 0; i < DX.length; i++) {
			int v = stand(data, fp, x, y, orient(i, i));
			if (v == 1) return 1;
			if (v < 0) unknown = true;
		}
		return unknown ? -1 : 0;
	}
	private static int stand(Grid data, Footprint fp, int x, int y, int o) {
		int cx = center(x);
		int cy = center(y);
		int oi = orientIndex(o);
		boolean unknown = false;
		for (int i = 0; i < fp.n; i++) {
			int v = hitFull(data, fp.x(cx, o, oi, i), fp.y(cy, o, oi, i));
			if (v == 0) return 0;
			if (v < 0) unknown = true;
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
		int dx = bx - ax;
		int dy = by - ay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / STEP;
		if (steps < 1) steps = 1;
		int px = Math.floorDiv(ax, TS);
		int py = Math.floorDiv(ay, TS);
		for (int i = 1; i <= steps; i++) {
			int lx = ax + dx * i / steps;
			int ly = ay + dy * i / steps;
			int x = Math.floorDiv(lx, TS);
			int y = Math.floorDiv(ly, TS);
			int f = data.flag(x, y);
			if (f == ChartPlotterCollisionCache.UNKNOWN) return -1;
			if (hitTile(f, x - px, y - py)) return 0;
			px = x;
			py = y;
		}
		return 1;
	}
	private static int hitFull(Grid data, int lx, int ly) {
		int x = Math.floorDiv(lx, TS);
		int y = Math.floorDiv(ly, TS);
		int f = data.flag(x, y);
		if (f == ChartPlotterCollisionCache.UNKNOWN) return -1;
		return (f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0 ? 0 : 1;
	}
	private static boolean hitTile(int f, int dx, int dy) {
		if ((f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return true;
		dx = Integer.signum(dx);
		dy = Integer.signum(dy);
		int mask = 0;
		if (dx < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		if (dx > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		if (dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		if (dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		if (dx < 0 && dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
		if (dx < 0 && dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
		if (dx > 0 && dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		if (dx > 0 && dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
		return (f & mask) != 0;
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
	private static int bh(int x, int y, int sx, int sy, int start, int turnBias) {
		return start < 0 ? h(x, y, sx, sy) : h(sx, sy, x, y, dir(start), turnBias);
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
	private static int cap(int sx, int sy, int tx, int ty, int turnBias) {
		int h = h(sx, sy, tx, ty);
		return h * (13 + turnBias) / 10 + 160;
	}
	private static int step(int dir) {return COST[dir];}
	private static int turn(int turnBias) {return 15 + turnBias * 14;}
	private static Bounds bounds(int sx, int sy, int tx, int ty, Footprint fp) {
		int d = Math.max(Math.abs(tx - sx), Math.abs(ty - sy));
		int m = Math.min(512, Math.max(32, d / 2 + 32 + radius(fp)));
		return new Bounds(Math.min(sx, tx) - m, Math.min(sy, ty) - m, Math.max(sx, tx) + m, Math.max(sy, ty) + m);
	}
	private static int dir(int angle) {
		int best = 0;
		int bd = Integer.MAX_VALUE;
		for (int i = 0; i < DX.length; i++) {
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
	private static int center(int v) {return v * TS + TS / 2;}
	private static int radius(Footprint fp) {
		if (fp == null) return 0;
		int r = Math.max(Math.max(Math.abs(fp.minX), Math.abs(fp.maxX)), Math.max(Math.abs(fp.minY), Math.abs(fp.maxY)));
		return Math.max(1, r / TS + 2);
	}
	private static float[] rectX(WorldEntityConfig wc) {
		float ox = wc.getBoundsX();
		float hw = wc.getBoundsWidth() / 2f;
		return new float[]{ox + hw, ox + hw, ox - hw, ox - hw};
	}
	private static float[] rectY(WorldEntityConfig wc) {
		float oy = wc.getBoundsY();
		float hh = wc.getBoundsHeight() / 2f;
		return new float[]{oy - hh, oy + hh, oy + hh, oy - hh};
	}
	private static int rotateX(int cx, int o, int x, int y) {return cx + (int) (((long) Perspective.COSINE[o] * x + (long) Perspective.SINE[o] * y) >> 16);}
	private static int rotateY(int cy, int o, int x, int y) {return cy + (int) (((long) Perspective.COSINE[o] * y - (long) Perspective.SINE[o] * x) >> 16);}
	private static int min(float[] v) {return (int) Math.floor(Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3])));}
	private static int max(float[] v) {return (int) Math.ceil(Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3])));}
	private static int next(int v, int max) {return Math.min(v + STEP, max);}
	private static boolean edge(int x, int y, int minX, int maxX, int minY, int maxY) {return x == minX || x == maxX || y == minY || y == maxY;}
	private static int orientIndex(int o) {
		for (int i = 0; i < OR.length; i++) {
			if (OR[i] == o) return i;
		}
		return -1;
	}
	private static long state(int x, int y, int d) {return ((long) x & 0xfffffL) << 44 | ((long) y & 0xfffffL) << 4 | d;}
	private static long chunk(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	private static final class Grid {
		final Map<Long, int[]> data;
		int cx;
		int cy;
		int[] c;
		boolean have;
		private Grid(Map<Long, int[]> data) {
			this.data = data;
		}
		int flag(int x, int y) {
			int nx = x >> 3;
			int ny = y >> 3;
			if (!have || nx != cx || ny != cy) {
				cx = nx;
				cy = ny;
				c = data.get(chunk(nx, ny));
				have = true;
			}
			return c == null ? ChartPlotterCollisionCache.UNKNOWN : c[(x & 7) + ((y & 7) << 3)];
		}
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
	private static final class Work {
		final Nodes a = new Nodes(1 << 15);
		final Nodes b = new Nodes(1 << 15);
		final Heap aq = new Heap(a, 1 << 15);
		final Heap bq = new Heap(b, 1 << 15);
		final LongIntMap ag = new LongIntMap(1 << 15);
		final LongIntMap bg = new LongIntMap(1 << 15);
		final LongIntMap an = new LongIntMap(1 << 15);
		final LongIntMap bn = new LongIntMap(1 << 15);
		void clearA() {
			a.clear();
			aq.clear();
			ag.clear();
		}
		void clearAll() {
			a.clear();
			b.clear();
			aq.clear();
			bq.clear();
			ag.clear();
			bg.clear();
			an.clear();
			bn.clear();
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
	private static final class Heap {
		final Nodes nodes;
		int[] q;
		int n;
		private Heap(Nodes nodes, int size) {
			this.nodes = nodes;
			q = new int[size];
		}
		boolean hasNext() {return n != 0;}
		int peek() {return q[0];}
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
			if (nodes.f[a] != nodes.f[b]) return nodes.f[a] < nodes.f[b];
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
		final int n;
		private Footprint(WorldEntityConfig wc) {
			float[] bx = rectX(wc);
			float[] by = rectY(wc);
			minX = min(bx);
			maxX = max(bx);
			minY = min(by);
			maxY = max(by);
			n = count();
			x = new int[n];
			y = new int[n];
			int p = 0;
			for (int ix = minX;; ix = next(ix, maxX)) {
				for (int iy = minY;; iy = next(iy, maxY)) {
					if (edge(ix, iy, minX, maxX, minY, maxY)) {
						x[p] = ix;
						y[p++] = iy;
					}
					if (iy == maxY) break;
				}
				if (ix == maxX) break;
			}
			rx = new int[OR.length][n];
			ry = new int[OR.length][n];
			for (int o = 0; o < OR.length; o++) {
				for (int i = 0; i < n; i++) {
					rx[o][i] = rotateX(0, OR[o], x[i], y[i]);
					ry[o][i] = rotateY(0, OR[o], x[i], y[i]);
				}
			}
		}
		int x(int cx, int o, int oi, int i) {return oi >= 0 ? cx + rx[oi][i] : rotateX(cx, o, x[i], y[i]);}
		int y(int cy, int o, int oi, int i) {return oi >= 0 ? cy + ry[oi][i] : rotateY(cy, o, x[i], y[i]);}
		private int count() {
			int n = 0;
			for (int ix = minX;; ix = next(ix, maxX)) {
				for (int iy = minY;; iy = next(iy, maxY)) {
					if (edge(ix, iy, minX, maxX, minY, maxY)) n++;
					if (iy == maxY) break;
				}
				if (ix == maxX) break;
			}
			return n;
		}
	}
	private static final class Search {
		final ChartPlotterRoute route;
		final int dist;
		private Search(ChartPlotterRoute route, int dist) {
			this.route = route;
			this.dist = dist;
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
		boolean outside(int x, int y) {return x < minX || y < minY || x > maxX || y > maxY;}
	}
}
