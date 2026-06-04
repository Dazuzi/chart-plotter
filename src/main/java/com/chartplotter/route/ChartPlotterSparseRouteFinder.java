package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.util.ChartPlotterMath;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import net.runelite.api.Perspective;
public final class ChartPlotterSparseRouteFinder {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int SPARSE_LINK = 128;
	private static final int SPARSE_CONNECT = 192;
	private static final int SPARSE_LOCAL_LINK = 256;
	private static final int SPARSE_LOCAL_TRIES = 8;
	private static final int STEP = 32;
	private static final int REACH_CHECK = 4095;
	private ChartPlotterSparseRouteFinder() {}
	static Path path(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty, int targetRadius, int sparseBand, BooleanSupplier cancel) {
		if (nodes == null || nodes.x.length == 0 || near(sx, sy, tx, ty, targetRadius)) return null;
		Connector startCon = connector(data, nodes, sx, sy, sparseBand, cancel);
		Connector targetCon = connector(data, nodes, tx, ty, sparseBand, cancel);
		if (startCon.pending || targetCon.pending) return Path.pending();
		if (startCon.n == 0 || targetCon.n == 0) return null;
		int n = nodes.x.length + 2;
		int[] g = new int[n];
		int[] prev = new int[n];
		boolean[] done = new boolean[n];
		Heap q = new Heap(n);
		Arrays.fill(g, Integer.MIN_VALUE);
		Arrays.fill(prev, -1);
		g[0] = 0;
		q.add(0, h(sx, sy, tx, ty));
		int seen = 0;
		while (q.hasNext()) {
			if ((seen++ & REACH_CHECK) == 0 && cancel.getAsBoolean()) return Path.pending();
			int a = q.poll();
			if (done[a]) continue;
			if (a == 1) return Path.of(nodes, prev, g[1], sx, sy, tx, ty);
			done[a] = true;
			int ax = x(nodes, a, sx, tx);
			int ay = y(nodes, a, sy, ty);
			int ag = g[a];
			for (int b = 1; b < n; b++) {
				if (b == a || done[b]) continue;
				int bx = x(nodes, b, sx, tx);
				int by = y(nodes, b, sy, ty);
				int edge = edge(data, a, b, ax, ay, bx, by, startCon, targetCon);
				if (edge == Integer.MIN_VALUE) continue;
				int ng = ag + edge;
				if (g[b] != Integer.MIN_VALUE && g[b] <= ng) continue;
				g[b] = ng;
				prev[b] = a;
				q.add(b, ng + h(bx, by, tx, ty));
			}
		}
		return null;
	}
	static Path simplify(ChartPlotterCollisionData data, Path p) {
		if (p.n < 3) return p;
		int[] x = new int[p.n];
		int[] y = new int[p.n];
		int n = 0;
		int i = 0;
		x[n] = p.x[0];
		y[n++] = p.y[0];
		while (i < p.n - 1) {
			int best = i + 1;
			for (int j = p.n - 1; j > i + 1; j--) {
				if (clear(data, p.x[i], p.y[i], p.x[j], p.y[j]) != 1) continue;
				best = j;
				break;
			}
			x[n] = p.x[best];
			y[n++] = p.y[best];
			i = best;
		}
		return n == p.n ? p : new Path(Arrays.copyOf(x, n), Arrays.copyOf(y, n), n, cost(x, y, n), false);
	}
	static Corridor corridor(Path p, int band) {
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
		return new Corridor(p.x, p.y, p.n, new ChartPlotterRouteBounds(minX - band, minY - band, maxX + band, maxY + band), c, band);
	}
	private static int edge(ChartPlotterCollisionData data, int a, int b, int ax, int ay, int bx, int by, Connector startCon, Connector targetCon) {
		if (a == 0 && b > 1) return startCon.cost[b - 2];
		if (b == 1 && a > 1) return targetCon.cost[a - 2];
		int link = a < 2 || b < 2 ? SPARSE_CONNECT : SPARSE_LINK;
		if (dist(ax, ay, bx, by) > link || clear(data, ax, ay, bx, by) != 1) return Integer.MIN_VALUE;
		return h(ax, ay, bx, by);
	}
	private static Connector connector(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int band, BooleanSupplier cancel) {
		Connector c = new Connector(nodes.x.length);
		int[] ci = new int[SPARSE_LOCAL_TRIES];
		int[] cd = new int[SPARSE_LOCAL_TRIES];
		Arrays.fill(ci, -1);
		Arrays.fill(cd, Integer.MAX_VALUE);
		for (int i = 0; i < nodes.x.length; i++) {
			int d = dist(sx, sy, nodes.x[i], nodes.y[i]);
			if (d <= SPARSE_LOCAL_LINK) insert(ci, cd, i, d);
			if (d > SPARSE_CONNECT) continue;
			int r = clear(data, sx, sy, nodes.x[i], nodes.y[i]);
			if (r == 1) {
				c.add(i, h(sx, sy, nodes.x[i], nodes.y[i]));
				c.los++;
			}
		}
		if (c.los == 0) {
			for (int p : ci) {
				if (p < 0 || c.cost[p] != Integer.MIN_VALUE) continue;
				ChartPlotterRoute r = ChartPlotterRouteFinder.localConnect(data, sx, sy, nodes.x[p], nodes.y[p], band, cancel);
				if (r.status == ChartPlotterRoute.PENDING) {
					c.pending = true;
					break;
				}
				if (r.status == ChartPlotterRoute.OK) c.add(p, h(sx, sy, nodes.x[p], nodes.y[p]) * 13 / 10 + 50);
			}
		}
		return c;
	}
	private static void insert(int[] ci, int[] cd, int i, int d) {
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
	private static int clear(ChartPlotterCollisionData data, int ax, int ay, int bx, int by) {
		int lax = center(ax);
		int lay = center(ay);
		int dx = center(bx) - lax;
		int dy = center(by) - lay;
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
			int f = data.flagAt(x, y);
			if (f == ChartPlotterCollisionCache.UNKNOWN) unknown = true;
			else if (f != ChartPlotterCollisionCache.OPEN) return 0;
		}
		return unknown ? -1 : 1;
	}
	private static int cost(int[] x, int[] y, int n) {
		int c = 0;
		for (int i = 1; i < n; i++) c += h(x[i - 1], y[i - 1], x[i], y[i]);
		return c;
	}
	private static int x(ChartPlotterSparseNodes.Snapshot nodes, int i, int sx, int tx) {return i == 0 ? sx : i == 1 ? tx : nodes.x[i - 2];}
	private static int y(ChartPlotterSparseNodes.Snapshot nodes, int i, int sy, int ty) {return i == 0 ? sy : i == 1 ? ty : nodes.y[i - 2];}
	private static int center(int v) {return v * TS + TS / 2;}
	private static int dist(int ax, int ay, int bx, int by) {return ChartPlotterMath.chebyshev(ax, ay, bx, by);}
	private static boolean near(int ax, int ay, int bx, int by, int r) {return dist(ax, ay, bx, by) <= r;}
	private static int h(int x, int y, int tx, int ty) {
		int dx = Math.abs(tx - x);
		int dy = Math.abs(ty - y);
		int a = Math.max(dx, dy);
		int b = Math.min(dx, dy);
		return 9 * b <= 4 * a ? 10 * a + 2 * b : (290 * a + 205 * b) / 35;
	}
	private static int cap(int sx, int sy, int tx, int ty, int margin) {
		int h = h(sx, sy, tx, ty);
		return Math.max(h * 14 / 10 + 200, h + margin * 10 + 160);
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
	private static final class Connector {
		final int[] cost;
		int n;
		int los;
		boolean pending;
		private Connector(int n) {
			cost = new int[n];
			Arrays.fill(cost, Integer.MIN_VALUE);
		}
		void add(int i, int c) {
			if (cost[i] == Integer.MIN_VALUE) n++;
			cost[i] = c;
		}
	}
	static final class Path {
		final int[] x;
		final int[] y;
		final int n;
		final int cost;
		final boolean pending;
		private Path(int[] x, int[] y, int n, int cost, boolean pending) {
			this.x = x;
			this.y = y;
			this.n = n;
			this.cost = cost;
			this.pending = pending;
		}
		static Path pending() {return new Path(null, null, 0, 0, true);}
		static Path of(ChartPlotterSparseNodes.Snapshot nodes, int[] prev, int cost, int sx, int sy, int tx, int ty) {
			int n = 0;
			for (int i = 1; i >= 0; i = prev[i]) n++;
			int[] x = new int[n];
			int[] y = new int[n];
			int p = n;
			for (int i = 1; i >= 0; i = prev[i]) {
				p--;
				x[p] = ChartPlotterSparseRouteFinder.x(nodes, i, sx, tx);
				y[p] = ChartPlotterSparseRouteFinder.y(nodes, i, sy, ty);
			}
			return new Path(x, y, n, cost, false);
		}
	}
	static final class Corridor {
		final int[] x;
		final int[] y;
		final int n;
		final ChartPlotterRouteBounds b;
		final int cap;
		final int band;
		final int width;
		final byte[] mask;
		int cells;
		private Corridor(int[] x, int[] y, int n, ChartPlotterRouteBounds b, int cap, int band) {
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
	private static final class Heap {
		int[] id;
		int[] f;
		int n;
		private Heap(int size) {
			id = new int[size];
			f = new int[size];
		}
		boolean hasNext() {return n != 0;}
		void add(int v, int ff) {
			if (n == id.length) {
				id = Arrays.copyOf(id, id.length << 1);
				f = Arrays.copyOf(f, f.length << 1);
			}
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
		private static boolean less(int af, int ai, int bf, int bi) {return af != bf ? af < bf : ai < bi;}
	}
}
