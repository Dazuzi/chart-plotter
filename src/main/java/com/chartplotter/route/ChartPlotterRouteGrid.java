package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;

import java.util.Arrays;

import static com.chartplotter.route.ChartPlotterRouteUtil.center;
import static com.chartplotter.route.ChartPlotterRouteUtil.state;
import static com.chartplotter.util.ChartPlotterMath.rotateX;
import static com.chartplotter.util.ChartPlotterMath.rotateY;

public final class ChartPlotterRouteGrid {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int DENSE_MAX = 24 << 20;
	private static final int STEP = 32;
	private static final int MODE_TILE = 1;
	private static final int[] OR = ChartPlotterRouteMoves.OR;
	final ChartPlotterCollisionData data;
	final int radius;
	final LongIntMap cache;
	final LongIntMap dirCache;
	final Footprint fp;
	final int mode;
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
	ChartPlotterCollisionData.Chunk c;
	boolean have;
	ChartPlotterRouteGrid(ChartPlotterCollisionData data) {this(data, 0, null, null, null, 0);}
	private ChartPlotterRouteGrid(ChartPlotterCollisionData data, int radius, LongIntMap cache, LongIntMap dirCache, Footprint fp, int mode) {
		this.data = data;
		this.radius = radius;
		this.cache = cache;
		this.dirCache = dirCache;
		this.fp = fp;
		this.mode = mode;
	}
	static ChartPlotterRouteGrid lazy(ChartPlotterCollisionData data, Footprint fp, int radius, int mode) {return new ChartPlotterRouteGrid(data, radius, new LongIntMap(16), new LongIntMap(16), fp, mode);}
	void cache(ChartPlotterRouteBounds b) {cache(b, DENSE_MAX);}
	void cache(ChartPlotterRouteBounds b, int max) {
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
				if (v != 0) return flag((byte) (v - 1));
				byte f = inflated(x, y);
				cached[i] = (byte) (f + 1);
				return flag(f);
			}
			long key = ChartPlotterCollisionData.key(x, y);
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
		if (fp != null) {
			int i = index(x, y);
			if (i >= 0) {
				int p = i + dir * stride;
				int v = cachedDirs[p];
				if (v != 0) return flag((byte) (v - 1));
				byte f = stand(x, y, dir);
				cachedDirs[p] = (byte) (f + 1);
				return flag(f);
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
	int baseFlag(int x, int y) {
		int i = rawIndex(x, y);
		if (i >= 0) {
			int v = raw[i];
			if (v != 0) return flag((byte) (v - 1));
			byte f = base(x, y);
			raw[i] = (byte) (f + 1);
			return flag(f);
		}
		return rawFlag(x, y);
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
	private static boolean blocker(int f) {return (f & ChartPlotterCollisionCache.MOVE) != 0;}
	static final class Footprint {
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
		Footprint(WorldEntityConfig wc) {
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
	private static int next(int v, int max) {return Math.min(v + STEP, max);}
	private static boolean edge(int x, int y, int minX, int maxX, int minY, int maxY) {return x == minX || x == maxX || y == minY || y == maxY;}
	private static int span(int min, int max) {return (max - min + STEP - 1) / STEP + 1;}
}
