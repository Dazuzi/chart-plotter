package com.chartplotter.route;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.runtime.ChartPlotterSailing;
import com.chartplotter.util.ChartPlotterMath;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import static com.chartplotter.util.ChartPlotterMath.rotateX;
import static com.chartplotter.util.ChartPlotterMath.rotateY;
@Singleton
public final class ChartPlotterRoutes {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int PRUNE_RADIUS = 20;
	private static final int FOLLOW_RADIUS = 48;
	private static final int PRUNE = 2;
	private static final int CLEAR_RADIUS = 10;
	private static final int REACH_RADIUS = 14;
	private static final int MODE_TILE = 1;
	private static final int ETA_CAP = 600;
	public static final int PV_NONE = 0;
	public static final int PV_OK = 1;
	public static final int PV_SNAP = 2;
	public static final int PV_BAD = 3;
	private final ChartPlotterConfig config;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterSparseNodes sparseNodes;
	private final ChartPlotterSailing sailing;
	private volatile ChartPlotterRoute route;
	private final AtomicInteger seq = new AtomicInteger();
	private volatile boolean busy;
	private volatile long rev;
	private ExecutorService exec;
	private final AtomicReference<Future<?>> work = new AtomicReference<>();
	@Inject
	private ChartPlotterRoutes(ChartPlotterConfig config, ChartPlotterCollisionCache collisionCache, ChartPlotterSparseNodes sparseNodes, ChartPlotterSailing sailing) {
		this.config = config;
		this.collisionCache = collisionCache;
		this.sparseNodes = sparseNodes;
		this.sailing = sailing;
	}
	public void chart(int tx, int ty) {
		ChartPlotterRoute old = route;
		if (old != null && old.target(tx, ty, CLEAR_RADIUS)) {
			clear();
			return;
		}
		set(tx, ty);
	}
	public void set(int tx, int ty) {
		Start s = startTile();
		if (s == null) return;
		long t = target(collisionCache.snapshot(), s.ship.getConfig(), tx, ty, s.x, s.y);
		tx = (int) (t >> 32);
		ty = (int) t;
		request(s.ship, tx, ty, true, s.x, s.y);
	}
	public Preview preview(int tx, int ty) {
		Start s = startTile();
		if (s == null) return new Preview(PV_NONE, tx, ty);
		ChartPlotterRouteGrid grid = grid(collisionCache.snapshot(), s.ship.getConfig());
		int f = grid.flag(tx, ty);
		if (f == ChartPlotterCollisionCache.UNKNOWN) return new Preview(PV_SNAP, tx, ty);
		if (open(f)) return new Preview(PV_OK, tx, ty);
		long t = target(grid, tx, ty, s.x, s.y);
		int rx = (int) (t >> 32);
		int ry = (int) t;
		return rx == tx && ry == ty ? new Preview(PV_BAD, tx, ty) : new Preview(PV_SNAP, rx, ry);
	}
	public void tick(WorldView top, WorldEntity ship, LocalPoint loc) {
		ChartPlotterRoute r = route;
		if (r == null) return;
		int sx = ChartPlotterMath.worldTile(top.getBaseX(), loc.getX());
		int sy = ChartPlotterMath.worldTile(top.getBaseY(), loc.getY());
		if (near(sx, sy, r.tx, r.ty)) {
			clear();
			return;
		}
		if (busy || r.status == ChartPlotterRoute.PENDING) return;
		int turnBias = config.routeShape().bias;
		ChartPlotterRouteEffort effort = config.routeEffort();
		if (r.turnBias != turnBias || r.effort != effort) {
			request(top, ship, loc, r.tx, r.ty);
			return;
		}
		if (r.status != ChartPlotterRoute.OK && rev != collisionCache.rev()) {
			request(top, ship, loc, r.tx, r.ty);
			return;
		}
		if (r.status == ChartPlotterRoute.OK) {
			if (sailing.speed() == 0) return;
			LocalPoint front = routeLoc(top, ship, loc);
			int fx = ChartPlotterMath.worldTile(top.getBaseX(), front.getX());
			int fy = ChartPlotterMath.worldTile(top.getBaseY(), front.getY());
			ChartPlotterRoute nr = r.advance(fx, fy, PRUNE_RADIUS, FOLLOW_RADIUS, PRUNE);
			if (nr == r) return;
			if (nr != null) {
				route = nr;
				rev = collisionCache.rev();
				return;
			}
			request(top, ship, loc, r.tx, r.ty);
			return;
		}
		if (!r.start(sx, sy)) request(top, ship, loc, r.tx, r.ty);
	}
	public void clear() {
		seq.incrementAndGet();
		Future<?> f = work.getAndSet(null);
		if (f != null) f.cancel(true);
		route = null;
		rev = 0;
		busy = false;
	}
	public void stop() {
		clear();
		if (exec == null) return;
		exec.shutdownNow();
		exec = null;
	}
	public ChartPlotterRoute route() {return route;}
	private LocalPoint routeLoc(WorldView top, WorldEntity ship, LocalPoint loc) {
		WorldEntityConfig wc = ship.getConfig();
		if (wc == null) return loc;
		int o = sailing.actualHeading(ship);
		int x = wc.getBoundsX();
		int y = Math.round(wc.getBoundsY() - wc.getBoundsHeight() / 2f);
		return new LocalPoint(rotateX(loc.getX(), o, x, y), rotateY(loc.getY(), o, x, y), top);
	}
	static long target(ChartPlotterCollisionData data, WorldEntityConfig wc, int tx, int ty, int sx, int sy) {return target(grid(data, wc), tx, ty, sx, sy);}
	static long target(ChartPlotterRouteGrid data, int tx, int ty, int sx, int sy) {
		int f = data.flag(tx, ty);
		if (f == ChartPlotterCollisionCache.UNKNOWN || open(f)) return key(tx, ty);
		int bx = tx;
		int by = ty;
		long bs = Long.MAX_VALUE;
		for (int r = 1; r <= CLEAR_RADIUS; r++) {
			for (int y = ty - r; y <= ty + r; y++) {
				for (int x = tx - r; x <= tx + r; x++) {
					if (Math.max(Math.abs(x - tx), Math.abs(y - ty)) != r || data.flag(x, y) != ChartPlotterCollisionCache.OPEN) continue;
					long dx = x - sx;
					long dy = y - sy;
					long s = dx * dx + dy * dy;
					if (s >= bs) continue;
					bx = x;
					by = y;
					bs = s;
				}
			}
			if (bs != Long.MAX_VALUE) return key(bx, by);
		}
		return key(tx, ty);
	}
	private static ChartPlotterRouteGrid grid(ChartPlotterCollisionData data, WorldEntityConfig wc) {
		if (wc == null) return new ChartPlotterRouteGrid(data);
		ChartPlotterRouteGrid.Footprint fp = new ChartPlotterRouteGrid.Footprint(wc);
		return ChartPlotterRouteGrid.lazy(data, fp, radius(fp), MODE_TILE);
	}
	private static int radius(ChartPlotterRouteGrid.Footprint fp) {
		int r = Math.max(Math.max(Math.abs(fp.minX), Math.abs(fp.maxX)), Math.max(Math.abs(fp.minY), Math.abs(fp.maxY)));
		return Math.max(1, (r + TS - 1) / TS);
	}
	private void request(WorldView top, WorldEntity ship, LocalPoint loc, int tx, int ty) {
		request(ship, tx, ty, false, ChartPlotterMath.worldTile(top.getBaseX(), loc.getX()), ChartPlotterMath.worldTile(top.getBaseY(), loc.getY()));
	}
	private Start startTile() {
		if (!sailing.boarded()) return null;
		WorldView top = sailing.top();
		WorldEntity ship = sailing.ship();
		if (top == null || ship == null) return null;
		LocalPoint loc = sailing.anchorLoc(ship);
		if (loc == null) return null;
		return new Start(ship, ChartPlotterMath.worldTile(top.getBaseX(), loc.getX()), ChartPlotterMath.worldTile(top.getBaseY(), loc.getY()));
	}
	private void request(WorldEntity ship, int tx, int ty, boolean pending, int sx, int sy) {
		ChartPlotterRouteEffort effort = config.routeEffort();
		WorldEntityConfig wc = ship.getConfig();
		int turnBias = config.routeShape().bias;
		int weight = effort.weight;
		boolean reverse = sailing.reversing();
		int start = sailing.speed() == 0 ? -1 : sailing.heading(ship);
		int id = seq.incrementAndGet();
		ChartPlotterCollisionData data = collisionCache.snapshot();
		ChartPlotterSparseNodes.Snapshot sparse = sparseNodes.snapshot();
		long dataRev = data.rev;
		busy = true;
		Future<?> old = work.getAndSet(null);
		if (old != null) old.cancel(true);
		if (pending) {
			route = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, weight).effort(effort);
			rev = dataRev;
		}
		start();
		AtomicReference<Future<?>> nextRef = new AtomicReference<>();
		FutureTask<Void> next = new FutureTask<>(() -> {
			try {
				BooleanSupplier cancel = () -> id != seq.get() || Thread.currentThread().isInterrupted();
				ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, wc, start, sx, sy, tx, ty, turnBias, reverse, weight, CLEAR_RADIUS, sparse, effort.corridor, cancel).effort(effort);
				if (id == seq.get() && !Thread.currentThread().isInterrupted()) {
					route = r;
					rev = dataRev;
				}
			} finally {
				if (id == seq.get()) busy = false;
				work.compareAndSet(nextRef.get(), null);
			}
		}, null);
		nextRef.set(next);
		work.set(next);
		exec.execute(next);
	}
	private void start() {
		if (exec != null) return;
		exec = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-route");
			t.setDaemon(true);
			return t;
		});
	}
	private static boolean near(int ax, int ay, int bx, int by) {return ChartPlotterMath.chebyshev(ax, ay, bx, by) <= REACH_RADIUS;}
	private static boolean open(int f) {return (f & ChartPlotterCollisionCache.MOVE) == 0;}
	private static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	private static final class Start {
		final WorldEntity ship;
		final int x;
		final int y;
		private Start(WorldEntity ship, int x, int y) {
			this.ship = ship;
			this.x = x;
			this.y = y;
		}
	}
	public static Turn turn(ChartPlotterRoute r, int bx, int by, double speed, double accel, double max) {
		return turn(r, bx, by, speed, accel, max, 0);
	}
	public static Turn turn(ChartPlotterRoute r, int bx, int by, double speed, double accel, double max, long updated) {
		if (r == null || r.status != ChartPlotterRoute.OK || r.n < 2) return Turn.NONE;
		int cx = r.x[1];
		int cy = r.y[1];
		int ticks = speed > 0 ? eta(Math.hypot(cx - bx, cy - by), speed, accel, max) : -1;
		return new Turn(cx, cy, ticks, updated > 0 ? updated : r.updated, r.n == 2);
	}
	private static int eta(double dist, double speed, double accel, double max) {
		double v = speed;
		double d = 0;
		int t = 0;
		while (d < dist) {
			v += accel;
			if (v > max) v = max;
			if (v <= 0) return -1;
			d += v;
			if (++t > ETA_CAP) return -1;
		}
		return t;
	}
	public static final class Turn {
		public static final Turn NONE = new Turn(false, 0, 0, -1, 0);
		public final boolean valid;
		public final int x;
		public final int y;
		public final int ticks;
		public final long updated;
		public final boolean end;
		private Turn(int x, int y, int ticks, long updated, boolean end) {this(true, x, y, ticks, updated, end);}
		private Turn(boolean valid, int x, int y, int ticks, long updated) {this(valid, x, y, ticks, updated, false);}
		private Turn(boolean valid, int x, int y, int ticks, long updated, boolean end) {
			this.valid = valid;
			this.x = x;
			this.y = y;
			this.ticks = ticks;
			this.updated = updated;
			this.end = end;
		}
	}
	public static final class Preview {
		public final int state;
		public final int x;
		public final int y;
		private Preview(int state, int x, int y) {
			this.state = state;
			this.x = x;
			this.y = y;
		}
	}
}
