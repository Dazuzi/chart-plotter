package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.util.ChartPlotterVersions;
import net.runelite.client.RuneLite;

import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Singleton
public final class ChartPlotterSparseNodes {
	private static final String KEY = "sparse";
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private int[] x = new int[64];
	private int[] y = new int[64];
	private int n;
	private boolean loaded;
	private long version;
	private long savedVersion;
	private ExecutorService io;
	private Future<?> flushTask;
	public synchronized void start() {
		if (io != null) return;
		io = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-sparse");
			t.setDaemon(true);
			return t;
		});
		ExecutorService ex = io;
		if (!loaded) ex.execute(() -> loadQuiet(ex));
		else if (savedVersion != version) flush(ex);
	}
	public void stop() {
		ExecutorService ex;
		Future<?> task;
		synchronized (this) {
			ex = io;
			io = null;
			task = flushTask;
			flushTask = null;
		}
		if (task != null) task.cancel(false);
		if (ex != null) ex.shutdownNow();
	}
	public synchronized Snapshot snapshot() {return snap();}
	public synchronized long version() {return version;}
	public synchronized void add(int wx, int wy) {
		if (!loaded) return;
		if (n == x.length) grow();
		x[n] = wx;
		y[n] = wy;
		n++;
		changed();
	}
	public synchronized void move(int ox, int oy, int wx, int wy) {
		if (!loaded) return;
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
		if (!loaded) return;
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
		if (!loaded) return;
		for (int i = 0; i < n; i++) {
			if (x[i] != wx || y[i] != wy) continue;
			remove(i);
			return;
		}
	}
	public synchronized void invalidate(ChartPlotterCollisionData data) {
		if (!loaded) return;
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
	private void loadQuiet(ExecutorService ex) {
		Load data = loadData();
		long save;
		synchronized (this) {
			if (io != ex || loaded) return;
			if (data.nodes != null) set(data.nodes);
			loaded = true;
			version++;
			save = version;
			savedVersion = data.replace ? version - 1 : version;
		}
		if (!data.replace || data.nodes == null) return;
		if (!ChartPlotterSparseCodec.write(dir, file(), data.nodes)) return;
		ChartPlotterVersions.write(dir, KEY, data.seedVersion);
		synchronized (this) {
			if (savedVersion < save) savedVersion = save;
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
		flush(io);
	}
	private Snapshot snap() {return new Snapshot(Arrays.copyOf(x, n), Arrays.copyOf(y, n), version);}
	private void flush(ExecutorService ex) {
		if (ex == null) return;
		if (flushTask != null && !flushTask.isDone()) return;
		try {flushTask = ex.submit(() -> flushQuiet(ex));} catch (RuntimeException ignored) {flushTask = null;}
	}
	private void flushQuiet(ExecutorService ex) {
		Snapshot nodes;
		long save;
		synchronized (this) {
			if (io != ex || savedVersion == version) return;
			nodes = snap();
			save = version;
		}
		boolean ok = ChartPlotterSparseCodec.write(dir, file(), nodes);
		synchronized (this) {
			if (io != ex) return;
			if (ok && savedVersion < save) savedVersion = save;
			flushTask = null;
			if (ok && savedVersion != version) flush(ex);
		}
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
