package com.chartplotter.route;
import java.util.Arrays;
public final class ChartPlotterRouteWork {
	private static final int EDGE_MAX = 1 << 24;
	private static final int DENSE_MAX = 24 << 20;
	private static final int SPARSE_MOVE_MAX = 1 << 22;
	private static final int MC_OFF = 0;
	private static final int MC_DENSE = 1;
	private static final int MC_SPARSE = 2;
	private static final int MC_COMPACT = 3;
	private static final int BUCKET_TIE = 16;
	private static final int[] DX = ChartPlotterRouteMoves.DX;
	private ChartPlotterRouteWork() {}
	private static long state(int x, int y, int d) {return ((long) x & 0xfffffL) << 44 | ((long) y & 0xfffffL) << 4 | d;}
	static final class LongIntMap {
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
	static final class Work {
		final Nodes ba = new Nodes(1 << 15);
		final BucketHeap bucket = new BucketHeap(ba, 1 << 15);
		final LongIntMap bg = new LongIntMap(1 << 15);
		final DenseCost bbest = new DenseCost();
		final CompactCost cbest = new CompactCost();
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
		void clear() {
			a.clear();
			aq.clear();
		}
		void clearBase() {
			ba.clear();
			bucket.clear();
		}
	}
	static final class DenseCost {
		int[] v;
		int[] m1;
		int[] m2;
		byte[] md;
		int minX;
		int minY;
		int width;
		int area;
		boolean dom;
		boolean on;
		void reset(ChartPlotterRouteBounds b, boolean dom) {
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long cells = (long) width * height;
			long n = cells * DX.length;
			if (n <= 0 || n > EDGE_MAX) {
				on = false;
				this.dom = false;
				return;
			}
			area = (int) cells;
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
		void put(int x, int y, int dir) {
			int pos = x - minX + (y - minY) * width;
			v[pos + dir * area] = 0;
			if (dom) domPut(pos, dir, 0);
		}
		boolean dominated(int pos, int dir, int g, int turn) {
			int lim = g - turn;
			if (lim < 0) return false;
			int m = md[pos] == dir ? m2[pos] : m1[pos];
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
	static final class CompactCost {
		int[] v;
		int[] m1;
		int[] m2;
		int[] index;
		byte[] md;
		int minX;
		int minY;
		int width;
		int area;
		boolean dom;
		void reset(ChartPlotterSparseRouteFinder.Corridor c, boolean dom, int[] index) {
			minX = c.b.minX;
			minY = c.b.minY;
			width = c.width;
			area = c.cells;
			this.index = index;
			int n = area * DX.length;
			if (v == null || v.length < n) v = new int[n];
			Arrays.fill(v, 0, n, LongIntMap.MISS);
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
		}
		int pos(int p, int dir) {return index[p] - 1 + dir * area;}
		void putZero(int x, int y, int dir) {
			int p = x - minX + (y - minY) * width;
			v[pos(p, dir)] = 0;
		}
		boolean dominated(int pos, int dir, int g, int turn) {
			return dominatedAt(index[pos] - 1, dir, g, turn);
		}
		boolean dominatedAt(int p, int dir, int g, int turn) {
			return dominatedAtIndex(p, dir, g, turn);
		}
		boolean dominatedAtIndex(int p, int d, int g, int turn) {
			int lim = g - turn;
			if (lim < 0) return false;
			int m = md[p] == d ? m2[p] : m1[p];
			return m <= lim;
		}
		void domPutAt(int p, int dir, int val) {
			int d = md[p];
			if (d == dir) {
				m1[p] = val;
				return;
			}
			int m = m1[p];
			if (val < m) {
				m2[p] = m;
				m1[p] = val;
				md[p] = (byte) dir;
				return;
			}
			if (val < m2[p]) m2[p] = val;
		}
	}
	static final class DomCost {
		int[] m1;
		int[] m2;
		byte[] md;
		void reset(ChartPlotterRouteBounds b) {
			int width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			int area = width * height;
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
			int m = md[pos] == dir ? m2[pos] : m1[pos];
			return m <= lim;
		}
		void put(int pos, int dir, int val) {
			int old = md[pos];
			if (old == dir) {
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
	static final class DenseBest {
		int[] v;
		int minX;
		int minY;
		int width;
		int area;
		boolean on;
		void reset(ChartPlotterRouteBounds b) {
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long cells = (long) width * height;
			long n = cells * (DX.length / 2);
			if (n <= 0 || n > DENSE_MAX) {
				on = false;
				return;
			}
			area = (int) cells;
			if (v == null || v.length < n) v = new int[(int) n];
			Arrays.fill(v, 0, (int) n, 0);
			on = true;
		}
		int node(int x, int y, int dir) {
			return v[x - minX + (y - minY) * width + dir / 2 * area] - 1;
		}
		void put(int x, int y, int dir, int node) {
			v[x - minX + (y - minY) * width + dir / 2 * area] = node + 1;
		}
	}
	static final class BaseMoveCache {
		byte[] v;
		int[] index;
		LongIntMap sparse;
		int minX;
		int minY;
		int width;
		int area;
		int mode;
		boolean on;
		void reset(ChartPlotterRouteBounds b, ChartPlotterSparseRouteFinder.Corridor c) {
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long cells = (long) width * height;
			long n = cells * DX.length;
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
			if (c != null) {
				long compact = (long) c.cells * DX.length;
				if (compact > 0 && compact <= EDGE_MAX) {
					area = c.cells;
					if (v == null || v.length < compact) v = new byte[(int) compact];
					else Arrays.fill(v, 0, (int) compact, (byte) 0);
					if (index == null || index.length < c.mask.length) index = new int[c.mask.length];
					else Arrays.fill(index, 0, c.mask.length, 0);
					int p = 0;
					for (int i = 0; i < c.mask.length; i++) {
						if (c.mask[i] != 0) index[i] = ++p;
					}
					mode = MC_COMPACT;
					on = true;
					return;
				}
			}
			if (sparse == null) sparse = new LongIntMap(1 << 16);
			else sparse.clear();
			mode = MC_SPARSE;
			on = true;
		}
		int get(int x, int y, int dir) {
			if (mode == MC_SPARSE) return sparse.get(state(x, y, dir));
			int p = v[x - minX + (y - minY) * width + dir * area];
			return p == 0 ? LongIntMap.MISS : p - 2;
		}
		void put(int x, int y, int dir, int p) {
			if (mode == MC_SPARSE) {
				if (sparse.n < SPARSE_MOVE_MAX) sparse.put(state(x, y, dir), p);
				return;
			}
			v[x - minX + (y - minY) * width + dir * area] = (byte) (p + 2);
		}
	}
	static final class MoveCache {
		byte[] v;
		LongIntMap sparse;
		int minX;
		int minY;
		int width;
		int area;
		int mode;
		boolean on;
		void reset(ChartPlotterRouteBounds b) {
			minX = b.minX;
			minY = b.minY;
			width = b.maxX - b.minX + 1;
			int height = b.maxY - b.minY + 1;
			long cells = (long) width * height;
			long n = cells * (DX.length / 2);
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
			int p = v[x - minX + (y - minY) * width + dir / 2 * area];
			return p == 0 ? LongIntMap.MISS : p - 2;
		}
		void put(int x, int y, int dir, int p) {
			if (mode == MC_SPARSE) {
				if (sparse.n < SPARSE_MOVE_MAX) sparse.put(state(x, y, dir), p);
				return;
			}
			v[x - minX + (y - minY) * width + dir / 2 * area] = (byte) (p + 2);
		}
	}
	static final class Nodes {
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
	static final class BucketHeap {
		final Nodes nodes;
		int[] head = new int[1 << 16];
		int[] next;
		int[] used = new int[1 << 12];
		int n;
		int usedN;
		int min;
		int base;
		private BucketHeap(Nodes nodes, int size) {
			this.nodes = nodes;
			next = new int[size];
		}
		boolean hasNext() {return n != 0;}
		void clear() {
			for (int i = 0; i < usedN; i++) head[used[i]] = 0;
			usedN = 0;
			n = 0;
			min = 0;
		}
		void add(int v) {
			int f = bucket(v);
			if (f >= head.length) growHead(f);
			if (v >= next.length) next = Arrays.copyOf(next, Math.max(v + 1, next.length << 1));
			int h = head[f];
			if (h == 0) use(f);
			if (h <= 0 && (n == 0 || f < min)) min = f;
			next[v] = h > 0 ? h - 1 : -1;
			head[f] = v + 1;
			n++;
		}
		int poll() {
			while (head[min] <= 0) min++;
			int v = head[min] - 1;
			head[min] = next[v] < 0 ? -1 : next[v] + 1;
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
		}
		private int bucket(int v) {
			int f = nodes.f[v] - base;
			if (f < 0) f = 0;
			int g = nodes.g[v] / 64;
			if (g >= BUCKET_TIE) g = BUCKET_TIE - 1;
			return f * BUCKET_TIE + BUCKET_TIE - 1 - g;
		}
	}
	static final class Heap {
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
	}}
