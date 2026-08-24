package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.Perspective;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.chartplotter.route.ChartPlotterRouteUtil.*;

public final class ChartPlotterSparseRouteFinder {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int SPARSE_LINK = 128;
	private static final int SPARSE_CONNECT = 192;
	private static final int SPARSE_LOCAL_LINK = 256;
	private static final int SPARSE_LOCAL_TRIES = 8;
	private static final int STEP = 32;
	private static final int REACH_CHECK = 255;
	private static final ThreadLocal<Index> INDEX = ThreadLocal.withInitial(Index::new);
	private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);
	private static final ThreadLocal<MaskBuffer> MASK = ThreadLocal.withInitial(MaskBuffer::new);
	private ChartPlotterSparseRouteFinder() {}
	static Path path(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int sx, int sy, int tx, int ty, int sparseBand, BooleanSupplier cancel) {
		if (nodes == null || nodes.x.length == 0 || sx == tx && sy == ty) return null;
		Index index = INDEX.get();
		if (!index.reset(nodes, cancel)) return Path.pending();
		Work work = WORK.get();
		work.reset(nodes.x.length + 2);
		Connector startCon = connector(data, nodes, index, work.start, sx, sy, sparseBand, cancel);
		if (startCon.pending) return Path.pending();
		if (startCon.n == 0) return null;
		Connector targetCon = connector(data, nodes, index, work.target, tx, ty, sparseBand, cancel);
		if (targetCon.pending) return Path.pending();
		if (targetCon.n == 0) return null;
		int n = nodes.x.length + 2;
		int[] g = work.g;
		int[] prev = work.prev;
		boolean[] done = work.done;
		Heap q = work.q;
		g[0] = 0;
		q.add(0, h(sx, sy, tx, ty));
		int seen = 0;
		while (q.hasNext()) {
			if ((seen++ & REACH_CHECK) == 0 && cancel.getAsBoolean()) return Path.pending();
			int a = q.poll();
			if (done[a]) continue;
			if (a == 1) return Path.of(nodes, prev, g[1], sx, sy, tx, ty, cancel);
			done[a] = true;
			int ax = x(nodes, a, sx, tx);
			int ay = y(nodes, a, sy, ty);
			int ag = g[a];
			if (a == 0) {
				for (int b = 1; b < n; b++) {
					if (done[b]) continue;
					int bx = x(nodes, b, sx, tx);
					int by = y(nodes, b, sy, ty);
					relax(b, edge(data, a, b, ax, ay, bx, by, startCon, targetCon), bx, by, ag, tx, ty, g, prev, q, a);
				}
				continue;
			}
			if (!done[1]) relax(1, targetCon.cost[a - 2], tx, ty, ag, tx, ty, g, prev, q, a);
			int[] links = index.links[a - 2];
			for (int node : links) {
				int b = node + 2;
				if (done[b]) continue;
				int bx = x(nodes, b, sx, tx);
				int by = y(nodes, b, sy, ty);
				relax(b, index.edge(data, a - 2, node), bx, by, ag, tx, ty, g, prev, q, a);
			}
		}
		return null;
	}
	private static void relax(int b, int edge, int bx, int by, int ag, int tx, int ty, int[] g, int[] prev, Heap q, int a) {
		if (edge == Integer.MIN_VALUE) return;
		int ng = ag + edge;
		if (g[b] != Integer.MIN_VALUE && g[b] <= ng) return;
		g[b] = ng;
		prev[b] = a;
		q.add(b, ng + h(bx, by, tx, ty));
	}
	static Path simplify(ChartPlotterCollisionData data, Path p, BooleanSupplier cancel) {
		if (p.n < 3) return p;
		int n = 1;
		int i = 0;
		while (i < p.n - 1) {
			if (cancel.getAsBoolean()) return null;
			int best = i + 1;
			for (int j = p.n - 1; j > i + 1; j--) {
				if ((j & REACH_CHECK) == 0 && cancel.getAsBoolean()) return null;
				if (clear(data, p.x[i], p.y[i], p.x[j], p.y[j]) != 1) continue;
				best = j;
				break;
			}
			p.x[n] = p.x[best];
			p.y[n++] = p.y[best];
			i = best;
		}
		return n == p.n ? p : new Path(Arrays.copyOf(p.x, n), Arrays.copyOf(p.y, n), n, cost(p.x, p.y, n), false);
	}
	static Corridor corridor(Path p, int band, BooleanSupplier cancel) {
		if (cancel.getAsBoolean()) return null;
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
		Corridor corridor = new Corridor(p.x, p.y, p.n, new ChartPlotterRouteBounds(minX - band, minY - band, maxX + band, maxY + band), c, band);
		return corridor.fill(cancel) ? corridor : null;
	}
	private static int edge(ChartPlotterCollisionData data, int a, int b, int ax, int ay, int bx, int by, Connector startCon, Connector targetCon) {
		if (a == 0 && b > 1) return startCon.cost[b - 2];
		if (b == 1 && a > 1) return targetCon.cost[a - 2];
		int link = a < 2 || b < 2 ? SPARSE_CONNECT : SPARSE_LINK;
		if (dist(ax, ay, bx, by) > link || clear(data, ax, ay, bx, by) != 1) return Integer.MIN_VALUE;
		return h(ax, ay, bx, by);
	}
	private static Connector connector(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, Index index, Connector c, int sx, int sy, int band, BooleanSupplier cancel) {
		c.reset(nodes.x.length);
		int candidates = index.query(sx, sy, SPARSE_LOCAL_LINK);
		for (int p = 0; p < candidates; p++) {
			if ((p & REACH_CHECK) == 0 && cancel.getAsBoolean()) {
				c.pending = true;
				return c;
			}
			int i = index.query[p];
			int d = dist(sx, sy, nodes.x[i], nodes.y[i]);
			insert(c.ci, c.cd, i, d);
			if (d > SPARSE_CONNECT) continue;
			int r = clear(data, sx, sy, nodes.x[i], nodes.y[i]);
			if (r == 1) {
				c.add(i, h(sx, sy, nodes.x[i], nodes.y[i]));
				c.los++;
			}
		}
		if (c.los == 0) {
			for (int p : c.ci) {
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
		int[] cost = new int[0];
		final int[] ci = new int[SPARSE_LOCAL_TRIES];
		final int[] cd = new int[SPARSE_LOCAL_TRIES];
		int n;
		int los;
		boolean pending;
		void reset(int size) {
			if (cost.length < size) cost = new int[size];
			Arrays.fill(cost, 0, size, Integer.MIN_VALUE);
			Arrays.fill(ci, -1);
			Arrays.fill(cd, Integer.MAX_VALUE);
			n = 0;
			los = 0;
			pending = false;
		}
		void add(int i, int c) {
			if (cost[i] == Integer.MIN_VALUE) n++;
			cost[i] = c;
		}
	}
	private static final class Work {
		int[] g = new int[0];
		int[] prev = new int[0];
		boolean[] done = new boolean[0];
		final Heap q = new Heap();
		final Connector start = new Connector();
		final Connector target = new Connector();
		void reset(int n) {
			if (g.length < n) {
				g = new int[n];
				prev = new int[n];
				done = new boolean[n];
			}
			Arrays.fill(g, 0, n, Integer.MIN_VALUE);
			Arrays.fill(prev, 0, n, -1);
			Arrays.fill(done, 0, n, false);
			q.clear();
		}
	}
	private static final class Index {
		ChartPlotterSparseNodes.Snapshot nodes;
		int[][] links;
		Map<Long, Bucket> buckets;
		int[] query = new int[32];
		ChartPlotterCollisionData edgeData;
		final LongIntMap edges = new LongIntMap(1024);
		boolean reset(ChartPlotterSparseNodes.Snapshot nodes, BooleanSupplier cancel) {
			if (this.nodes == nodes) return true;
			Map<Long, Bucket> buckets = new HashMap<>();
			for (int i = 0; i < nodes.x.length; i++) {
				if ((i & REACH_CHECK) == 0 && cancel.getAsBoolean()) return false;
				int bx = Math.floorDiv(nodes.x[i], SPARSE_LINK);
				int by = Math.floorDiv(nodes.y[i], SPARSE_LINK);
				buckets.computeIfAbsent(ChartPlotterCollisionData.key(bx, by), ignored -> new Bucket()).add(i);
			}
			this.nodes = nodes;
			this.buckets = buckets;
			links = new int[nodes.x.length][];
			for (int i = 0; i < nodes.x.length; i++) {
				if ((i & REACH_CHECK) == 0 && cancel.getAsBoolean()) {
					this.nodes = null;
					return false;
				}
				int n = query(nodes.x[i], nodes.y[i], SPARSE_LINK);
				int p = 0;
				for (int j = 0; j < n; j++) if (query[j] != i) query[p++] = query[j];
				links[i] = Arrays.copyOf(query, p);
			}
			edgeData = null;
			edges.clear();
			return true;
		}
		int query(int x, int y, int limit) {
			int bx = Math.floorDiv(x, SPARSE_LINK);
			int by = Math.floorDiv(y, SPARSE_LINK);
			int r = (limit + SPARSE_LINK - 1) / SPARSE_LINK;
			int n = 0;
			for (int dx = -r; dx <= r; dx++) {
				for (int dy = -r; dy <= r; dy++) {
					Bucket bucket = buckets.get(ChartPlotterCollisionData.key(bx + dx, by + dy));
					if (bucket == null) continue;
					for (int p = 0; p < bucket.n; p++) {
						int i = bucket.v[p];
						if (dist(x, y, nodes.x[i], nodes.y[i]) > limit) continue;
						if (n == query.length) query = Arrays.copyOf(query, query.length << 1);
						query[n++] = i;
					}
				}
			}
			Arrays.sort(query, 0, n);
			return n;
		}
		int edge(ChartPlotterCollisionData data, int a, int b) {
			if (edgeData != data) {
				edgeData = data;
				edges.clear();
			}
			int lo = Math.min(a, b);
			int hi = Math.max(a, b);
			long key = (long) lo << 32 ^ hi & 0xffffffffL;
			int clear = edges.get(key);
			if (clear == LongIntMap.MISS) {
				clear = clear(data, nodes.x[a], nodes.y[a], nodes.x[b], nodes.y[b]);
				edges.put(key, clear);
			}
			return clear == 1 ? h(nodes.x[a], nodes.y[a], nodes.x[b], nodes.y[b]) : Integer.MIN_VALUE;
		}
	}
	private static final class Bucket {
		int[] v = new int[8];
		int n;
		void add(int i) {
			if (n == v.length) v = Arrays.copyOf(v, v.length << 1);
			v[n++] = i;
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
		static Path of(ChartPlotterSparseNodes.Snapshot nodes, int[] prev, int cost, int sx, int sy, int tx, int ty, BooleanSupplier cancel) {
			int n = 0;
			for (int i = 1; i >= 0; i = prev[i]) {
				if ((n & 4095) == 0 && cancel.getAsBoolean()) return pending();
				n++;
			}
			int[] x = new int[n];
			int[] y = new int[n];
			int p = n;
			for (int i = 1; i >= 0; i = prev[i]) {
				if ((p & 4095) == 0 && cancel.getAsBoolean()) return pending();
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
		final int size;
		final byte[] mask;
		final byte maskMark;
		int cells;
		private Corridor(int[] x, int[] y, int n, ChartPlotterRouteBounds b, int cap, int band) {
			this.x = x;
			this.y = y;
			this.n = n;
			this.b = b;
			this.cap = cap;
			this.band = band;
			width = b.maxX - b.minX + 1;
			size = width * (b.maxY - b.minY + 1);
			MaskBuffer buffer = MASK.get();
			mask = buffer.reset(size);
			maskMark = buffer.mark;
		}
		private boolean fill(BooleanSupplier cancel) {
			int seen = 0;
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
						if ((seen++ & REACH_CHECK) == 0 && cancel.getAsBoolean()) return false;
						if (!nearSegment(px, py, ax, ay, bx, by, band)) continue;
						int p = px - b.minX + row;
						if (mask[p] == maskMark) continue;
						mask[p] = maskMark;
						cells++;
					}
				}
			}
			return true;
		}
	}
	private static final class Heap {
		int[] id;
		int[] f;
		int n;
		private Heap() {
			id = new int[16];
			f = new int[16];
		}
		void clear() {n = 0;}
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
	private static final class MaskBuffer {
		byte[] data = new byte[0];
		byte mark;
		byte[] reset(int n) {
			if (data.length < n) {
				data = new byte[n];
				mark = 1;
				return data;
			}
			if (++mark == 0) {
				Arrays.fill(data, (byte) 0);
				mark = 1;
			}
			return data;
		}
	}
}
