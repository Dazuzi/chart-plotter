package com.chartplotter;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
public final class ChartPlotterRouteBenchmark {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int TARGET_RADIUS = 15;
	private static final int[] TX = {2861, 2390, 1453};
	private static final int[] TY = {3399, 3547, 3459};
	private ChartPlotterRouteBenchmark() {}
	public static void main(String[] args) throws Exception {
		Args a = new Args(args);
		File dir = new File(a.str("dir", new File(System.getProperty("user.home"), ".runelite\\chart-plotter").getPath()));
		ChartPlotterCollisionData data = collision(new File(dir, "collision.bin"));
		ChartPlotterSparseNodes.Snapshot sparse = sparse(new File(dir, "sparse.bin"));
		int sx = a.i("sx", 2286);
		int sy = a.i("sy", 4114);
		int phaseX = a.i("phaseX", TS / 2);
		int phaseY = a.i("phaseY", TS / 2);
		int start = a.i("start", -1);
		int corridor = a.i("corridor", 80);
		boolean reverse = Boolean.parseBoolean(a.str("reverse", "false"));
		ChartPlotterRouteEffort effort = ChartPlotterRouteEffort.VERY_HIGH;
		WorldEntityConfig wc = new Wc(a.i("boundsX", -832), a.i("boundsY", -768), a.i("boundsWidth", 128), a.i("boundsHeight", 128));
		int turnBias = shape(a.str("shape", "balanced"));
		System.out.println("bench local chunks=" + data.size + " nodes=" + sparse.n + " effort=" + effort + " shape=" + shapeName(turnBias) + " from=" + sx + "," + sy + " phase=" + phaseX + "," + phaseY + " start=" + start + " reverse=" + reverse + " corridor=" + corridor);
		long t = System.nanoTime();
		ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, wc, start, sx, sy, phaseX, phaseY, TX[0], TY[0], turnBias, reverse, effort.fast, effort.dirs, effort.adaptive, TARGET_RADIUS, sparse, true, corridor, () -> false, null).effort(effort);
		System.out.println("bench local result=" + result(r) + " pts=" + r.n + " ms=" + (System.nanoTime() - t) / 1000000);
	}
	private static ChartPlotterCollisionData collision(File file) throws Exception {
		Constructor<ChartPlotterCollisionCache.Chunk> ctor = ChartPlotterCollisionCache.Chunk.class.getDeclaredConstructor(long.class, long.class);
		ctor.setAccessible(true);
		Map<Long, ChartPlotterCollisionCache.Chunk> map = new HashMap<>();
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
			if (in.readByte() != 1) throw new IllegalArgumentException("collision version");
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				int cx = in.readUnsignedShort();
				int cy = in.readUnsignedShort();
				long known = in.readLong();
				long blocked = in.readLong();
				map.put(ChartPlotterCollisionData.key(cx, cy), ctor.newInstance(known, blocked & known));
			}
		}
		return new ChartPlotterCollisionData(map, 1);
	}
	private static ChartPlotterSparseNodes.Snapshot sparse(File file) throws Exception {
		int[] x;
		int[] y;
		int n;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			if (in.readByte() != 1) throw new IllegalArgumentException("sparse version");
			n = in.readInt();
			x = new int[n];
			y = new int[n];
			for (int i = 0; i < n; i++) {
				x[i] = in.readUnsignedShort();
				y[i] = in.readUnsignedShort();
			}
		}
		Constructor<ChartPlotterSparseNodes.Snapshot> ctor = ChartPlotterSparseNodes.Snapshot.class.getDeclaredConstructor(int[].class, int[].class, int.class);
		ctor.setAccessible(true);
		return ctor.newInstance(x, y, n);
	}
	private static int shape(String s) {
		if ("direct".equalsIgnoreCase(s)) return ChartPlotterTurnPreference.DIRECT.bias;
		if ("smooth".equalsIgnoreCase(s)) return ChartPlotterTurnPreference.SMOOTH.bias;
		return ChartPlotterTurnPreference.BALANCED.bias;
	}
	private static String shapeName(int bias) {
		if (bias == ChartPlotterTurnPreference.DIRECT.bias) return "Direct";
		if (bias == ChartPlotterTurnPreference.SMOOTH.bias) return "Smooth";
		return "Balanced";
	}
	private static String result(ChartPlotterRoute r) {return r.status == ChartPlotterRoute.OK ? "ok" : r.text();}
	private static final class Args {
		final String[] args;
		private Args(String[] args) {
			this.args = args;
		}
		int i(String k, int d) {
			String v = str(k, null);
			return v == null ? d : Integer.parseInt(v);
		}
		String str(String k, String d) {
			String p = k + "=";
			for (String s : args) if (s.startsWith(p)) return s.substring(p.length());
			return d;
		}
	}
	private static final class Wc implements WorldEntityConfig {
		final int x;
		final int y;
		final int w;
		final int h;
		private Wc(int x, int y, int w, int h) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}
		public int getId() {return 0;}
		public int getCategory() {return 0;}
		public int getBoundsX() {return x;}
		public int getBoundsY() {return y;}
		public int getBoundsWidth() {return w;}
		public int getBoundsHeight() {return h;}
	}
}
