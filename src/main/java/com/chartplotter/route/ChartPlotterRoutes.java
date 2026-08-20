package com.chartplotter.route;

import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.runtime.ChartPlotterSailing;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
	private static final int MAX_STOPS = 32;
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
	private final AtomicReference<ChartPlotterTrip> trip = new AtomicReference<>(ChartPlotterTrip.empty(0));
	private final AtomicInteger seq = new AtomicInteger();
	private volatile boolean activeBusy;
	private volatile boolean paused;
	private volatile long rev;
	private volatile long sparseRev;
	private ExecutorService exec;
	private final AtomicReference<Future<?>> work = new AtomicReference<>();
	@Inject
	private ChartPlotterRoutes(ChartPlotterConfig config, ChartPlotterCollisionCache collisionCache, ChartPlotterSparseNodes sparseNodes, ChartPlotterSailing sailing) {
		this.config = config;
		this.collisionCache = collisionCache;
		this.sparseNodes = sparseNodes;
		this.sailing = sailing;
	}
	public void set(int tx, int ty) {
		Start s = startTile();
		if (s == null) return;
		ChartPlotterCollisionData data = collisionCache.snapshot();
		long t = target(data, s.config, tx, ty, s.x, s.y);
		tx = (int) (t >> 32);
		ty = (int) t;
		int id = cancel();
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		ChartPlotterRoute pending = ChartPlotterRoute.pending(s.x, s.y, tx, ty, turnBias, effort.weight).effort(effort);
		trip.set(ChartPlotterTrip.single(id, tx, ty, pending));
		paused = false;
		request(s, data, sparseNodes.snapshot(), new boolean[]{true}, id, turnBias, effort);
	}
	public void append(int tx, int ty) {
		Start s = startTile();
		if (s == null) return;
		ChartPlotterTrip old = trip.get();
		if (old.empty()) {
			set(tx, ty);
			return;
		}
		if (old.size() >= MAX_STOPS) return;
		int sx = legStartX(old, old.size(), s.x);
		int sy = legStartY(old, old.size(), s.y);
		ChartPlotterCollisionData data = collisionCache.snapshot();
		long t = target(data, s.config, tx, ty, sx, sy);
		tx = (int) (t >> 32);
		ty = (int) t;
		if (ChartPlotterMath.chebyshev(sx, sy, tx, ty) <= CLEAR_RADIUS) return;
		int id = cancel();
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		ChartPlotterRoute pending = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, effort.weight).effort(effort);
		int fx = tx;
		int fy = ty;
		boolean reboard = paused;
		ChartPlotterTrip next = trip.updateAndGet(p -> p.append(id, fx, fy, pending));
		paused = false;
		boolean[] selected = reboard ? all(next) : pending(next);
		if (data.rev != rev) merge(selected, failed(next));
		suffix(selected);
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, data, sparseNodes.snapshot(), selected, id, turnBias, effort);
	}
	public void move(int stop, int oldX, int oldY, int tx, int ty) {
		Start s = startTile();
		if (s == null) return;
		ChartPlotterTrip old = trip.get();
		if (stop < 0 || stop >= old.size() || old.x(stop) != oldX || old.y(stop) != oldY) return;
		int sx = legStartX(old, stop, s.x);
		int sy = legStartY(old, stop, s.y);
		ChartPlotterCollisionData data = collisionCache.snapshot();
		long t = target(data, s.config, tx, ty, sx, sy);
		tx = (int) (t >> 32);
		ty = (int) t;
		if (old.x(stop) == tx && old.y(stop) == ty || stop > 0 && ChartPlotterMath.chebyshev(sx, sy, tx, ty) <= CLEAR_RADIUS || stop + 1 < old.size() && ChartPlotterMath.chebyshev(old.x(stop + 1), old.y(stop + 1), tx, ty) <= CLEAR_RADIUS) return;
		int id = cancel();
		int fx = tx;
		int fy = ty;
		boolean reboard = paused;
		ChartPlotterTrip next = trip.updateAndGet(p -> p.move(id, stop, fx, fy));
		paused = false;
		boolean[] selected = reboard ? all(next) : pending(next);
		selected[stop] = true;
		if (data.rev != rev) merge(selected, failed(next));
		suffix(selected);
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, data, sparseNodes.snapshot(), selected, id, turnBias, effort);
	}
	public void truncate(int stop) {
		ChartPlotterTrip old = trip.get();
		if (stop < 0 || stop >= old.size()) return;
		int id = cancel();
		ChartPlotterTrip next = trip.updateAndGet(p -> p.truncate(id, stop));
		if (next.empty()) {
			paused = false;
			return;
		}
		Start s = startTile();
		if (s == null) return;
		boolean[] selected = paused ? all(next) : pending(next);
		paused = false;
		if (!any(selected)) return;
		suffix(selected);
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, collisionCache.snapshot(), sparseNodes.snapshot(), selected, id, turnBias, effort);
	}
	public void truncate(int stop, int x, int y) {
		ChartPlotterTrip current = trip.get();
		if (stop < 0 || stop >= current.size() || current.x(stop) != x || current.y(stop) != y) return;
		truncate(stop);
	}
	public Preview preview(int tx, int ty, boolean append) {
		Start s = startTile();
		if (s == null) return new Preview(PV_NONE, tx, ty);
		ChartPlotterTrip p = trip.get();
		int sx = append && !p.empty() ? legStartX(p, p.size(), s.x) : s.x;
		int sy = append && !p.empty() ? legStartY(p, p.size(), s.y) : s.y;
		if (append && p.size() >= MAX_STOPS) return new Preview(PV_BAD, tx, ty);
		ChartPlotterRouteGrid grid = grid(collisionCache.snapshot(), s.config);
		int f = grid.flag(tx, ty);
		if (f == ChartPlotterCollisionCache.UNKNOWN) return new Preview(PV_SNAP, tx, ty);
		if (open(f)) return new Preview(append && ChartPlotterMath.chebyshev(sx, sy, tx, ty) <= CLEAR_RADIUS ? PV_BAD : PV_OK, tx, ty);
		long t = target(grid, tx, ty, sx, sy);
		int rx = (int) (t >> 32);
		int ry = (int) t;
		if (append && ChartPlotterMath.chebyshev(sx, sy, rx, ry) <= CLEAR_RADIUS) return new Preview(PV_BAD, rx, ry);
		return rx == tx && ry == ty ? new Preview(PV_BAD, tx, ty) : new Preview(PV_SNAP, rx, ry);
	}
	public void tick(WorldView top, WorldEntity ship, LocalPoint loc) {
		ChartPlotterTrip p = trip.get();
		if (p.empty()) return;
		Start s = start(top, ship, loc);
		ChartPlotterRoute r = p.active();
		if (near(s.x, s.y, p.x(0), p.y(0)) && (r == null || r.status != ChartPlotterRoute.OK || r.n <= 2 || sailing.speed() == 0)) {
			advance(s);
			return;
		}
		if (paused) {
			paused = false;
			replan(s, all(p));
			return;
		}
		if (r == null) {
			if (!activeBusy) replan(s, missing(p));
			return;
		}
		if (activeBusy) return;
		if (r.status == ChartPlotterRoute.PENDING) {
			if (work.get() == null && (rev != collisionCache.rev() || sparseRev != sparseNodes.version())) {
				boolean[] selected = new boolean[p.size()];
				selected[0] = true;
				replan(s, selected);
			}
			return;
		}
		int turnBias = config.routeShape().bias;
		ChartPlotterRouteEffort effort = config.routeEffort();
		if (r.turnBias != turnBias || r.effort != effort) {
			replan(s, all(p));
			return;
		}
		if (sparseRev != sparseNodes.version()) {
			replan(s, all(p));
			return;
		}
		if (work.get() == null && rev != collisionCache.rev()) {
			boolean[] retry = failed(p);
			if (any(retry)) {
				replan(s, retry);
				return;
			}
		}
		if (r.status != ChartPlotterRoute.OK && !r.start(s.x, s.y)) {
			boolean[] selected = new boolean[p.size()];
			selected[0] = true;
			replan(s, selected);
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
				update(p.generation(), 0, nr);
				return;
			}
			boolean[] selected = new boolean[p.size()];
			selected[0] = true;
			replan(s, selected);
		}
	}
	public void clear() {
		int id = cancel();
		trip.set(ChartPlotterTrip.empty(id));
		paused = false;
		rev = 0;
		sparseRev = 0;
	}
	public void pause() {
		if (paused || trip.get().empty()) return;
		int id = cancel();
		trip.updateAndGet(p -> p.generation(id));
		paused = true;
		rev = 0;
		sparseRev = 0;
	}
	public void stop() {
		clear();
		if (exec == null) return;
		exec.shutdownNow();
		exec = null;
	}
	public ChartPlotterRoute route() {return trip.get().active();}
	public ChartPlotterTrip trip() {return trip.get();}
	public boolean canAppend() {return trip.get().size() < MAX_STOPS;}
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
		if (f == ChartPlotterCollisionCache.UNKNOWN || open(f)) return ChartPlotterCollisionData.key(tx, ty);
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
			if (bs != Long.MAX_VALUE) return ChartPlotterCollisionData.key(bx, by);
		}
		return ChartPlotterCollisionData.key(tx, ty);
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
	private Start startTile() {
		if (!sailing.boarded()) return null;
		WorldView top = sailing.top();
		WorldEntity ship = sailing.ship();
		if (top == null || ship == null) return null;
		LocalPoint loc = sailing.anchorLoc(ship);
		if (loc == null) return null;
		return start(top, ship, loc);
	}
	private Start start(WorldView top, WorldEntity ship, LocalPoint loc) {
		int heading = sailing.speed() == 0 ? -1 : sailing.heading(ship);
		return new Start(ship.getConfig(), ChartPlotterMath.worldTile(top.getBaseX(), loc.getX()), ChartPlotterMath.worldTile(top.getBaseY(), loc.getY()), heading, sailing.reversing());
	}
	private void advance(Start s) {
		boolean reboard = paused;
		int id = cancel();
		paused = false;
		ChartPlotterTrip next = trip.updateAndGet(p -> p.advance(id));
		if (next.empty()) return;
		boolean[] selected = reboard ? all(next) : pending(next);
		selected[0] = true;
		suffix(selected);
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, collisionCache.snapshot(), sparseNodes.snapshot(), selected, id, turnBias, effort);
	}
	private void replan(Start s, boolean[] selected) {
		if (!any(selected)) return;
		ChartPlotterCollisionData data = collisionCache.snapshot();
		ChartPlotterTrip current = trip.get();
		if (data.rev != rev) merge(selected, failed(current));
		suffix(selected);
		int id = cancel();
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		ChartPlotterTrip next = trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		if (next.empty()) return;
		request(s, data, sparseNodes.snapshot(), selected, id, turnBias, effort);
	}
	private void request(Start s, ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot sparse, boolean[] selected, int id, int turnBias, ChartPlotterRouteEffort effort) {
		ChartPlotterTrip snapshot = trip.get();
		if (snapshot.generation() != id || snapshot.empty() || !any(selected)) return;
		int weight = effort.weight;
		rev = data.rev;
		sparseRev = sparse.version;
		activeBusy = selected[0];
		start();
		AtomicReference<Future<?>> nextRef = new AtomicReference<>();
		FutureTask<Void> next = new FutureTask<>(() -> {
			try {
				BooleanSupplier cancel = () -> id != seq.get() || Thread.currentThread().isInterrupted();
				int first = first(selected);
				ChartPlotterRoute previous = first > 0 ? snapshot.route(first - 1) : null;
				for (int i = first; i < selected.length; i++) {
					if (cancel.getAsBoolean()) return;
					int sx = s.x;
					int sy = s.y;
					if (i > 0) {
						ChartPlotterRoute from = previous;
						if (from == null || from.status != ChartPlotterRoute.OK) {
							for (; i < selected.length; i++) {
								ChartPlotterRoute r = ChartPlotterRoute.dependent(snapshot.x(i - 1), snapshot.y(i - 1), snapshot.x(i), snapshot.y(i), turnBias, weight).effort(effort);
								if (!update(id, i, r)) return;
							}
							return;
						}
						sx = endX(from);
						sy = endY(from);
					}
					int heading = i == 0 ? s.heading : -1;
					boolean reverse = i == 0 && s.reverse;
					ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, s.config, heading, sx, sy, snapshot.x(i), snapshot.y(i), turnBias, reverse, weight, CLEAR_RADIUS, sparse, effort.corridor, cancel).effort(effort);
					if (!update(id, i, r)) return;
					if (i == 0 && id == seq.get()) activeBusy = false;
					previous = r;
				}
			} finally {
				if (id == seq.get()) activeBusy = false;
				work.compareAndSet(nextRef.get(), null);
			}
		}, null);
		nextRef.set(next);
		work.set(next);
		exec.execute(next);
	}
	private boolean update(int id, int i, ChartPlotterRoute route) {
		while (id == seq.get()) {
			ChartPlotterTrip old = trip.get();
			if (old.generation() != id || i >= old.size()) return false;
			if (trip.compareAndSet(old, old.route(i, route))) return true;
		}
		return false;
	}
	private int cancel() {
		int id = seq.incrementAndGet();
		Future<?> old = work.getAndSet(null);
		if (old != null) old.cancel(true);
		activeBusy = false;
		return id;
	}
	private void start() {
		if (exec != null) return;
		exec = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-route");
			t.setDaemon(true);
			return t;
		});
	}
	private static boolean[] pending(ChartPlotterTrip p) {
		boolean[] selected = new boolean[p.size()];
		for (int i = 0; i < selected.length; i++) selected[i] = p.route(i) == null || p.route(i).status == ChartPlotterRoute.PENDING;
		return selected;
	}
	private static boolean[] missing(ChartPlotterTrip p) {
		boolean[] selected = new boolean[p.size()];
		for (int i = 0; i < selected.length; i++) selected[i] = p.route(i) == null;
		return selected;
	}
	private static boolean[] failed(ChartPlotterTrip p) {
		boolean[] selected = new boolean[p.size()];
		for (int i = 0; i < selected.length; i++) selected[i] = p.route(i) == null || p.route(i).status != ChartPlotterRoute.OK && p.route(i).status != ChartPlotterRoute.PENDING;
		return selected;
	}
	private static boolean[] all(ChartPlotterTrip p) {
		boolean[] selected = new boolean[p.size()];
		Arrays.fill(selected, true);
		return selected;
	}
	private static boolean any(boolean[] selected) {
		for (boolean on : selected) if (on) return true;
		return false;
	}
	private static int first(boolean[] selected) {
		for (int i = 0; i < selected.length; i++) if (selected[i]) return i;
		return selected.length;
	}
	private static void suffix(boolean[] selected) {
		boolean on = false;
		for (int i = 0; i < selected.length; i++) {
			on |= selected[i];
			selected[i] = on;
		}
	}
	static int legStartX(ChartPlotterTrip trip, int stop, int live) {
		if (stop == 0) return live;
		ChartPlotterRoute route = trip.route(stop - 1);
		return route != null && route.status == ChartPlotterRoute.OK ? endX(route) : trip.x(stop - 1);
	}
	static int legStartY(ChartPlotterTrip trip, int stop, int live) {
		if (stop == 0) return live;
		ChartPlotterRoute route = trip.route(stop - 1);
		return route != null && route.status == ChartPlotterRoute.OK ? endY(route) : trip.y(stop - 1);
	}
	private static int endX(ChartPlotterRoute route) {return route.n > 0 ? route.x[route.n - 1] : route.tx;}
	private static int endY(ChartPlotterRoute route) {return route.n > 0 ? route.y[route.n - 1] : route.ty;}
	private static void merge(boolean[] dst, boolean[] src) {for (int i = 0; i < dst.length; i++) dst[i] |= src[i];}
	private static boolean near(int ax, int ay, int bx, int by) {return ChartPlotterMath.chebyshev(ax, ay, bx, by) <= REACH_RADIUS;}
	private static boolean open(int f) {return (f & ChartPlotterCollisionCache.MOVE) == 0;}
	private static final class Start {
		final WorldEntityConfig config;
		final int x;
		final int y;
		final int heading;
		final boolean reverse;
		private Start(WorldEntityConfig config, int x, int y, int heading, boolean reverse) {
			this.config = config;
			this.x = x;
			this.y = y;
			this.heading = heading;
			this.reverse = reverse;
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
	public static Turn turn(ChartPlotterRoute r, double bx, double by, double speed, double accel, double max, long updated) {
		if (r == null || r.status != ChartPlotterRoute.OK || r.n < 2) return Turn.NONE;
		int cx = r.x[1];
		int cy = r.y[1];
		int ticks = speed > 0 ? eta(Math.hypot(cx + 0.5 - bx, cy + 0.5 - by), speed, accel, max) : -1;
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
