package com.chartplotter.route;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterRouteEffort;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.runtime.ChartPlotterSailing;
import com.chartplotter.util.ChartPlotterMath;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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
	private static final int PRUNE_RADIUS = 8;
	private static final int FOLLOW_RADIUS = 24;
	private static final int PRUNE = 12;
	private static final int CLEAR_RADIUS = 10;
	private static final int MODE_TILE = 1;
	private final ChartPlotterConfig config;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterSparseNodes sparseNodes;
	private final ChartPlotterSailing sailing;
	private volatile ChartPlotterRoute route;
	private final AtomicInteger seq = new AtomicInteger();
	private volatile boolean busy;
	private volatile long rev;
	private ExecutorService exec;
	@Inject
	private ChartPlotterRoutes(ChartPlotterConfig config, ChartPlotterCollisionCache collisionCache, ChartPlotterSparseNodes sparseNodes, ChartPlotterSailing sailing) {
		this.config = config;
		this.collisionCache = collisionCache;
		this.sparseNodes = sparseNodes;
		this.sailing = sailing;
	}
	public void chart(int tx, int ty) {
		if (!sailing.boarded()) return;
		WorldView top = sailing.top();
		WorldEntity ship = sailing.ship();
		if (top == null || ship == null) return;
		LocalPoint loc = ship.getTargetLocation();
		if (loc == null) loc = ship.getLocalLocation();
		if (loc == null) return;
		ChartPlotterRoute old = route;
		if (old != null && old.target(tx, ty, CLEAR_RADIUS)) {
			clear();
			return;
		}
		int sx = top.getBaseX() + Math.floorDiv(loc.getX(), TS);
		int sy = top.getBaseY() + Math.floorDiv(loc.getY(), TS);
		long t = target(collisionCache.snapshot(), ship.getConfig(), tx, ty, sx, sy);
		tx = (int) (t >> 32);
		ty = (int) t;
		request(top, ship, loc, tx, ty, true);
	}
	public void tick(WorldView top, WorldEntity ship, LocalPoint loc) {
		ChartPlotterRoute r = route;
		if (r == null) return;
		int sx = top.getBaseX() + Math.floorDiv(loc.getX(), TS);
		int sy = top.getBaseY() + Math.floorDiv(loc.getY(), TS);
		if (near(sx, sy, r.tx, r.ty)) {
			clear();
			return;
		}
		if (busy || r.status == ChartPlotterRoute.PENDING) return;
		int turnBias = config.routeShape().bias;
		ChartPlotterRouteEffort effort = config.routeEffort();
		if (r.turnBias != turnBias || r.effort != effort) {
			request(top, ship, loc, r.tx, r.ty, false);
			return;
		}
		if (r.status != ChartPlotterRoute.OK && rev != collisionCache.rev()) {
			request(top, ship, loc, r.tx, r.ty, false);
			return;
		}
		if (r.status == ChartPlotterRoute.OK) {
			if (sailing.speed() == 0) return;
			LocalPoint front = routeLoc(top, ship, loc);
			int fx = top.getBaseX() + Math.floorDiv(front.getX(), TS);
			int fy = top.getBaseY() + Math.floorDiv(front.getY(), TS);
			ChartPlotterRoute nr = r.advance(fx, fy, PRUNE_RADIUS, FOLLOW_RADIUS, PRUNE);
			if (nr == r) return;
			if (nr != null) {
				route = nr;
				rev = collisionCache.rev();
				return;
			}
			request(top, ship, loc, r.tx, r.ty, false);
			return;
		}
		if (!r.start(sx, sy)) request(top, ship, loc, r.tx, r.ty, false);
	}
	public void clear() {
		seq.incrementAndGet();
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
		if (f == ChartPlotterCollisionCache.UNKNOWN || !blocker(f)) return key(tx, ty);
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
	private void request(WorldView top, WorldEntity ship, LocalPoint loc, int tx, int ty, boolean pending) {
		int sx = top.getBaseX() + Math.floorDiv(loc.getX(), TS);
		int sy = top.getBaseY() + Math.floorDiv(loc.getY(), TS);
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
		if (pending) {
			route = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, weight).effort(effort);
			rev = dataRev;
		}
		start();
		exec.execute(() -> {
			BooleanSupplier cancel = () -> id != seq.get() || Thread.currentThread().isInterrupted();
			ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, wc, start, sx, sy, tx, ty, turnBias, reverse, weight, CLEAR_RADIUS, sparse, effort.corridor, cancel).effort(effort);
			if (id == seq.get() && !Thread.currentThread().isInterrupted()) {
				route = r;
				rev = dataRev;
				busy = false;
			}
		});
	}
	private void start() {
		if (exec != null) return;
		exec = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-route");
			t.setDaemon(true);
			return t;
		});
	}
	private static boolean near(int ax, int ay, int bx, int by) {return ChartPlotterMath.chebyshev(ax, ay, bx, by) <= CLEAR_RADIUS;}
	private static boolean blocker(int f) {return (f & ChartPlotterCollisionCache.MOVE) != 0;}
	private static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
}
