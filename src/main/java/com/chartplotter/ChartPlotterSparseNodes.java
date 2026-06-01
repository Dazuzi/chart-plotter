package com.chartplotter;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
@Singleton
final class ChartPlotterSparseNodes {
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private int[] x = new int[64];
	private int[] y = new int[64];
	private int n;
	private boolean loaded;
	synchronized Snapshot snapshot() {
		load();
		return new Snapshot(Arrays.copyOf(x, n), Arrays.copyOf(y, n));
	}
	synchronized int nodeAt(int wx, int wy, int r) {
		load();
		for (int i = 0; i < n; i++) {
			if (dist(wx, wy, x[i], y[i]) <= r) return i;
		}
		return -1;
	}
	synchronized void add(int wx, int wy) {
		load();
		if (n == x.length) grow();
		x[n] = wx;
		y[n] = wy;
		n++;
		flushQuiet();
	}
	synchronized void move(int ox, int oy, int wx, int wy) {
		load();
		for (int i = 0; i < n; i++) {
			if (x[i] != ox || y[i] != oy) continue;
			if (x[i] == wx && y[i] == wy) return;
			x[i] = wx;
			y[i] = wy;
			flushQuiet();
			return;
		}
	}
	synchronized void remove(int i) {
		load();
		if (i < 0 || i >= n) return;
		int m = n - i - 1;
		if (m > 0) {
			System.arraycopy(x, i + 1, x, i, m);
			System.arraycopy(y, i + 1, y, i, m);
		}
		n--;
		flushQuiet();
	}
	synchronized void invalidate(ChartPlotterCollisionData data) {
		load();
		int w = 0;
		for (int i = 0; i < n; i++) {
			if (blocked(data, x[i], y[i])) continue;
			x[w] = x[i];
			y[w++] = y[i];
		}
		if (w == n) return;
		n = w;
		flushQuiet();
	}
	private void load() {
		if (loaded) return;
		ChartPlotterSparseNodes.Snapshot nodes = ChartPlotterSparseCodec.read(file());
		if (nodes == null) defaults();
		else {
			ensure(nodes.x.length);
			System.arraycopy(nodes.x, 0, x, 0, nodes.x.length);
			System.arraycopy(nodes.y, 0, y, 0, nodes.y.length);
			n = nodes.x.length;
		}
		loaded = true;
	}
	private void defaults() {
		try (InputStream in = ChartPlotterSparseNodes.class.getResourceAsStream("/com/chartplotter/sparse-nodes.txt")) {
			if (in == null) return;
			byte[] b = in.readAllBytes();
			parse(new String(b, StandardCharsets.UTF_8));
		} catch (Exception ignored) {
		}
	}
	private void parse(String s) {
		int[] v = new int[1024];
		int c = 0;
		int p = 0;
		while (p < s.length()) {
			while (p < s.length() && !digit(s.charAt(p))) p++;
			if (p >= s.length()) break;
			int q = p;
			while (q < s.length() && digit(s.charAt(q))) q++;
			if (c == v.length) v = Arrays.copyOf(v, v.length << 1);
			v[c++] = Integer.parseInt(s.substring(p, q));
			p = q;
		}
		n = 0;
		ensure(c / 2);
		for (int i = 1; i < c; i += 2) {
			x[n] = v[i - 1];
			y[n] = v[i];
			n++;
		}
	}
	private void flushQuiet() {
		try {
			write();
		} catch (Exception ignored) {
		}
	}
	private void write() {
		ChartPlotterSparseCodec.write(dir, file(), new Snapshot(Arrays.copyOf(x, n), Arrays.copyOf(y, n)));
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
	private static boolean digit(char c) {return c >= '0' && c <= '9';}
	private static int dist(int ax, int ay, int bx, int by) {return ChartPlotterMath.chebyshev(ax, ay, bx, by);}
	static final class Snapshot {
		final int[] x;
		final int[] y;
		Snapshot(int[] x, int[] y) {
			this.x = x;
			this.y = y;
		}
	}
}
