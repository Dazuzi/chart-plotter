package com.chartplotter.collision;
import com.chartplotter.collision.ChartPlotterCollisionData.Chunk;
import com.chartplotter.route.ChartPlotterSparseNodes;
import com.chartplotter.util.ChartPlotterVersions;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.WorldView;
import net.runelite.client.RuneLite;
@Singleton
public final class ChartPlotterCollisionCache {
	private static final String KEY = "collision";
	public static final int UNKNOWN = ChartPlotterCollisionData.UNKNOWN;
	public static final int OPEN = ChartPlotterCollisionData.OPEN;
	public static final int BLOCKED = ChartPlotterCollisionData.BLOCKED;
	public static final int VOID = ChartPlotterCollisionData.VOID;
	private static final int EDGE = 8;
	public static final int MOVE = ChartPlotterCollisionData.MOVE;
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private final Map<Long, Chunk> chunks = new HashMap<>();
	@Inject private ChartPlotterSparseNodes sparseNodes;
	private ChartPlotterCollisionData view = new ChartPlotterCollisionData(new HashMap<>());
	private boolean loaded;
	private ScheduledExecutorService io;
	private final AtomicLong rev = new AtomicLong();
	private long savedRev;
	private long viewRev = -1;
	public synchronized void start() {
		if (io != null) return;
		io = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-collision");
			t.setDaemon(true);
			return t;
		});
		if (!loaded) io.execute(this::loadQuiet);
		io.scheduleWithFixedDelay(this::flushQuiet, 30, 30, TimeUnit.SECONDS);
	}
	public void stop() {
		ScheduledExecutorService ex;
		synchronized (this) {
			ex = io;
			io = null;
		}
		if (ex == null) return;
		try {ex.execute(this::flushQuiet);} catch (RuntimeException ignored) {}
		ex.shutdown();
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
			try {io.execute(() -> mergeQuiet(scan));} catch (RuntimeException ignored) {}
		}
	}
	public synchronized ChartPlotterCollisionData snapshot() {
		if (!loaded) return view;
		long r = rev.get();
		if (viewRev != r) {
			view = new ChartPlotterCollisionData(new HashMap<>(chunks), r);
			viewRev = r;
		}
		return view;
	}
	public long rev() {return rev.get();}
	private void mergeQuiet(ChartPlotterCollisionScan scan) {
		try {
			if (merge(scan)) sparseNodes.invalidate(snapshot());
		} catch (Exception ignored) {
		}
	}
	private boolean merge(ChartPlotterCollisionScan scan) {
		synchronized (this) {
			if (!loaded) return false;
			Map<Long, Builder> data = new HashMap<>();
			int sx1 = scan.width - EDGE;
			int sy1 = scan.height - EDGE;
			for (int sx = EDGE; sx < sx1; sx++) {
				for (int sy = EDGE; sy < sy1; sy++) {
					int f = scan.flags[sx * scan.height + sy];
					if (f == VOID) continue;
					put(data, chunks, scan.baseX + sx, scan.baseY + sy, f);
				}
			}
			for (int i = 0; i < scan.objects.length; i += 4) putObject(data, chunks, scan, i);
			merge(data);
			return true;
		}
	}
	private static void putObject(Map<Long, Builder> data, Map<Long, Chunk> base, ChartPlotterCollisionScan scan, int i) {
		for (int sx = scan.objects[i]; sx <= scan.objects[i + 2]; sx++) {
			for (int sy = scan.objects[i + 1]; sy <= scan.objects[i + 3]; sy++) {
				put(data, base, scan.baseX + sx, scan.baseY + sy, BLOCKED);
			}
		}
	}
	private static void put(Map<Long, Builder> data, Map<Long, Chunk> base, int wx, int wy, int f) {
		f = clean(f);
		int flag = f;
		int cx = wx >> 3;
		int cy = wy >> 3;
		long k = key(cx, cy);
		int i = (wx & 7) + ((wy & 7) << 3);
		data.compute(k, (x, b) -> {
			if (b == null) b = new Builder(base.get(x));
			b.put(i, flag);
			return b;
		});
	}
	private void merge(Map<Long, Builder> data) {
		for (Map.Entry<Long, Builder> e : data.entrySet()) {
			Chunk old = chunks.get(e.getKey());
			Chunk c = e.getValue().chunk();
			if (same(old, c)) continue;
			chunks.put(e.getKey(), c);
			rev.incrementAndGet();
		}
	}
	private static boolean same(Chunk a, Chunk b) {
		return a == b || a != null && b != null && a.known == b.known && a.blocked == b.blocked;
	}
	private void loadQuiet() {
		try {
			load();
		} catch (Exception ignored) {
		}
	}
	private void load() {
		File f = file();
		ChartPlotterCollisionCodec.Text seed = defaults();
		boolean replace = seed != null && (!f.isFile() || ChartPlotterVersions.newer(seed.version, ChartPlotterVersions.read(dir, KEY)));
		Map<Long, Chunk> data = replace ? seed.data : ChartPlotterCollisionCodec.read(f);
		if (data.isEmpty() && seed != null && !replace) {
			replace = true;
			data = seed.data;
		}
		synchronized (this) {
			chunks.clear();
			chunks.putAll(data);
			long r = rev.incrementAndGet();
			savedRev = replace ? r - 1 : r;
			viewRev = -1;
			loaded = true;
		}
		if (replace && flush()) ChartPlotterVersions.write(dir, KEY, seed.version);
	}
	private ChartPlotterCollisionCodec.Text defaults() {
		try (InputStream in = ChartPlotterCollisionCache.class.getResourceAsStream("/com/chartplotter/collision.txt")) {
			return in == null ? null : ChartPlotterCollisionCodec.readText(in);
		} catch (Exception ignored) {
			return null;
		}
	}
	private void flushQuiet() {
		try {
			flush();
		} catch (Exception ignored) {
		}
	}
	private boolean flush() {
		ChartPlotterCollisionData out;
		long save;
		synchronized (this) {
			long r = rev.get();
			if (r == savedRev) return true;
			out = snapshot();
			save = r;
		}
		if (ChartPlotterCollisionCodec.write(dir, file(), out.base)) {
			synchronized (this) {
				if (savedRev < save) savedRev = save;
			}
			return true;
		}
		return false;
	}
	private File file() {return new File(dir, "collision.bin");}
	private static int clean(int f) {
		return (f & MOVE) == 0 ? OPEN : BLOCKED;
	}
	private static long key(int x, int y) {return ChartPlotterCollisionData.key(x, y);}
	private static final class Builder {
		long known;
		long blocked;
		private Builder(Chunk base) {
			if (base == null) return;
			known = base.known;
			blocked = base.blocked;
		}
		void put(int i, int f) {
			long bit = 1L << i;
			known |= bit;
			if (f == BLOCKED) blocked |= bit;
			else blocked &= ~bit;
		}
		Chunk chunk() {return new Chunk(known, blocked & known);}
	}
}
