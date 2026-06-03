package com.chartplotter.runtime;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.util.ChartPlotterMath;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import static com.chartplotter.util.ChartPlotterMath.rotateX;
import static com.chartplotter.util.ChartPlotterMath.rotateY;
@Singleton
public final class ChartPlotterProjection {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int TURN = 128;
	private static final int STEP = 32;
	private static final int MEMO = 16384;
	private final ChartPlotterSailing sailing;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterScene scene;
	private final Motion motion;
	private final Slot[] cache = new Slot[8];
	private int next;
	@Inject
	private ChartPlotterProjection(ChartPlotterSailing sailing, ChartPlotterCollisionCache collisionCache, ChartPlotterScene scene) {
		this.sailing = sailing;
		this.collisionCache = collisionCache;
		this.scene = scene;
		motion = new Motion() {
			@Override
			public int turn() {return sailing.turnDir();}
			@Override
			public double speed() {return sailing.speed();}
			@Override
			public double accel() {return sailing.accel();}
			@Override
			public double max() {return sailing.maxSpeed();}
			@Override
			public boolean reversing() {return sailing.reversing();}
		};
	}
	public Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, boolean showExt) {
		ChartPlotterScene.Area area = scene.area(wv);
		return path(wv, wc, anchor, from, target, limit(anchor, area), area, showExt);
	}
	public Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, boolean showExt) {return path(wv, wc, anchor, from, target, cap, null, showExt);}
	private Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, ChartPlotterScene.Area area, boolean showExt) {
		Key key = key(wv, wc, anchor, from, target, cap, area, showExt);
		for (Slot s : cache) {
			if (s != null && s.key.same(key)) return s.path;
		}
		Path p = raw(wv, wc, anchor, from, target, cap, area, showExt);
		cache[next++ & cache.length - 1] = new Slot(key, p);
		return p;
	}
	private Key key(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, ChartPlotterScene.Area area, boolean showExt) {
		return new Key(wv.getBaseX(), wv.getBaseY(), wv.getPlane(), anchor.getX(), anchor.getY(), from, target, cap, sailing.turnDir(), wid(wc), wcat(wc), wx(wc), wy(wc), ww(wc), wh(wc), Double.doubleToLongBits(sailing.speed()), Double.doubleToLongBits(sailing.accel()), Double.doubleToLongBits(sailing.maxSpeed()), sailing.reversing(), showExt, collisionCache.rev(), ChartPlotterScene.key(area));
	}
	private Path raw(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, ChartPlotterScene.Area area, boolean showExt) {
		return raw(anchor.getX(), anchor.getY(), from, target, cap, area, showExt, motion, blocker(wv, wc, collisionCache));
	}
	private static Path raw(int ax, int ay, int from, int target, int cap, ChartPlotterScene.Area area, boolean showExt, Motion motion, Blocker blocker) {
		Path p = new Path(cap + 2);
		p.start = from;
		p.x[p.n] = ax;
		p.y[p.n] = ay;
		p.o[p.n] = from;
		p.n++;
		int posX = 0;
		int posY = 0;
		int o = from;
		double speed = motion.speed();
		double accel = motion.accel();
		if (speed == 0) o = target;
		if (motion.reversing()) {
			speed *= -1;
			accel *= -1;
		}
		from = o;
		p.start = from;
		p.o[0] = from;
		int dir = ChartPlotterMath.angleDir(from, target, motion.turn());
		for (int i = 0; i < cap; i++) {
			if (o != target) o = turn(o, target, dir);
			speed += accel;
			double limit = Math.max(motion.max(), Math.abs(motion.speed()));
			speed = motion.reversing() ? Math.max(-limit, speed) : Math.min(limit, speed);
			int vx = velocityX(speed, o);
			int vy = velocityY(speed, o);
			if (vx == 0 && vy == 0) break;
			posX += vx;
			posY += vy;
			int lx = ax + posX;
			int ly = ay + posY;
			if (area != null && area.missing(lx, ly)) break;
			if (!p.blocked) {
				Block b = block(blocker, p.x[p.n - 1], p.y[p.n - 1], p.o[p.n - 1], lx, ly, o);
				if (b != null) {
					if (b.sx != p.x[p.n - 1] || b.sy != p.y[p.n - 1] || b.so != p.o[p.n - 1]) {
						p.x[p.n] = b.sx;
						p.y[p.n] = b.sy;
						p.o[p.n] = b.so;
						p.n++;
					}
					p.blocked = true;
					p.blockedAt = p.n;
					if (!showExt) break;
				}
			}
			p.x[p.n] = lx;
			p.y[p.n] = ly;
			p.o[p.n] = o;
			p.n++;
		}
		return p;
	}
	public static int match(Path a, Path b) {
		int n = Math.min(a.n, b.n);
		for (int i = 0; i < n; i++) {
			if (a.x[i] != b.x[i] || a.y[i] != b.y[i] || a.o[i] != b.o[i]) return i;
		}
		return n;
	}
	public static float[] rectX(WorldEntityConfig wc) {
		float ox = wc != null ? wc.getBoundsX() : 0;
		float hw = wc != null ? wc.getBoundsWidth() / 2f : TS;
		return new float[]{ox + hw, ox + hw, ox - hw, ox - hw};
	}
	public static float[] rectY(WorldEntityConfig wc) {
		float oy = wc != null ? wc.getBoundsY() : 0;
		float hh = wc != null ? wc.getBoundsHeight() / 2f : TS;
		return new float[]{oy - hh, oy + hh, oy + hh, oy - hh};
	}
	private static int wid(WorldEntityConfig wc) {return wc == null ? 0 : wc.getId();}
	private static int wcat(WorldEntityConfig wc) {return wc == null ? 0 : wc.getCategory();}
	private static int wx(WorldEntityConfig wc) {return wc == null ? 0 : Float.floatToIntBits(wc.getBoundsX());}
	private static int wy(WorldEntityConfig wc) {return wc == null ? 0 : Float.floatToIntBits(wc.getBoundsY());}
	private static int ww(WorldEntityConfig wc) {return wc == null ? 0 : Float.floatToIntBits(wc.getBoundsWidth());}
	private static int wh(WorldEntityConfig wc) {return wc == null ? 0 : Float.floatToIntBits(wc.getBoundsHeight());}
	private static Blocker blocker(WorldView wv, WorldEntityConfig wc, ChartPlotterCollisionCache cache) {return blocker(wv.getBaseX(), wv.getBaseY(), wc, cache.snapshot());}
	private static Blocker blocker(int baseX, int baseY, WorldEntityConfig wc, ChartPlotterCollisionData data) {return new Blocker(baseX, baseY, data, footprint(wc), new FlagMemo(MEMO));}
	private static Footprint footprint(WorldEntityConfig wc) {
		float[] rx = rectX(wc);
		float[] ry = rectY(wc);
		int minX = min(rx);
		int maxX = max(rx);
		int minY = min(ry);
		int maxY = max(ry);
		int n = 0;
		for (int x = minX;; x = next(x, maxX)) {
			for (int y = minY;; y = next(y, maxY)) {
				if (edge(x, y, minX, maxX, minY, maxY)) n++;
				if (y == maxY) break;
			}
			if (x == maxX) break;
		}
		Footprint p = new Footprint(n);
		for (int x = minX;; x = next(x, maxX)) {
			for (int y = minY;; y = next(y, maxY)) {
				if (edge(x, y, minX, maxX, minY, maxY)) {
					p.x[p.n] = x;
					p.y[p.n] = y;
					p.corner[p.n] = (x == minX || x == maxX) && (y == minY || y == maxY);
					p.n++;
				}
				if (y == maxY) break;
			}
			if (x == maxX) break;
		}
		return p;
	}
	private static Block block(Blocker b, int ax, int ay, int ao, int bx, int by, int bo) {return b == null ? null : blockBoundsExact(b, ax, ay, ao, bx, by, bo);}
	private static Block blockBoundsExact(Blocker b, int ax, int ay, int ao, int bx, int by, int bo) {
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
			if (!clearBounds(b, px, py, po, qx, qy, bo) && hitFootprint(b, px, py, po, qx, qy, bo)) return new Block(px, py, po);
			px = qx;
			py = qy;
			po = bo;
		}
		return null;
	}
	private static boolean clearBounds(Blocker b, int ax, int ay, int ao, int bx, int by, int bo) {
		Footprint fp = b.footprint;
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
		int x0 = Math.floorDiv(minX, TS);
		int y0 = Math.floorDiv(minY, TS);
		int x1 = Math.floorDiv(maxX, TS);
		int y1 = Math.floorDiv(maxY, TS);
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				int f = flag(b, x, y);
				if (f != ChartPlotterCollisionCache.UNKNOWN && blocker(f)) return false;
			}
		}
		return true;
	}
	private static boolean hitFootprint(Blocker b, int ax, int ay, int ao, int bx, int by, int bo) {
		Footprint fp = b.footprint;
		for (int i = 0; i < fp.n; i++) {
			int x = fp.x[i];
			int y = fp.y[i];
			if (hitPath(b, rotateX(ax, ao, x, y), rotateY(ay, ao, x, y), rotateX(bx, bo, x, y), rotateY(by, bo, x, y))) return true;
		}
		return false;
	}
	private static boolean hitPath(Blocker b, int ax, int ay, int bx, int by) {
		int dx = bx - ax;
		int dy = by - ay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / STEP;
		if (steps < 1) steps = 1;
		for (int i = 1; i <= steps; i++) {
			int x = Math.floorDiv(ax + dx * i / steps, TS);
			int y = Math.floorDiv(ay + dy * i / steps, TS);
			int f = flag(b, x, y);
			if (f == ChartPlotterCollisionCache.UNKNOWN) return false;
			if (blocker(f)) return true;
		}
		return false;
	}
	private static int flag(Blocker b, int x, int y) {
		if (b.memo != null) {
			if (b.memo.full) return flagRaw(b, x, y);
			long k = (long) x << 32 ^ y & 0xffffffffL;
			int i = b.memo.slot(k);
			for (int n = 0; n < b.memo.used.length; n++) {
				if (!b.memo.used[i]) {
					int f = flagRaw(b, x, y);
					b.memo.used[i] = true;
					b.memo.key[i] = k;
					b.memo.val[i] = f;
					if (++b.memo.n == b.memo.used.length) b.memo.full = true;
					return f;
				}
				if (b.memo.key[i] == k) return b.memo.val[i];
				i = i + 1 & b.memo.mask;
			}
			b.memo.full = true;
		}
		return flagRaw(b, x, y);
	}
	private static int flagRaw(Blocker b, int x, int y) {return b.data.flagAt(b.baseX + x, b.baseY + y);}
	private static boolean blocker(int f) {return (f & ChartPlotterCollisionCache.MOVE) != 0;}
	private static int min(float[] v) {return (int) Math.floor(Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3])));}
	private static int max(float[] v) {return (int) Math.ceil(Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3])));}
	private static int next(int v, int max) {return Math.min(v + STEP, max);}
	private static boolean edge(int x, int y, int minX, int maxX, int minY, int maxY) {return x == minX || x == maxX || y == minY || y == maxY;}
	private static int limit(LocalPoint anchor, ChartPlotterScene.Area area) {
		if (area == null) return 512;
		int ax = Math.floorDiv(anchor.getX(), TS);
		int ay = Math.floorDiv(anchor.getY(), TS);
		int edge = Math.max(Math.max(Math.abs(ax - area.minX), Math.abs(area.maxX - ax)), Math.max(Math.abs(ay - area.minY), Math.abs(area.maxY - ay)));
		return edge * 8 + 32;
	}
	private static int turn(int o, int target, int dir) {
		if (dir == 0) return target;
		int d = dir > 0 ? ChartPlotterMath.norm(target - o) : ChartPlotterMath.norm(o - target);
		if (d <= TURN) return target;
		return ChartPlotterMath.norm(o + TURN * dir);
	}
	private static int velocityX(double speed, int o) {return ChartPlotterMath.snap(ChartPlotterMath.round(-Perspective.SINE[o] * speed / 512.0));}
	private static int velocityY(double speed, int o) {return ChartPlotterMath.snap(ChartPlotterMath.round(-Perspective.COSINE[o] * speed / 512.0));}
	private static final class Slot {
		final Key key;
		final Path path;
		private Slot(Key key, Path path) {
			this.key = key;
			this.path = path;
		}
	}
	private static final class Key {
		final int baseX;
		final int baseY;
		final int plane;
		final int ax;
		final int ay;
		final int from;
		final int target;
		final int cap;
		final int turn;
		final int wid;
		final int wcat;
		final int wx;
		final int wy;
		final int ww;
		final int wh;
		final long speed;
		final long accel;
		final long max;
		final long rev;
		final long area;
		final boolean reverse;
		final boolean show;
		private Key(int baseX, int baseY, int plane, int ax, int ay, int from, int target, int cap, int turn, int wid, int wcat, int wx, int wy, int ww, int wh, long speed, long accel, long max, boolean reverse, boolean show, long rev, long area) {
			this.baseX = baseX;
			this.baseY = baseY;
			this.plane = plane;
			this.ax = ax;
			this.ay = ay;
			this.from = from;
			this.target = target;
			this.cap = cap;
			this.turn = turn;
			this.wid = wid;
			this.wcat = wcat;
			this.wx = wx;
			this.wy = wy;
			this.ww = ww;
			this.wh = wh;
			this.speed = speed;
			this.accel = accel;
			this.max = max;
			this.reverse = reverse;
			this.show = show;
			this.rev = rev;
			this.area = area;
		}
		boolean same(Key k) {return baseX == k.baseX && baseY == k.baseY && plane == k.plane && ax == k.ax && ay == k.ay && from == k.from && target == k.target && cap == k.cap && turn == k.turn && wid == k.wid && wcat == k.wcat && wx == k.wx && wy == k.wy && ww == k.ww && wh == k.wh && speed == k.speed && accel == k.accel && max == k.max && reverse == k.reverse && show == k.show && rev == k.rev && area == k.area;}
	}
	private static final class Blocker {
		final int baseX;
		final int baseY;
		final ChartPlotterCollisionData data;
		final Footprint footprint;
		final FlagMemo memo;
		private Blocker(int baseX, int baseY, ChartPlotterCollisionData data, Footprint footprint, FlagMemo memo) {
			this.baseX = baseX;
			this.baseY = baseY;
			this.data = data;
			this.footprint = footprint;
			this.memo = memo;
		}
	}
	private static final class FlagMemo {
		final boolean[] used;
		final long[] key;
		final int[] val;
		final int mask;
		public int n;
		boolean full;
		private FlagMemo(int n) {
			used = new boolean[n];
			key = new long[n];
			val = new int[n];
			mask = n - 1;
		}
		int slot(long k) {
			k ^= k >>> 33;
			k *= 0xff51afd7ed558ccdL;
			k ^= k >>> 33;
			return (int) k & mask;
		}
	}
	private static final class Footprint {
		public final int[] x;
		public final int[] y;
		final boolean[] corner;
		public int n;
		private Footprint(int n) {
			x = new int[n];
			y = new int[n];
			corner = new boolean[n];
		}
	}
	public static final class Path {
		public final int[] x;
		public final int[] y;
		public final int[] o;
		public int start;
		public int n;
		public boolean blocked;
		public int blockedAt = Integer.MAX_VALUE;
		private Path(int cap) {
			x = new int[cap];
			y = new int[cap];
			o = new int[cap];
		}
	}
	private static final class Block {
		final int sx;
		final int sy;
		final int so;
		private Block(int sx, int sy, int so) {
			this.sx = sx;
			this.sy = sy;
			this.so = so;
		}
	}
	private interface Motion {
		int turn();
		double speed();
		double accel();
		double max();
		boolean reversing();
	}
}
