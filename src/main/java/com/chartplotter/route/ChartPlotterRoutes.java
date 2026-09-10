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
import net.runelite.client.callback.ClientThread;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;


@Singleton
public class ChartPlotterRoutes {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int PRUNE_RADIUS = 20;
	private static final int FOLLOW_RADIUS = 32;
	private static final int PRUNE = 2;
	private static final int CLEAR_RADIUS = 10;
	public static final int MAX_STOPS = 32;
	private static final int ETA_CAP = 600;
	public static final int PV_NONE = 0;
	public static final int PV_OK = 1;
	public static final int PV_SNAP = 2;
	public static final int PV_BAD = 3;
	public static final int PV_PENDING = 4;
	private final ChartPlotterConfig config;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterSailing sailing;
	private final ClientThread clientThread;
	private final AtomicReference<ChartPlotterTrip> trip = new AtomicReference<>(ChartPlotterTrip.empty(0));
	private final AtomicInteger seq = new AtomicInteger();
	private volatile boolean activeBusy;
	private volatile boolean paused;
	private volatile long rev;
	private volatile ChartPlotterRouteComponent component;
	private boolean placing;
	private ArrayDeque<Runnable> placements;
	ThreadPoolExecutor exec;
	private final AtomicReference<Future<?>> work = new AtomicReference<>();
	private PreviewSlot previewSlot;
	private Start checkedStart;
	@Inject
	protected ChartPlotterRoutes(ChartPlotterConfig config, ChartPlotterCollisionCache collisionCache, ChartPlotterSailing sailing, ClientThread clientThread) {
		this.config = config;
		this.collisionCache = collisionCache;
		this.sailing = sailing;
		this.clientThread = clientThread;
	}
	public void set(int tx, int ty) {
		Start s = startTile();
		if (s == null) return;
		ChartPlotterCollisionData data = collisionCache.snapshot();
		int cx = tx;
		int cy = ty;
		if (resolve(s, data, () -> set(cx, cy))) return;
		long t = target(data, tx, ty, s.x, s.y, CLEAR_RADIUS, component);
		tx = (int) (t >> 32);
		ty = (int) t;
		if (data.flagAt(tx, ty) != ChartPlotterCollisionData.OPEN || component != null && !component.contains(tx, ty)) return;
		int id = cancel();
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		ChartPlotterRoute pending = ChartPlotterRoute.pending(s.x, s.y, tx, ty, turnBias, effort.weight).effort(effort);
		trip.set(ChartPlotterTrip.single(id, tx, ty, pending));
		paused = false;
		request(s, data, new boolean[]{true}, id, turnBias, effort, false);
	}
	public void append(int tx, int ty) {
		int cx = tx;
		int cy = ty;
		if (placing) {
			if (placements == null) placements = new ArrayDeque<>();
			placements.add(() -> append(cx, cy));
			return;
		}
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
		if (resolve(s, data, () -> append(cx, cy))) return;
		long t = target(data, tx, ty, sx, sy, CLEAR_RADIUS, component);
		tx = (int) (t >> 32);
		ty = (int) t;
		if (data.flagAt(tx, ty) != ChartPlotterCollisionData.OPEN || component != null && !component.contains(tx, ty)) return;
		if (ChartPlotterMath.chebyshev(old.x(old.size() - 1), old.y(old.size() - 1), tx, ty) <= CLEAR_RADIUS) return;
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
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, data, selected, id, turnBias, effort, false);
	}
	public void move(int stop, int oldX, int oldY, int tx, int ty) {
		Start s = startTile();
		if (s == null) return;
		ChartPlotterTrip old = trip.get();
		if (stop < 0 || stop >= old.size() || old.x(stop) != oldX || old.y(stop) != oldY) return;
		int sx = legStartX(old, stop, s.x);
		int sy = legStartY(old, stop, s.y);
		ChartPlotterCollisionData data = collisionCache.snapshot();
		int cx = tx;
		int cy = ty;
		if (resolve(s, data, () -> move(stop, oldX, oldY, cx, cy))) return;
		long t = target(data, tx, ty, sx, sy, CLEAR_RADIUS, component);
		tx = (int) (t >> 32);
		ty = (int) t;
		if (data.flagAt(tx, ty) != ChartPlotterCollisionData.OPEN || component != null && !component.contains(tx, ty)) return;
		if (old.x(stop) == tx && old.y(stop) == ty || stop > 0 && ChartPlotterMath.chebyshev(old.x(stop - 1), old.y(stop - 1), tx, ty) <= CLEAR_RADIUS || stop + 1 < old.size() && ChartPlotterMath.chebyshev(old.x(stop + 1), old.y(stop + 1), tx, ty) <= CLEAR_RADIUS) return;
		int id = cancel();
		int fx = tx;
		int fy = ty;
		boolean reboard = paused;
		ChartPlotterTrip next = trip.updateAndGet(p -> p.move(id, stop, fx, fy));
		paused = false;
		boolean[] selected = reboard ? all(next) : pending(next);
		selected[stop] = true;
		if (stop + 1 < selected.length) selected[stop + 1] = true;
		if (data.rev != rev) merge(selected, failed(next));
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, data, selected, id, turnBias, effort, false);
	}
	public void remove(int stop) {
		ChartPlotterTrip old = trip.get();
		if (stop < 0 || stop >= old.size()) return;
		int id = cancel();
		ChartPlotterTrip next = trip.updateAndGet(p -> p.remove(id, stop));
		if (next.empty()) {
			paused = false;
			idle();
			return;
		}
		Start s = startTile();
		if (s == null) return;
		boolean[] selected = paused ? all(next) : pending(next);
		paused = false;
		if (none(selected)) return;
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, collisionCache.snapshot(), selected, id, turnBias, effort, false);
	}
	public void remove(int stop, int x, int y) {
		ChartPlotterTrip current = trip.get();
		if (stop < 0 || stop >= current.size() || current.x(stop) != x || current.y(stop) != y) return;
		remove(stop);
	}
	public void truncate(int stop) {
		ChartPlotterTrip old = trip.get();
		if (stop < 0 || stop >= old.size()) return;
		int id = cancel();
		ChartPlotterTrip next = trip.updateAndGet(p -> p.truncate(id, stop));
		if (next.empty()) {
			paused = false;
			idle();
			return;
		}
		Start s = startTile();
		if (s == null) return;
		boolean[] selected = paused ? all(next) : pending(next);
		paused = false;
		if (none(selected)) return;
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, collisionCache.snapshot(), selected, id, turnBias, effort, false);
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
		ChartPlotterCollisionData data = collisionCache.snapshot();
		int sx = append && !p.empty() ? legStartX(p, p.size(), s.x) : s.x;
		int sy = append && !p.empty() ? legStartY(p, p.size(), s.y) : s.y;
		ChartPlotterRouteComponent reachable = component;
		long origin = target(data, s.x, s.y, s.x, s.y, 2, null);
		if (reachable != null && (reachable.data != data || !reachable.contains((int) (origin >> 32), (int) origin))) reachable = null;
		PreviewSlot cached = previewSlot;
		if (cached != null && cached.same(tx, ty, append, sx, sy, p, data, reachable)) return cached.preview;
		Preview result;
		if (append && p.size() >= MAX_STOPS) result = new Preview(PV_BAD, tx, ty);
		else {
			long t = reachable == null ? ChartPlotterCollisionData.key(tx, ty) : target(data, tx, ty, sx, sy, CLEAR_RADIUS, reachable);
			int rx = (int) (t >> 32);
			int ry = (int) t;
			result = new Preview(data.flagAt(rx, ry) == ChartPlotterCollisionData.UNKNOWN || reachable != null && !reachable.contains(rx, ry) || append && !p.empty() && ChartPlotterMath.chebyshev(p.x(p.size() - 1), p.y(p.size() - 1), rx, ry) <= CLEAR_RADIUS ? PV_BAD : reachable == null ? PV_PENDING : rx == tx && ry == ty ? PV_OK : PV_SNAP, rx, ry);
		}
		previewSlot = new PreviewSlot(tx, ty, append, sx, sy, p, data, reachable, result);
		return result;
	}
	public void tick(WorldView top, WorldEntity ship, LocalPoint loc) {
		if (placing) return;
		ChartPlotterTrip p = trip.get();
		if (p.empty()) return;
		Start s = start(top, ship, loc);
		ChartPlotterRoute r = p.active();
		if (paused) {
			paused = false;
			replan(s, all(p));
			return;
		}
		if (r == null) {
			if (!activeBusy) replan(s, missing(p));
			return;
		}
		boolean offRoute = false;
		if (r.status == ChartPlotterRoute.OK) {
			if (sailing.speed() > 0) {
				LocalPoint front = routeLoc(top, ship, loc);
				ChartPlotterRoute nr = r.advance(top.getBaseX() + front.getX() / (double) TS, top.getBaseY() + front.getY() / (double) TS, PRUNE_RADIUS, FOLLOW_RADIUS, PRUNE);
				offRoute = nr == null;
				if (nr != null && nr != r) {
					ChartPlotterTrip next = p.route(0, nr);
					if (!trip.compareAndSet(p, next)) return;
					p = next;
					r = nr;
				}
			}
			if (r.hull != null && r.hull.matches(s.config) && p.reached(s.x, s.y, collisionCache.snapshot())) {
				advance(s);
				return;
			}
		}
		if (activeBusy) return;
		boolean changedStart = checkedStart != null && !checkedStart.samePosition(s);
		checkedStart = s;
		if (r.status == ChartPlotterRoute.PENDING) {
			if (work.get() == null && rev != collisionCache.rev()) {
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
		if (r.status == ChartPlotterRoute.OK && (r.motion == null || r.hull == null) || r.motion != null && r.motion.speed != s.speed || r.hull != null && !r.hull.matches(s.config)) {
			replan(s, all(p));
			return;
		}
		if (work.get() == null && rev != collisionCache.rev()) {
			int id = cancel();
			trip.updateAndGet(current -> current.generation(id));
			request(s, collisionCache.snapshot(), all(p), id, turnBias, effort, true);
			return;
		}
		if (changedStart && (r.status != ChartPlotterRoute.OK || sailing.speed() == 0) || offRoute) {
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
		checkedStart = null;
		idle();
	}
	public void pause() {
		if (!placing && (paused || trip.get().empty())) return;
		int id = cancel();
		trip.updateAndGet(p -> p.generation(id));
		paused = !trip.get().empty();
		rev = 0;
		idle();
	}
	public void stop() {clear();}
	public ChartPlotterRoute route() {return trip.get().active();}
	public ChartPlotterTrip trip() {return trip.get();}
	public boolean canAppend() {return trip.get().size() < MAX_STOPS;}
	public void clearPreview() {previewSlot = null;}
	private LocalPoint routeLoc(WorldView top, WorldEntity ship, LocalPoint loc) {
		WorldEntityConfig wc = ship.getConfig();
		if (wc == null) return loc;
		int o = sailing.actualHeading(ship);
		int x = wc.getBoundsX();
		int y = Math.round(wc.getBoundsY() + (sailing.reversing() ? 1 : -1) * wc.getBoundsHeight() / 2f);
		return new LocalPoint(ChartPlotterMath.rotateX(loc.getX(), o, x, y), ChartPlotterMath.rotateY(loc.getY(), o, x, y), top);
	}
	static long target(ChartPlotterCollisionData data, int tx, int ty, int sx, int sy, int radius, ChartPlotterRouteComponent component) {
		int flag = data.flagAt(tx, ty);
		if (flag == ChartPlotterCollisionData.UNKNOWN || flag == ChartPlotterCollisionData.OPEN && (component == null || component.contains(tx, ty))) return ChartPlotterCollisionData.key(tx, ty);
		int bx = tx;
		int by = ty;
		int distance = Integer.MAX_VALUE;
		long best = Long.MAX_VALUE;
		for (int y = ty - radius; y <= ty + radius; y++) for (int x = tx - radius; x <= tx + radius; x++) {
			if (data.flagAt(x, y) != ChartPlotterCollisionData.OPEN || component != null && !component.contains(x, y)) continue;
			long dx = (long) x - sx;
			long dy = (long) y - sy;
			int d = (x - tx) * (x - tx) + (y - ty) * (y - ty);
			long score = dx * dx + dy * dy;
			if (d < distance || d == distance && score < best) {bx = x; by = y; distance = d; best = score;}
		}
		return ChartPlotterCollisionData.key(bx, by);
	}
	private boolean resolve(Start s, ChartPlotterCollisionData data, Runnable action) {
		long origin = target(data, s.x, s.y, s.x, s.y, 2, null);
		int sx = (int) (origin >> 32);
		int sy = (int) origin;
		ChartPlotterRouteComponent cached = component;
		if (cached != null && cached.data == data && cached.contains(sx, sy)) return false;
		component = null;
		if (data.flagAt(sx, sy) != ChartPlotterCollisionData.OPEN) return false;
		int id = cancel();
		trip.updateAndGet(p -> p.generation(id));
		placing = true;
		start();
		AtomicReference<Future<?>> nextRef = new AtomicReference<>();
		FutureTask<Void> next = new FutureTask<>(() -> {
			try {
				ChartPlotterRouteComponent reachable = ChartPlotterRouteComponent.create(data, sx, sy, () -> id != seq.get());
				if (reachable == null || id != seq.get()) return;
				clientThread.invoke(() -> {
					if (id != seq.get()) return;
					component = reachable;
					placing = false;
					clearPreview();
					ArrayDeque<Runnable> queued = placements;
					placements = null;
					action.run();
					if (queued != null) while (!queued.isEmpty()) queued.remove().run();
					if (id != seq.get()) return;
					Start live = startTile();
					if (trip.get().empty()) idle();
					else if (live == null) pause();
					else replan(live, pending(trip.get()));
				});
			} finally {
				work.compareAndSet(nextRef.get(), null);
			}
		}, null) {
			@Override
			protected void setException(Throwable error) {
				super.setException(error);
				LoggerFactory.getLogger(ChartPlotterRoutes.class).warn("Destination placement failed", error);
				clientThread.invoke(() -> {
					if (id != seq.get()) return;
					pause();
				});
			}
		};
		nextRef.set(next);
		work.set(next);
		exec.execute(next);
		return true;
	}
	Start startTile() {
		if (!sailing.boarded()) return null;
		WorldView top = sailing.top();
		WorldEntity ship = sailing.ship();
		if (top == null || ship == null) return null;
		LocalPoint loc = sailing.anchorLoc(ship);
		if (loc == null) return null;
		return start(top, ship, loc);
	}
	private Start start(WorldView top, WorldEntity ship, LocalPoint loc) {
		return new Start(ship.getConfig(), ChartPlotterMath.worldTile(top.getBaseX(), loc.getX()), ChartPlotterMath.worldTile(top.getBaseY(), loc.getY()), Math.max(0.5, sailing.maxSpeed()));
	}
	private void advance(Start s) {
		boolean reboard = paused;
		int id = cancel();
		paused = false;
		ChartPlotterTrip next = trip.updateAndGet(p -> p.advance(id));
		if (next.empty()) {
			idle();
			return;
		}
		boolean[] selected = reboard ? all(next) : pending(next);
		checkedStart = s;
		if (none(selected)) return;
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		request(s, collisionCache.snapshot(), selected, id, turnBias, effort, false);
	}
	private void replan(Start s, boolean[] selected) {
		if (none(selected)) return;
		ChartPlotterCollisionData data = collisionCache.snapshot();
		ChartPlotterTrip current = trip.get();
		if (data.rev != rev) merge(selected, failed(current));
		int id = cancel();
		ChartPlotterRouteEffort effort = config.routeEffort();
		int turnBias = config.routeShape().bias;
		ChartPlotterTrip next = trip.updateAndGet(p -> p.pending(id, s.x, s.y, turnBias, effort.weight, effort, selected));
		if (next.empty()) return;
		request(s, data, selected, id, turnBias, effort, false);
	}
	private void request(Start s, ChartPlotterCollisionData data, boolean[] selected, int id, int turnBias, ChartPlotterRouteEffort effort, boolean validate) {
		ChartPlotterTrip snapshot = trip.get();
		if (snapshot.generation() != id || snapshot.empty() || none(selected)) return;
		if (selected[0] && !validate) checkedStart = s;
		int weight = effort.weight;
		boolean changed = rev != data.rev;
		boolean[] awaiting = selected.clone();
		for (int i = 0; i < awaiting.length; i++) awaiting[i] |= changed && snapshot.route(i) != null && snapshot.route(i).status == ChartPlotterRoute.OK;
		activeBusy = awaiting[0];
		start();
		AtomicReference<Future<?>> nextRef = new AtomicReference<>();
		FutureTask<Void> next = new FutureTask<>(() -> {
			try {
				BooleanSupplier cancelled = () -> id != seq.get();
				ChartPlotterRouteComponent reachable = component;
				for (int i = 0; i < selected.length; i++) {
					ChartPlotterTrip current = trip.get();
					if (current.generation() != id || cancelled.getAsBoolean()) return;
					ChartPlotterRoute previous = current.route(i);
					int sx = legStartX(current, i, s.x);
					int sy = legStartY(current, i, s.y);
					if (i == 0) {
						long origin = target(data, sx, sy, sx, sy, 2, null);
						sx = (int) (origin >> 32);
						sy = (int) origin;
					}
					boolean connected = previous != null && previous.tx == current.x(i) && previous.ty == current.y(i) && (i == 0 || previous.start(sx, sy));
					if (!awaiting[i] && connected) continue;
					awaiting[i] = true;
					if ((validate || !selected[i]) && connected && !previous.recalculating) {
						ChartPlotterRoute refreshed = previous.refresh(data, cancelled);
						if (refreshed != null) {
							if (refreshed != previous && !trip.compareAndSet(current, current.route(i, refreshed))) {
								i--;
								continue;
							}
							awaiting[i] = false;
							if (i == 0 && id == seq.get()) activeBusy = false;
							continue;
						}
					}
					if (updateFailed(id, i, ChartPlotterRoute.pending(sx, sy, snapshot.x(i), snapshot.y(i), turnBias, weight).effort(effort))) return;
					long started = System.nanoTime();
					ChartPlotterRouteFinder finder = new ChartPlotterRouteFinder(data, s.config, sx, sy, snapshot.x(i), snapshot.y(i), turnBias, s.speed, weight, () -> cancelled.getAsBoolean() || System.nanoTime() - started >= effort.nanos);
					finder.component = reachable;
					if (previous != null && previous.source != null && previous.source.x == sx && previous.source.y == sy && previous.source.matches(data)) finder.source = previous.source;
					if (previous != null && previous.target != null && previous.tx == snapshot.x(i) && previous.ty == snapshot.y(i) && previous.target.matches(data)) finder.target = previous.target;
					ChartPlotterRoute r = finder.find().effort(effort);
					reachable = finder.component;
					if (cancelled.getAsBoolean()) return;
					if (r.status == ChartPlotterRoute.PENDING) r = ChartPlotterRoute.timedOut(sx, sy, snapshot.x(i), snapshot.y(i), turnBias, weight).effort(effort);
					ChartPlotterCollisionData latest = collisionCache.snapshot();
					if (latest != data && r.status == ChartPlotterRoute.OK) {
						ChartPlotterRoute refreshed = r.refresh(latest, cancelled);
						r = refreshed == null ? ChartPlotterRoute.pending(sx, sy, snapshot.x(i), snapshot.y(i), turnBias, weight).effort(effort) : refreshed;
					}
					if (updateFailed(id, i, r)) return;
					awaiting[i] = false;
					if (i == 0 && id == seq.get()) activeBusy = false;
				}
				if (id == seq.get()) {
					rev = data.rev;
					ChartPlotterRouteComponent cached = reachable;
					if (cached != null && cached.data == data && cached != component) clientThread.invoke(() -> {
						if (id == seq.get()) component = cached;
					});
				}
			} finally {
				if (id == seq.get()) activeBusy = false;
				work.compareAndSet(nextRef.get(), null);
			}
		}, null) {
			@Override
			protected void setException(Throwable error) {
				super.setException(error);
				if (id != seq.get()) return;
				LoggerFactory.getLogger(ChartPlotterRoutes.class).warn("Routing failed", error);
				trip.updateAndGet(current -> current.failed(id, awaiting));
				if (id == seq.get()) rev = data.rev;
			}
		};
		nextRef.set(next);
		work.set(next);
		exec.execute(next);
	}
	private boolean updateFailed(int id, int i, ChartPlotterRoute route) {
		while (id == seq.get()) {
			ChartPlotterTrip old = trip.get();
			if (old.generation() != id || i >= old.size()) return true;
			if (trip.compareAndSet(old, old.route(i, route))) return false;
		}
		return true;
	}
	private int cancel() {
		int id = seq.incrementAndGet();
		placing = false;
		placements = null;
		Future<?> old = work.getAndSet(null);
		if (old != null) {
			old.cancel(false);
			ThreadPoolExecutor ex = exec;
			if (ex != null && old instanceof Runnable) ex.remove((Runnable) old);
		}
		activeBusy = false;
		return id;
	}
	private void start() {
		if (exec != null) return;
		exec = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
			Thread t = new Thread(r, "chart-plotter-route");
			t.setDaemon(true);
			return t;
		});
		exec.allowCoreThreadTimeOut(true);
	}
	private void idle() {
		ThreadPoolExecutor ex = exec;
		if (ex != null) {
			ex.shutdownNow();
			exec = null;
		}
		clearPreview();
		component = null;
	}
	private static boolean[] pending(ChartPlotterTrip p) {
		boolean[] selected = new boolean[p.size()];
		for (int i = 0; i < selected.length; i++) selected[i] = p.route(i) == null || p.route(i).status == ChartPlotterRoute.PENDING || p.route(i).recalculating;
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
	private static boolean none(boolean[] selected) {
		for (boolean on : selected) if (on) return false;
		return true;
	}
	static int legStartX(ChartPlotterTrip trip, int stop, int live) {
		return stop == 0 ? live : trip.x(stop - 1);
	}
	static int legStartY(ChartPlotterTrip trip, int stop, int live) {
		return stop == 0 ? live : trip.y(stop - 1);
	}
	private static void merge(boolean[] dst, boolean[] src) {for (int i = 0; i < dst.length; i++) dst[i] |= src[i];}
	static final class Start {
		final WorldEntityConfig config;
		final int x;
		final int y;
		final double speed;
		Start(WorldEntityConfig config, int x, int y, double speed) {
			this.config = config;
			this.x = x;
			this.y = y;
			this.speed = speed;
		}
		boolean samePosition(Start other) {return x == other.x && y == other.y;}
	}
	private static final class PreviewSlot {
		final int tx;
		final int ty;
		final boolean append;
		final int sx;
		final int sy;
		final Object stops;
		final ChartPlotterCollisionData data;
		final ChartPlotterRouteComponent component;
		final Preview preview;
		private PreviewSlot(int tx, int ty, boolean append, int sx, int sy, ChartPlotterTrip trip, ChartPlotterCollisionData data, ChartPlotterRouteComponent component, Preview preview) {
			this.tx = tx;
			this.ty = ty;
			this.append = append;
			this.sx = sx;
			this.sy = sy;
			stops = trip.stopKey();
			this.data = data;
			this.component = component;
			this.preview = preview;
		}
		boolean same(int tx, int ty, boolean append, int sx, int sy, ChartPlotterTrip trip, ChartPlotterCollisionData data, ChartPlotterRouteComponent component) {return this.tx == tx && this.ty == ty && this.append == append && this.sx == sx && this.sy == sy && stops == trip.stopKey() && this.data == data && this.component == component;}
	}
	public static Turn turn(ChartPlotterRoute r, double bx, double by, double speed, double accel, double max, long updated) {
		if (r == null || r.status != ChartPlotterRoute.OK || r.n == 0) return Turn.NONE;
		boolean end = r.n <= 2;
		int cx = end ? r.tx : r.x[1];
		int cy = end ? r.ty : r.y[1];
		double fx = end ? 0.5 : r.offsetX;
		double fy = end ? 0.5 : r.offsetY;
		int ticks = speed > 0 ? eta(Math.hypot(cx + fx - bx, cy + fy - by), speed, accel, max) : -1;
		return new Turn(true, cx, cy, fx, fy, ticks, updated > 0 ? updated : r.updated, end);
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
		public static final Turn NONE = new Turn();
		public final boolean valid;
		public final int x;
		public final int y;
		public final double offsetX;
		public final double offsetY;
		public final int ticks;
		public final long updated;
		public final boolean end;
		private Turn() {this(false, 0, 0, 0, 0, -1, 0, false);}
		private Turn(boolean valid, int x, int y, double offsetX, double offsetY, int ticks, long updated, boolean end) {
			this.valid = valid;
			this.x = x;
			this.y = y;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
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
