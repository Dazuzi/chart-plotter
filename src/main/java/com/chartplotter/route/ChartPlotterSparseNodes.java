package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.util.ChartPlotterVersions;
import net.runelite.client.RuneLite;

import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public final class ChartPlotterSparseNodes {
	private static final String KEY = "sparse";
	private static final int RETRY_SECONDS = 30;
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private final ArrayList<Op> pending = new ArrayList<>();
	private int[] x = new int[64];
	private int[] y = new int[64];
	private int n;
	private boolean active;
	private boolean loaded;
	private long version;
	private long savedVersion;
	private String seedVersion;
	private ScheduledExecutorService io;
	private ScheduledFuture<?> flushTask;
	public synchronized void start() {
		if (active) return;
		active = true;
		ScheduledExecutorService ex = executor();
		if (!loaded) ex.execute(() -> loadQuiet(ex));
		else if (savedVersion != version) flush(ex, 0);
	}
	private ScheduledExecutorService executor() {
		if (io != null) return io;
		io = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-sparse");
			t.setDaemon(true);
			return t;
		});
		return io;
	}
	public void stop() {
		ScheduledExecutorService ex;
		ScheduledFuture<?> task;
		synchronized (this) {
			active = false;
			ex = io;
			io = null;
			task = flushTask;
			flushTask = null;
			pending.clear();
		}
		if (ex == null) return;
		if (task != null) task.cancel(false);
		ex.shutdownNow();
	}
	public synchronized Snapshot snapshot() {return snap();}
	public synchronized long version() {return version;}
	public synchronized void add(int wx, int wy) {
		if (!loaded) {
			pending(() -> add(wx, wy));
			return;
		}
		if (n == x.length) grow();
		x[n] = wx;
		y[n] = wy;
		n++;
		changed();
	}
	public synchronized void move(int ox, int oy, int wx, int wy) {
		if (!loaded) {
			pending(() -> move(ox, oy, wx, wy));
			return;
		}
		for (int i = 0; i < n; i++) {
			if (x[i] != ox || y[i] != oy) continue;
			if (x[i] == wx && y[i] == wy) return;
			x[i] = wx;
			y[i] = wy;
			changed();
			return;
		}
	}
	public synchronized void remove(int i) {
		if (!loaded) {
			pending(() -> remove(i));
			return;
		}
		if (i < 0 || i >= n) return;
		int m = n - i - 1;
		if (m > 0) {
			System.arraycopy(x, i + 1, x, i, m);
			System.arraycopy(y, i + 1, y, i, m);
		}
		n--;
		changed();
	}
	public synchronized void remove(int wx, int wy) {
		if (!loaded) {
			pending(() -> remove(wx, wy));
			return;
		}
		for (int i = 0; i < n; i++) {
			if (x[i] != wx || y[i] != wy) continue;
			remove(i);
			return;
		}
	}
	public synchronized void invalidate(ChartPlotterCollisionData data) {
		if (!loaded) {
			pending(() -> invalidate(data));
			return;
		}
		int w = 0;
		for (int i = 0; i < n; i++) {
			if (blocked(data, x[i], y[i])) continue;
			x[w] = x[i];
			y[w++] = y[i];
		}
		if (w == n) return;
		n = w;
		changed();
	}
	private void loadQuiet(ScheduledExecutorService ex) {
		Load data = loadData();
		synchronized (this) {
			if (io != ex || loaded) return;
			if (data.nodes != null) set(data.nodes);
			loaded = true;
			version++;
			savedVersion = data.replace ? version - 1 : version;
			seedVersion = data.replace ? data.seedVersion : null;
			applyPending();
			if (savedVersion != version) flush(ex, 0);
		}
	}
	private Load loadData() {
		File f = file();
		ChartPlotterSparseCodec.Text seed = defaults();
		boolean replace = seed != null && (!f.isFile() || ChartPlotterVersions.newer(seed.version, ChartPlotterVersions.read(dir, KEY)));
		ChartPlotterSparseNodes.Snapshot nodes = replace ? seed.nodes : ChartPlotterSparseCodec.read(f);
		if (nodes == null && seed != null) {
			replace = true;
			nodes = seed.nodes;
		}
		return new Load(nodes, replace, seed == null ? null : seed.version);
	}
	private ChartPlotterSparseCodec.Text defaults() {
		try (InputStream in = ChartPlotterSparseNodes.class.getResourceAsStream("/com/chartplotter/sparse-nodes.txt")) {
			return in == null ? null : ChartPlotterSparseCodec.readText(in);
		} catch (Exception ignored) {
			return null;
		}
	}
	private void changed() {
		version++;
		flush(io, 0);
	}
	private Snapshot snap() {return new Snapshot(Arrays.copyOf(x, n), Arrays.copyOf(y, n), version);}
	private void pending(Op op) {
		if (io == null) return;
		pending.add(op);
	}
	private void applyPending() {
		for (Op op : pending) op.apply();
		pending.clear();
	}
	private void flush(ScheduledExecutorService ex, int delay) {
		if (ex == null) return;
		if (flushTask != null && !flushTask.isDone()) return;
		try {flushTask = ex.schedule(() -> flushQuiet(ex), delay, TimeUnit.SECONDS);} catch (RuntimeException ignored) {flushTask = null;}
	}
	private void flushQuiet(ScheduledExecutorService ex) {
		Snapshot nodes;
		long save;
		synchronized (this) {
			if (io != ex) return;
			if (savedVersion == version) return;
			nodes = snap();
			save = version;
		}
		boolean ok = ChartPlotterSparseCodec.write(dir, file(), nodes);
		String seed = null;
		synchronized (this) {
			if (io != ex) return;
			if (ok) {
				if (savedVersion < save) savedVersion = save;
				seed = seedVersion;
				seedVersion = null;
			}
			flushTask = null;
			if (active && savedVersion != version) flush(ex, ok ? 0 : RETRY_SECONDS);
		}
		if (seed != null) ChartPlotterVersions.write(dir, KEY, seed);
	}
	private void set(ChartPlotterSparseNodes.Snapshot nodes) {
		ensure(nodes.x.length);
		System.arraycopy(nodes.x, 0, x, 0, nodes.x.length);
		System.arraycopy(nodes.y, 0, y, 0, nodes.y.length);
		n = nodes.x.length;
	}
	private File file() {return new File(dir, "sparse.bin");}
	private void ensure(int c) {
		while (x.length < c) grow();
	}
	private void grow() {
		x = Arrays.copyOf(x, x.length << 1);
		y = Arrays.copyOf(y, y.length << 1);
	}
	private static boolean blocked(ChartPlotterCollisionData data, int wx, int wy) {return data.flagAt(wx, wy) == ChartPlotterCollisionCache.BLOCKED;}
	private interface Op {
		void apply();
	}
	private static final class Load {
		final Snapshot nodes;
		final boolean replace;
		final String seedVersion;
		private Load(Snapshot nodes, boolean replace, String seedVersion) {
			this.nodes = nodes;
			this.replace = replace;
			this.seedVersion = seedVersion;
		}
	}
	public static final class Snapshot {
		public final int[] x;
		public final int[] y;
		public final long version;
		public Snapshot(int[] x, int[] y) {
			this(x, y, 0);
		}
		public Snapshot(int[] x, int[] y, long version) {
			this.x = x;
			this.y = y;
			this.version = version;
		}
	}
}
