package com.chartplotter.collision;

import com.chartplotter.collision.ChartPlotterCollisionData.Chunk;
import com.chartplotter.route.ChartPlotterSparseNodes;
import com.chartplotter.util.ChartPlotterVersions;
import net.runelite.api.WorldView;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Singleton
public final class ChartPlotterCollisionCache {
	private static final String KEY = "collision";
	public static final int UNKNOWN = ChartPlotterCollisionData.UNKNOWN;
	public static final int OPEN = ChartPlotterCollisionData.OPEN;
	public static final int BLOCKED = ChartPlotterCollisionData.BLOCKED;
	public static final int VOID = ChartPlotterCollisionData.VOID;
	public static final int MOVE = ChartPlotterCollisionData.MOVE;
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private final Map<Long, Chunk> chunks = new HashMap<>();
	@Inject private ChartPlotterSparseNodes sparseNodes;
	private volatile ChartPlotterCollisionData view = new ChartPlotterCollisionData(new HashMap<>());
	private volatile boolean loaded;
	private ScheduledExecutorService io;
	private ScheduledFuture<?> flushTask;
	private volatile long rev;
	private long savedRev;
	private volatile long viewRev = -1;
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
		}
		if (ex == null) return;
		if (task != null) task.cancel(false);
		ex.shutdownNow();
	}
	public void capture(WorldView wv) {
		ScheduledExecutorService ex;
		synchronized (this) {
			ex = io;
		}
		if (ex == null) return;
		ChartPlotterCollisionScan scan = ChartPlotterCollisionScan.capture(wv);
		if (scan == null) return;
		synchronized (this) {
			if (io != ex) return;
			try {io.execute(() -> mergeQuiet(ex, scan));} catch (RuntimeException ignored) {}
		}
	}
	public ChartPlotterCollisionData snapshot() {
		if (!loaded) return view;
		long r = rev;
		long vr = viewRev;
		ChartPlotterCollisionData v = view;
		if (vr == r) return v;
		synchronized (this) {
			r = rev;
			if (viewRev != r) {
				view = new ChartPlotterCollisionData(chunks, r);
				viewRev = r;
			}
			return view;
		}
	}
	public long rev() {return loaded ? rev : view.rev;}
	private void mergeQuiet(ScheduledExecutorService ex, ChartPlotterCollisionScan scan) {
		try {
			if (merge(ex, scan)) sparseNodes.invalidate(snapshot());
		} catch (Exception ignored) {
		}
	}
	private boolean merge(ScheduledExecutorService ex, ChartPlotterCollisionScan scan) {
		synchronized (this) {
			if (!loaded || io != ex) return false;
		}
		Map<Long, Builder> data = new HashMap<>();
		for (int sx = 0; sx < scan.width; sx++) {
			if (Thread.currentThread().isInterrupted()) return false;
			for (int sy = 0; sy < scan.height; sy++) {
				int f = scan.flags[sx * scan.height + sy];
				if (f == VOID) continue;
				put(data, scan.baseX + sx, scan.baseY + sy, f);
			}
		}
		for (int i = 0; i < scan.objects.length; i += 4) putObject(data, scan, i);
		synchronized (this) {
			if (!loaded || io != ex) return false;
			boolean changed = merge(data);
			if (changed) scheduleFlush(ex, 30);
			return changed;
		}
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
	private synchronized boolean merge(Map<Long, Builder> data) {
		boolean changed = false;
		for (Map.Entry<Long, Builder> e : data.entrySet()) {
			Chunk old = chunks.get(e.getKey());
			Chunk c = e.getValue().chunk(old);
			if (same(old, c)) continue;
			chunks.put(e.getKey(), c);
			changed = true;
		}
		if (changed) rev++;
		return changed;
	}
	private static boolean same(Chunk a, Chunk b) {
		return a == b || a != null && b != null && a.known == b.known && a.blocked == b.blocked;
	}
	private void loadQuiet(ScheduledExecutorService ex) {
		try {
			load(ex);
		} catch (Exception ignored) {
		}
	}
	private void load(ScheduledExecutorService ex) {
		File f = file();
		String defaultVersion = defaultVersion();
		boolean replace = defaultVersion != null && (!f.isFile() || ChartPlotterVersions.newer(defaultVersion, ChartPlotterVersions.read(dir, KEY)));
		ChartPlotterCollisionCodec.Text seed = replace ? defaults() : null;
		Map<Long, Chunk> data = seed == null ? ChartPlotterCollisionCodec.read(f) : seed.data;
		if (data.isEmpty() && defaultVersion != null && !replace) {
			seed = defaults();
			if (seed != null) {
				replace = true;
				data = seed.data;
			}
		}
		synchronized (this) {
			if (io != ex || loaded) return;
			chunks.clear();
			chunks.putAll(data);
			long r = ++rev;
			savedRev = replace ? r - 1 : r;
			seedVersion = replace && seed != null ? seed.version : null;
			viewRev = -1;
			loaded = true;
		}
		if (replace && seed != null && !flush(ex)) synchronized (this) {if (io == ex) scheduleFlush(ex, 30);}
	}
	private String defaultVersion() {
		try (InputStream in = ChartPlotterCollisionCache.class.getResourceAsStream("/com/chartplotter/collision.txt")) {
			return in == null ? null : ChartPlotterCollisionCodec.readVersion(in);
		} catch (Exception ignored) {
			return null;
		}
	}
	private ChartPlotterCollisionCodec.Text defaults() {
		try (InputStream in = ChartPlotterCollisionCache.class.getResourceAsStream("/com/chartplotter/collision.txt")) {
			return in == null ? null : ChartPlotterCollisionCodec.readText(in);
		} catch (Exception ignored) {
			return null;
		}
	}
	private void scheduleFlush(ScheduledExecutorService ex, int delay) {
		if (flushTask != null && !flushTask.isDone()) return;
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
		ChartPlotterCollisionData out;
		long save;
		synchronized (this) {
			if (io != ex) return false;
			long r = rev;
			if (r == savedRev) return true;
			out = snapshot();
			save = r;
		}
		if (ChartPlotterCollisionCodec.write(dir, file(), out)) {
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
	private File file() {return new File(dir, "collision.bin");}
	private static int clean(int f) {
		return (f & MOVE) == 0 ? OPEN : BLOCKED;
	}
	private static final class Builder {
		long known;
		long blocked;
		private Builder() {}
		void put(int i, int f) {
			long bit = 1L << i;
			known |= bit;
			if (f == BLOCKED) blocked |= bit;
			else blocked &= ~bit;
		}
		Chunk chunk(Chunk base) {
			if (base == null) return new Chunk(known, blocked & known);
			return new Chunk(base.known | known, (base.blocked & ~known | blocked) & (base.known | known));
		}
	}
}
