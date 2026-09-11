package com.chartplotter.runtime;

import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.collision.ChartPlotterHull;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntBinaryOperator;

@Singleton
public final class ChartPlotterProjection {
	private static final int TS = 128;
	private static final int STEP = 32;
	private static final int HORIZON = 512;
	private final ChartPlotterSailing sailing;
	private final ChartPlotterCollisionCache collisionCache;
	private volatile Request requested;
	volatile Result result;
	ThreadPoolExecutor executor;
	private Future<?> work;
	@Inject
	ChartPlotterProjection(ChartPlotterSailing sailing, ChartPlotterCollisionCache collisionCache) {
		this.sailing = sailing;
		this.collisionCache = collisionCache;
	}
	public void update(WorldView view, WorldEntityConfig config, LocalPoint anchor, int from) {
		Request previous = requested;
		ChartPlotterCollisionData data = collisionCache.snapshot();
		ChartPlotterSailing.Forecast motion = sailing.forecast;
		ChartPlotterSailing.Forecast preview = sailing.preview(collisionCache.refreshing() || sailing.unobserved(view.getBaseX() * TS + anchor.getX(), view.getBaseY() * TS + anchor.getY(), from));
		if (previous != null && previous.same(view, config, anchor, from) && previous.motion.same(motion) && previous.preview.same(preview) && previous.blocker.data == data) return;
		ChartPlotterHull hull = config == null ? null : previous != null && previous.blocker.footprint != null && previous.blocker.footprint.matches(config) ? previous.blocker.footprint : new ChartPlotterHull(config);
		queue(new Request(view, view.getPlane(), anchor.getX(), anchor.getY(), from, motion, preview, new Blocker(view.getBaseX(), view.getBaseY(), data, hull)));
	}
	public void refresh(WorldEntity ship) {
		Request previous = requested;
		LocalPoint anchor = sailing.anchorLoc(ship);
		if (previous == null || previous.view == null || anchor == null) {refresh(); return;}
		update(previous.view, ship.getConfig(), anchor, sailing.heading(ship));
	}
	public void refresh() {
		Request previous = requested;
		if (previous == null) return;
		ChartPlotterCollisionData data = collisionCache.snapshot();
		ChartPlotterSailing.Forecast preview = sailing.preview(collisionCache.refreshing() || sailing.unobserved(previous.blocker.baseX * TS + previous.x, previous.blocker.baseY * TS + previous.y, previous.from));
		if (data == previous.blocker.data && previous.motion.same(sailing.forecast) && previous.preview.same(preview)) return;
		if (data.scene != previous.blocker.data.scene) {
			if (work != null) work.cancel(true);
			work = null;
			result = previous.provisional;
			return;
		}
		queue(new Request(previous.view, previous.plane, previous.x, previous.y, previous.from, sailing.forecast, preview, new Blocker(previous.blocker.baseX, previous.blocker.baseY, data, previous.blocker.footprint)));
	}
	synchronized void queue(Request next) {
		if (work != null) work.cancel(true);
		if (executor != null) executor.getQueue().clear();
		if (next.blocker.footprint == null) {requested = next; result = null; return;}
		next.provisional = new Result(next, new Path[16]);
		requested = next;
		result = next.provisional;
		if (executor == null) {
			executor = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
				Thread thread = new Thread(task, "chart-plotter-forecast");
				thread.setDaemon(true);
				return thread;
			});
			executor.allowCoreThreadTimeOut(true);
		}
		work = executor.submit(() -> {
			Path[] paths = new Path[16];
			for (int i = 0; i < paths.length; i++) {
				if (Thread.currentThread().isInterrupted() || requested != next) return;
				paths[i] = raw(next.x, next.y, next.from, i * 128, HORIZON, true, next.preview, next.blocker);
			}
			if (Thread.currentThread().isInterrupted()) return;
			synchronized (ChartPlotterProjection.this) {
				collisionCache.publish(next.blocker.data, () -> {
					if (requested != next) return false;
					result = new Result(next, paths);
					return true;
				});
			}
		});
	}
	public synchronized void clear() {
		requested = null;
		result = null;
		if (work != null) work.cancel(true);
		work = null;
		if (executor != null) executor.shutdownNow();
		executor = null;
	}
	public Path path(WorldView view, WorldEntityConfig config, LocalPoint anchor, int from, int target, boolean extension) {return path(view, config, anchor, from, target, HORIZON, extension);}
	public Path path(WorldView view, WorldEntityConfig config, LocalPoint anchor, int from, int target, int cap, boolean extension) {
		Result cached = result;
		Request request = cached == null ? requested : cached.request;
		if (cached == null || request == null || !request.same(view, config, anchor, from) || target < 0 || target > 2047 || (target & 127) != 0) return request != null && request.x == anchor.getX() && request.y == anchor.getY() && request.from == from ? request.empty : empty(anchor.getX(), anchor.getY(), from);
		if (!request.motion.same(sailing.forecast) || request.blocker.data != collisionCache.snapshot() || collisionCache.refreshing()) cached = request.provisional;
		return cached.limited(target >>> 7, Math.max(0, Math.min(cap, HORIZON)), extension);
	}
	Path raw(int baseX, int baseY, WorldEntityConfig config, LocalPoint anchor, int from, int target, int cap, boolean extension) {return raw(anchor.getX(), anchor.getY(), from, target, cap, extension, sailing.forecast, new Blocker(baseX, baseY, collisionCache.snapshot(), config == null ? null : new ChartPlotterHull(config)));}
	private static Path empty(int x, int y, int from) {
		Path path = new Path(1);
		path.x[0] = x;
		path.y[0] = y;
		path.o[0] = from;
		path.start = from;
		path.n = 1;
		path.unknown = true;
		path.unknownAt = 1;
		path.clearUntil = 1;
		return path;
	}
	private static Path raw(int ax, int ay, int from, int target, int cap, boolean extension, ChartPlotterSailing.Forecast motion, Blocker blocker) {
		Path path = new Path(cap + 2);
		path.x[0] = ax;
		path.y[0] = ay;
		path.o[0] = from;
		path.start = from;
		path.reverse = motion.reverse;
		path.n = 1;
		int initial = blocker == null || blocker.footprint == null ? ChartPlotterCollisionData.UNKNOWN : blocker.footprint.pose(blocker.data, blocker.baseX + ax / 128.0, blocker.baseY + ay / 128.0, from);
		boolean initialOverlap = initial == ChartPlotterCollisionData.BLOCKED;
		IntBinaryOperator flags = blocker == null ? null : blocker.data::flagAt;
		if (initialOverlap) {
			path.clearFrom = cap + 2;
			Set<Long> overlap = new HashSet<>();
			blocker.footprint.tiles(from, blocker.baseX + ax / 128.0, blocker.baseY + ay / 128.0, 0, 0, 0, (x, y) -> {
				if (blocker.data.flagAt(x, y) == ChartPlotterCollisionData.BLOCKED) overlap.add(ChartPlotterCollisionData.key(x, y));
				return ChartPlotterCollisionData.OPEN;
			});
			flags = (x, y) -> {
				int flag = blocker.data.flagAt(x, y);
				return flag == ChartPlotterCollisionData.BLOCKED && overlap.contains(ChartPlotterCollisionData.key(x, y)) ? ChartPlotterCollisionData.OPEN : flag;
			};
		} else if (initial != ChartPlotterCollisionData.OPEN) path.clearUntil = 1;
		if (initial != ChartPlotterCollisionData.OPEN) {
			path.unknown = true;
			path.unknownAt = 1;
		}
		int horizon = motion.starts && from != target ? 0 : Math.min(cap, motion.horizon);
		double speed = motion.speed;
		int x = ax;
		int y = ay;
		int heading = from;
		double steppedSpeed = Double.NaN;
		ChartPlotterSailing.Step step = null;
		if (motion.horizon == 0 && speed == 0 && motion.maximum > 0) {
			heading = target;
			speed = motion.maximum;
			path.start = target;
			path.o[0] = target;
		}
		for (int i = 0; i < cap; i++) {
			if (Thread.currentThread().isInterrupted()) {path.unknown = true; path.unknownAt = Math.min(path.unknownAt, path.n); path.clearUntil = Math.min(path.clearUntil, path.n); return path;}
			if (i >= horizon && !path.unknown) {path.unknown = true; path.unknownAt = path.n;}
			if (step == null || heading != target || steppedSpeed != speed) {
				steppedSpeed = speed;
				step = ChartPlotterSailing.step(speed, motion.acceleration, motion.maximum, heading, target, motion.turn, motion.reverse);
			}
			if (step.x == 0 && step.y == 0 && step.heading == heading) return path;
			int nx = x + step.x;
			int ny = y + step.y;
			if (!path.blocked && blocker != null && blocker.footprint != null) {
				Block contact = block(blocker, flags, x, y, heading, nx, ny, step.heading);
				if (contact != null) {
					if (!path.unknown) {path.unknown = true; path.unknownAt = path.n;}
					path.clearUntil = Math.min(path.clearUntil, path.n);
					if (contact.flag == ChartPlotterCollisionData.BLOCKED && (contact.sx != x || contact.sy != y || contact.so != heading)) {
						path.x[path.n] = contact.sx;
						path.y[path.n] = contact.sy;
						path.o[path.n++] = contact.so;
					}
					if (contact.flag == ChartPlotterCollisionData.BLOCKED) {
						path.blocked = true;
						path.blockedAt = path.n;
						if (!extension) return path;
					}
				}
				if (initialOverlap && blocker.footprint.pose(blocker.data, blocker.baseX + nx / 128.0, blocker.baseY + ny / 128.0, step.heading) == ChartPlotterCollisionData.OPEN) {
					initialOverlap = false;
					flags = blocker.data::flagAt;
					path.clearFrom = path.n + 1;
				}
			}
			x = nx;
			y = ny;
			heading = step.heading;
			speed = step.speed;
			path.x[path.n] = x;
			path.y[path.n] = y;
			path.o[path.n++] = heading;
			if (!path.unknown) path.verifiedTicks++;
		}
		return path;
	}
	public static int match(Path a, Path b) {
		int n = Math.min(Math.min(a.n, a.blockedAt), Math.min(b.n, b.blockedAt));
		for (int i = 0; i < n; i++) if (a.x[i] != b.x[i] || a.y[i] != b.y[i] || a.o[i] != b.o[i]) return i;
		return n;
	}
	public static void rect(WorldEntityConfig wc, float[] x, float[] y) {
		float ox = wc != null ? wc.getBoundsX() : 0;
		float hw = wc != null ? wc.getBoundsWidth() / 2f : TS;
		float oy = wc != null ? wc.getBoundsY() : 0;
		float hh = wc != null ? wc.getBoundsHeight() / 2f : TS;
		x[0] = ox + hw;
		x[1] = ox + hw;
		x[2] = ox - hw;
		x[3] = ox - hw;
		y[0] = oy - hh;
		y[1] = oy + hh;
		y[2] = oy + hh;
		y[3] = oy - hh;
	}
	private static Block block(Blocker b, IntBinaryOperator flags, int ax, int ay, int ao, int bx, int by, int bo) {
		int full = b.footprint.sweep(b.baseX + ax / 128.0, b.baseY + ay / 128.0, ao, b.baseX + bx / 128.0, b.baseY + by / 128.0, bo, flags);
		if (full == ChartPlotterCollisionData.OPEN) return null;
		if (full == ChartPlotterCollisionData.UNKNOWN || b.footprint.tiles(ao, b.baseX + ax / 128.0, b.baseY + ay / 128.0, 0, 0, 0, flags) == ChartPlotterCollisionData.BLOCKED) return new Block(ax, ay, ao, full);
		int steps = Math.max(1, (Math.max(Math.abs(bx - ax), Math.abs(by - ay)) + STEP - 1) / STEP);
		int px = ax;
		int py = ay;
		int po = ao;
		for (int i = 1; i <= steps; i++) {
			int qx = ax + (bx - ax) * i / steps;
			int qy = ay + (by - ay) * i / steps;
			int flag = b.footprint.sweep(b.baseX + ax / 128.0, b.baseY + ay / 128.0, ao, b.baseX + qx / 128.0, b.baseY + qy / 128.0, bo, flags);
			if (flag == ChartPlotterCollisionData.BLOCKED) return new Block(px, py, po, full);
			px = qx;
			py = qy;
			po = bo;
		}
		return new Block(ax, ay, ao, full);
	}
	static final class Blocker {
		final int baseX;
		final int baseY;
		final ChartPlotterCollisionData data;
		final ChartPlotterHull footprint;
		Blocker(int baseX, int baseY, ChartPlotterCollisionData data, ChartPlotterHull footprint) {this.baseX = baseX; this.baseY = baseY; this.data = data; this.footprint = footprint;}
	}
	private static final class Block {
		final int sx;
		final int sy;
		final int so;
		final int flag;
		Block(int sx, int sy, int so, int flag) {this.sx = sx; this.sy = sy; this.so = so; this.flag = flag;}
	}
	static final class Request {
		final WorldView view;
		final int x;
		final int y;
		final int from;
		final int plane;
		final ChartPlotterSailing.Forecast motion;
		final ChartPlotterSailing.Forecast preview;
		final Blocker blocker;
		final Path empty;
		Result provisional;
		Request(WorldView view, int plane, int x, int y, int from, ChartPlotterSailing.Forecast motion, ChartPlotterSailing.Forecast preview, Blocker blocker) {this.view = view; this.plane = plane; this.x = x; this.y = y; this.from = from; this.motion = motion; this.preview = preview; this.blocker = blocker; empty = empty(x, y, from);}
		boolean same(WorldView view, WorldEntityConfig config, LocalPoint anchor, int from) {return this.view == view && plane == view.getPlane() && blocker.baseX == view.getBaseX() && blocker.baseY == view.getBaseY() && x == anchor.getX() && y == anchor.getY() && this.from == from && (config == null ? blocker.footprint == null : blocker.footprint != null && blocker.footprint.matches(config));}
	}
	static final class Result {
		final Request request;
		final Path[] paths;
		final View[] views = new View[32];
		int next;
		Result(Request request, Path[] paths) {this.request = request; this.paths = paths;}
		Path limited(int heading, int cap, boolean extension) {
			for (View view : views) if (view != null && view.heading == heading && view.cap == cap && view.extension == extension) return view.path;
			if (paths[heading] == null) paths[heading] = raw(request.x, request.y, request.from, heading * 128, HORIZON, true, request.preview, null);
			Path path = new Path(paths[heading], cap, extension);
			views[next++ & 31] = new View(heading, cap, extension, path);
			return path;
		}
	}
	private static final class View {
		final int heading;
		final int cap;
		final boolean extension;
		final Path path;
		View(int heading, int cap, boolean extension, Path path) {this.heading = heading; this.cap = cap; this.extension = extension; this.path = path;}
	}
	public static final class Path {
		public final int[] x;
		public final int[] y;
		public final int[] o;
		public int n;
		public int verifiedTicks;
		public int start;
		public boolean reverse;
		public boolean blocked;
		public boolean unknown;
		public int blockedAt = Integer.MAX_VALUE;
		public int unknownAt = Integer.MAX_VALUE;
		int clearFrom;
		int clearUntil = Integer.MAX_VALUE;
		private Path(int cap) {x = new int[cap]; y = new int[cap]; o = new int[cap];}
		private Path(Path source, int cap, boolean extension) {
			x = source.x;
			y = source.y;
			o = source.o;
			n = Math.min(source.n, cap + 1);
			verifiedTicks = Math.min(source.verifiedTicks, cap);
			blocked = source.blocked && source.blockedAt <= n;
			blockedAt = blocked ? source.blockedAt : Integer.MAX_VALUE;
			if (!extension) n = Math.min(n, blockedAt);
			unknownAt = source.unknownAt;
			unknown = unknownAt <= n;
			clearFrom = source.clearFrom;
			clearUntil = source.clearUntil;
			start = source.start;
			reverse = source.reverse;
		}
		public int prev(int i) {return i > 0 ? o[i - 1] : start;}
		boolean clear(int i) {return i >= clearFrom && i < clearUntil;}
	}
}
