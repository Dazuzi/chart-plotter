package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.util.ChartPlotterVersions;
import net.runelite.client.RuneLite;

import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;

@Singleton
public final class ChartPlotterSparseNodes {
	private static final String KEY = "sparse";
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private int[] x = new int[64];
	private int[] y = new int[64];
	private int n;
	private boolean loaded;
	private long version;
	public synchronized Snapshot snapshot() {
		load();
		return new Snapshot(Arrays.copyOf(x, n), Arrays.copyOf(y, n), version);
	}
	public synchronized long version() {
		load();
		return version;
	}
	public synchronized void add(int wx, int wy) {
		load();
		if (n == x.length) grow();
		x[n] = wx;
		y[n] = wy;
		n++;
		version++;
		flushQuiet();
	}
	public synchronized void move(int ox, int oy, int wx, int wy) {
		load();
		for (int i = 0; i < n; i++) {
			if (x[i] != ox || y[i] != oy) continue;
			if (x[i] == wx && y[i] == wy) return;
			x[i] = wx;
			y[i] = wy;
			version++;
			flushQuiet();
			return;
		}
	}
	public synchronized void remove(int i) {
		load();
		if (i < 0 || i >= n) return;
		int m = n - i - 1;
		if (m > 0) {
			System.arraycopy(x, i + 1, x, i, m);
			System.arraycopy(y, i + 1, y, i, m);
		}
		n--;
		version++;
		flushQuiet();
	}
	public synchronized void remove(int wx, int wy) {
		load();
		for (int i = 0; i < n; i++) {
			if (x[i] != wx || y[i] != wy) continue;
			remove(i);
			return;
		}
	}
	public synchronized void invalidate(ChartPlotterCollisionData data) {
		load();
		int w = 0;
		for (int i = 0; i < n; i++) {
			if (blocked(data, x[i], y[i])) continue;
			x[w] = x[i];
			y[w++] = y[i];
		}
		if (w == n) return;
		n = w;
		version++;
		flushQuiet();
	}
	private void load() {
		if (loaded) return;
		File f = file();
		ChartPlotterSparseCodec.Text seed = defaults();
		boolean replace = seed != null && (!f.isFile() || ChartPlotterVersions.newer(seed.version, ChartPlotterVersions.read(dir, KEY)));
		ChartPlotterSparseNodes.Snapshot nodes = replace ? seed.nodes : ChartPlotterSparseCodec.read(f);
		if (nodes == null && seed != null) {
			replace = true;
			nodes = seed.nodes;
		}
		if (nodes != null) {
			set(nodes);
			if (replace && write()) ChartPlotterVersions.write(dir, KEY, seed.version);
		}
		loaded = true;
		version++;
	}
	private ChartPlotterSparseCodec.Text defaults() {
		try (InputStream in = ChartPlotterSparseNodes.class.getResourceAsStream("/com/chartplotter/sparse-nodes.txt")) {
			return in == null ? null : ChartPlotterSparseCodec.readText(in);
		} catch (Exception ignored) {
			return null;
		}
	}
	private void flushQuiet() {
		try {
			write();
		} catch (Exception ignored) {
		}
	}
	private boolean write() {
		return ChartPlotterSparseCodec.write(dir, file(), new Snapshot(Arrays.copyOf(x, n), Arrays.copyOf(y, n)));
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
	private static boolean blocked(ChartPlotterCollisionData data, int wx, int wy) {
		return data.flagAt(wx, wy) == ChartPlotterCollisionCache.BLOCKED;
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
