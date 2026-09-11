package com.chartplotter.collision;

import com.chartplotter.collision.ChartPlotterCollisionData.Chunk;
import com.chartplotter.util.ChartPlotterVersions;
import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.RuneLite;

import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Singleton
public class ChartPlotterCollisionCache {
	private static final String KEY = "collision";
	public static final int UNKNOWN = ChartPlotterCollisionData.UNKNOWN;
	public static final int OPEN = ChartPlotterCollisionData.OPEN;
	public static final int BLOCKED = ChartPlotterCollisionData.BLOCKED;
	public static final int VOID = ChartPlotterCollisionData.VOID;
	public static final int MOVE = ChartPlotterCollisionData.MOVE;
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private final Object fileLock = new Object();
	private final Map<Long, Chunk> chunks = new HashMap<>();
	private volatile ChartPlotterCollisionData view = new ChartPlotterCollisionData(new HashMap<>());
	volatile boolean loaded;
	volatile ScheduledExecutorService io;
	ScheduledFuture<?> flushTask;
	private volatile long rev;
	private long savedRev;
	private volatile ChartPlotterCollisionData published = view;
	private final Map<Long, Chunk> live = new HashMap<>();
	private long liveScene = Long.MIN_VALUE;
	private final Map<Long, Long> pending = new HashMap<>();
	private final Set<Long> dirty = new HashSet<>();
	private final ChartPlotterCollisionObjects objects = new ChartPlotterCollisionObjects();
	private WorldView sceneView;
	private boolean capturePending;
	private int baseX;
	private int baseY;
	private volatile long generation;
	private long publication;
	private String seedVersion;
	public synchronized void start() {
		if (io != null) return;
		ScheduledThreadPoolExecutor next = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "chart-plotter-collision");
			t.setDaemon(true);
			return t;
		});
		next.setKeepAliveTime(35, TimeUnit.SECONDS);
		next.allowCoreThreadTimeOut(true);
		next.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		io = next;
		ScheduledExecutorService ex = io;
		if (!loaded) ex.execute(() -> loadQuiet(ex));
		else if (savedRev != rev) scheduleFlush(ex, 0);
	}
	public void stop() {
		ScheduledExecutorService ex;
		ScheduledFuture<?> task;
		synchronized (this) {
			ex = io;
			io = null;
			task = flushTask;
			flushTask = null;
			invalidateScene();
		}
		if (ex == null) return;
		if (task != null) task.cancel(false);
		ex.shutdownNow();
	}
	public synchronized void invalidateScene() {
		generation++;
		sceneView = null;
		capturePending = true;
		objects.blockers.clear();
		dirty.clear();
		pending.clear();
		view = new ChartPlotterCollisionData(Map.of(), ++publication, generation);
		published = view;
	}
	public void capture(WorldView wv) {
		if (io == null || wv == null) return;
		if (wv != sceneView || baseX != wv.getBaseX() || baseY != wv.getBaseY()) {
			invalidateScene();
			sceneView = wv;
			baseX = wv.getBaseX();
			baseY = wv.getBaseY();
		}
		if (!capturePending || !ChartPlotterCollisionScan.ready(wv)) return;
		objects.seed(wv);
		ChartPlotterCollisionScan scan = ChartPlotterCollisionScan.capture(wv, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, objects.blockers);
		if (scan == null) return;
		capturePending = false;
		mark(scan.baseX, scan.baseY, scan.baseX + scan.width - 1, scan.baseY + scan.height - 1);
		submit(List.of(scan));
	}
	public void changed(TileObject object, boolean spawned) {
		if (io == null || sceneView == null || object == null || object.getWorldView() != sceneView || object.getPlane() != 0 || sceneView.isInstance()) return;
		objects.changed(object, spawned);
		LocalPoint location = object.getLocalLocation();
		Point min = object instanceof GameObject ? ((GameObject) object).getSceneMinLocation() : new Point(location.getSceneX(), location.getSceneY());
		Point max = object instanceof GameObject ? ((GameObject) object).getSceneMaxLocation() : min;
		if (min == null || max == null) return;
		int minX = Math.max(ChartPlotterCollisionScan.EDGE, min.getX() - 1);
		int minY = Math.max(ChartPlotterCollisionScan.EDGE, min.getY() - 1);
		int maxX = Math.min(sceneView.getSizeX() - ChartPlotterCollisionScan.EDGE - 1, max.getX() + 1);
		int maxY = Math.min(sceneView.getSizeY() - ChartPlotterCollisionScan.EDGE - 1, max.getY() + 1);
		if (minX <= maxX && minY <= maxY) mark(baseX + minX, baseY + minY, baseX + maxX, baseY + maxY);
	}
	synchronized void mark(int minX, int minY, int maxX, int maxY) {
		long revision = publication + 1;
		boolean changed = false;
		for (int x = minX >> 3; x <= maxX >> 3; x++) for (int y = minY >> 3; y <= maxY >> 3; y++) {
			long key = ChartPlotterCollisionData.key(x, y);
			if (!dirty.add(key)) continue;
			changed = true;
			pending.put(key, revision);
		}
		if (!changed) return;
		publication = revision;
		published = new ChartPlotterCollisionData(view, pending.keySet(), publication, generation);
	}
	public void refresh(WorldView wv) {
		if (io == null || wv == null) return;
		if (wv != sceneView || baseX != wv.getBaseX() || baseY != wv.getBaseY() || capturePending) {capture(wv); return;}
		if (dirty.isEmpty()) return;
		List<ChartPlotterCollisionScan> scans = new ArrayList<>();
		for (long key : dirty) {
			int x = (int) (key >> 32) * 8 - baseX;
			int y = (int) key * 8 - baseY;
			ChartPlotterCollisionScan scan = ChartPlotterCollisionScan.capture(wv, x, y, x + 8, y + 8, objects.blockers);
			if (scan != null) scans.add(scan);
		}
		if (!scans.isEmpty()) submit(scans);
	}
	synchronized void submit(List<ChartPlotterCollisionScan> scans) {
		ScheduledExecutorService ex = io;
		if (ex == null) return;
		long scene = generation;
		Map<Long, Long> versions = new HashMap<>();
		for (ChartPlotterCollisionScan scan : scans) for (int x = scan.baseX >> 3; x <= (scan.baseX + scan.width - 1) >> 3; x++) for (int y = scan.baseY >> 3; y <= (scan.baseY + scan.height - 1) >> 3; y++) {
			long key = ChartPlotterCollisionData.key(x, y);
			if (dirty.remove(key)) versions.put(key, pending.get(key));
		}
		try {ex.execute(() -> merge(ex, scene, versions, scans));} catch (RuntimeException ignored) {}
	}
	public ChartPlotterCollisionData snapshot() {return published;}
	public boolean refreshing() {return capturePending || published.pending();}
	public long rev() {return published.rev;}
	public synchronized boolean publish(ChartPlotterCollisionData expected, BooleanSupplier update) {return snapshot() == expected && update.getAsBoolean();}
	void merge(ScheduledExecutorService ex, long scene, Map<Long, Long> versions, List<ChartPlotterCollisionScan> scans) {
		synchronized (fileLock) {
			if (!loaded || io != ex || generation != scene) return;
			Map<Long, Builder> recorded = new HashMap<>();
			Map<Long, Builder> current = new HashMap<>();
			for (ChartPlotterCollisionScan scan : scans) {
				if (io != ex || generation != scene) return;
				for (int sx = 0; sx < scan.width; sx++) for (int sy = 0; sy < scan.height; sy++) {
					int f = scan.flags[sx * scan.height + sy];
					int x = scan.baseX + sx;
					int y = scan.baseY + sy;
					if (f != VOID) put(recorded, x, y, f);
					put(current, x, y, f);
				}
				for (int i = 0; i < scan.objects.length; i += 4) putObject(current, scan, i);
			}
			if (io != ex || generation != scene) return;
			if (merge(recorded)) {
				rev++;
				scheduleFlush(ex, 30);
			}
			boolean changedScene = liveScene != scene;
			if (changedScene) {live.clear(); liveScene = scene;}
			ChartPlotterCollisionData previousView = view;
			Map<Long, Chunk> changes = new HashMap<>();
			for (Map.Entry<Long, Builder> entry : current.entrySet()) {
				long key = entry.getKey();
				Chunk previous = live.getOrDefault(key, chunks.get(key));
				Chunk next = entry.getValue().chunk(previous);
				live.put(key, next);
				if (!changedScene && previousView.chunk((int) (key >> 32), (int) key) != (next.empty() ? null : next)) changes.put(key, next);
			}
			if (changedScene) publish(ex, scene, versions);
			else publish(ex, scene, versions, changes.isEmpty() ? previousView : new ChartPlotterCollisionData(previousView, changes));
		}
	}
	private void publish(ScheduledExecutorService ex, long scene, Map<Long, Long> versions) {
		Map<Long, Chunk> combined = new HashMap<>(chunks);
		if (liveScene == scene) combined.putAll(live);
		ChartPlotterCollisionData next = new ChartPlotterCollisionData(combined);
		publish(ex, scene, versions, next);
	}
	private synchronized void publish(ScheduledExecutorService ex, long scene, Map<Long, Long> versions, ChartPlotterCollisionData next) {
		if (io != ex || generation != scene) return;
		for (Map.Entry<Long, Long> entry : versions.entrySet()) pending.remove(entry.getKey(), entry.getValue());
		view = new ChartPlotterCollisionData(next, Set.of(), ++publication, scene);
		published = pending.isEmpty() ? view : new ChartPlotterCollisionData(view, pending.keySet(), publication, scene);
	}
	private static void putObject(Map<Long, Builder> data, ChartPlotterCollisionScan scan, int i) {
		for (int sx = scan.objects[i]; sx <= scan.objects[i + 2]; sx++) {
			for (int sy = scan.objects[i + 1]; sy <= scan.objects[i + 3]; sy++) {
				put(data, scan.baseX + sx, scan.baseY + sy, BLOCKED);
			}
		}
	}
	private static void put(Map<Long, Builder> data, int wx, int wy, int f) {
		f = clean(f);
		int cx = wx >> 3;
		int cy = wy >> 3;
		long k = ChartPlotterCollisionData.key(cx, cy);
		int i = (wx & 7) + ((wy & 7) << 3);
		Builder b = data.computeIfAbsent(k, k1 -> new Builder());
		b.put(i, f);
	}
	private boolean merge(Map<Long, Builder> data) {
		boolean changed = false;
		for (Map.Entry<Long, Builder> e : data.entrySet()) {
			Chunk old = chunks.get(e.getKey());
			Chunk c = e.getValue().chunk(old);
			if (same(old, c)) continue;
			chunks.put(e.getKey(), c);
			changed = true;
		}
		return changed;
	}
	private static boolean same(Chunk a, Chunk b) {
		return a == b || a != null && b != null && a.known == b.known && a.blocked == b.blocked;
	}
	private void loadQuiet(ScheduledExecutorService ex) {
		synchronized (fileLock) {
			if (io != ex) return;
			try {
				load(ex);
			} catch (Exception ignored) {
			}
		}
	}
	private void load(ScheduledExecutorService ex) {
		BooleanSupplier cancel = () -> io != ex;
		File f = file();
		String defaultVersion = defaultVersion(cancel);
		if (cancel.getAsBoolean()) return;
		boolean replace = defaultVersion != null && (!f.isFile() || ChartPlotterVersions.newer(defaultVersion, ChartPlotterVersions.read(dir, KEY)));
		ChartPlotterCollisionCodec.Text seed = replace ? defaults(cancel) : null;
		Map<Long, Chunk> data = seed == null ? ChartPlotterCollisionCodec.read(f, cancel) : seed.data;
		if (cancel.getAsBoolean()) return;
		if (data.isEmpty() && defaultVersion != null && !replace) {
			seed = defaults(cancel);
			if (seed != null) {
				replace = true;
				data = seed.data;
			}
		}
		if (io != ex || loaded) return;
		chunks.clear();
		chunks.putAll(data);
		synchronized (this) {
			if (io != ex || loaded) return;
			long r = ++rev;
			savedRev = replace ? r - 1 : r;
			seedVersion = replace && seed != null ? seed.version : null;
			loaded = true;
		}
		publish(ex, generation, Map.of());
		if (replace && seed != null && !flush(ex)) synchronized (this) {if (io == ex) scheduleFlush(ex, 30);}
	}
	private String defaultVersion(BooleanSupplier cancel) {
		try (InputStream in = ChartPlotterCollisionCache.class.getResourceAsStream("/com/chartplotter/collision.txt")) {
			return in == null ? null : ChartPlotterCollisionCodec.readVersion(in, cancel);
		} catch (Exception ignored) {
			return null;
		}
	}
	private ChartPlotterCollisionCodec.Text defaults(BooleanSupplier cancel) {
		try (InputStream in = ChartPlotterCollisionCache.class.getResourceAsStream("/com/chartplotter/collision.txt")) {
			return in == null ? null : ChartPlotterCollisionCodec.readText(in, cancel);
		} catch (Exception ignored) {
			return null;
		}
	}
	private synchronized void scheduleFlush(ScheduledExecutorService ex, int delay) {
		if (io != ex || flushTask != null && !flushTask.isDone()) return;
		try {flushTask = ex.schedule(() -> flushQuiet(ex), delay, TimeUnit.SECONDS);} catch (RuntimeException ignored) {flushTask = null;}
	}
	private void flushQuiet(ScheduledExecutorService ex) {
		try {
			flush(ex);
		} catch (Exception ignored) {
		}
		synchronized (this) {
			if (io != ex) return;
			flushTask = null;
			if (savedRev != rev) scheduleFlush(ex, 30);
		}
	}
	private boolean flush(ScheduledExecutorService ex) {
		synchronized (fileLock) {
			if (io != ex) return false;
			long save = rev;
			if (save == savedRev) return true;
			ChartPlotterCollisionData out = new ChartPlotterCollisionData(chunks, save);
			if (ChartPlotterCollisionCodec.write(dir, file(), out, () -> io != ex)) {
				String seed;
				synchronized (this) {
					if (io != ex) return false;
					if (savedRev < save) savedRev = save;
					seed = seedVersion;
					seedVersion = null;
				}
				if (seed != null) ChartPlotterVersions.write(dir, KEY, seed);
				return true;
			}
			return false;
		}
	}
	private File file() {return new File(dir, "collision.bin");}
	private static int clean(int f) {
		return f == VOID || f == UNKNOWN ? UNKNOWN : (f & MOVE) == 0 ? OPEN : BLOCKED;
	}
	private static final class Builder {
		long present;
		long known;
		long blocked;
		private Builder() {}
		void put(int i, int f) {
			long bit = 1L << i;
			present |= bit;
			if (f == UNKNOWN) known &= ~bit;
			else known |= bit;
			if (f == BLOCKED) blocked |= bit;
			else blocked &= ~bit;
		}
		Chunk chunk(Chunk base) {
			long nextKnown = base == null ? known : base.known & ~present | known;
			long nextBlocked = (base == null ? blocked : base.blocked & ~present | blocked) & nextKnown;
			return base != null && base.known == nextKnown && base.blocked == nextBlocked ? base : new Chunk(nextKnown, nextBlocked);
		}
	}
}
