package com.chartplotter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.client.RuneLite;
@Singleton
final class ChartPlotterCollisionCache {
	static final int UNKNOWN = -1;
	static final int OPEN = 0;
	static final int BLOCKED = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	static final int VOID = 0xffffff;
	private static final byte VERSION = 3;
	private static final int EDGE = 8;
	private static final int MOVE = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private final Map<Long, int[]> chunks = new HashMap<>();
	private Map<Long, int[]> view = new HashMap<>();
	private boolean loaded;
	private ScheduledExecutorService io;
	private long rev;
	private long savedRev;
	private long viewRev = -1;
	private int captures;
	private int tiles;
	synchronized void start() {
		if (io != null) return;
		try {Files.createDirectories(dir.toPath());} catch (Exception ignored) {}
		if (!loaded) load();
		io = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-collision");
			t.setDaemon(true);
			return t;
		});
		io.scheduleWithFixedDelay(this::flushQuiet, 30, 30, TimeUnit.SECONDS);
	}
	void stop() {
		ScheduledExecutorService ex;
		synchronized (this) {
			ex = io;
			io = null;
		}
		if (ex == null) return;
		ex.execute(this::flushQuiet);
		ex.shutdown();
	}
	synchronized void capture(WorldView wv) {
		if (wv == null || wv.isInstance() || wv.getPlane() != 0) return;
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps == null || maps.length == 0 || maps[0] == null) return;
		int[][] flags = maps[0].getFlags();
		int sx1 = flags.length - EDGE;
		int sy1 = flags[0].length - EDGE;
		for (int sx = EDGE; sx < sx1; sx++) {
			for (int sy = EDGE; sy < sy1; sy++) {
				int f = flags[sx][sy];
				if (f == VOID) continue;
				put(wv.getBaseX() + sx, wv.getBaseY() + sy, f);
			}
		}
		captures++;
	}
	synchronized int flag(WorldView wv, int sx, int sy) {
		int wx = wv.getBaseX() + sx;
		int wy = wv.getBaseY() + sy;
		int[] c = chunks.get(key(wx >> 3, wy >> 3));
		return c == null ? UNKNOWN : c[(wx & 7) + ((wy & 7) << 3)];
	}
	synchronized Map<Long, int[]> snapshot() {
		if (!loaded) load();
		if (viewRev != rev) {
			view = copy(chunks);
			viewRev = rev;
		}
		return view;
	}
	synchronized Map<Long, int[]> snapshot(WorldView wv) {
		Map<Long, int[]> out = copy(snapshot());
		add(out, wv);
		return out;
	}
	synchronized String stats() {return "cacheCaptures=" + captures + " cacheChunks=" + chunks.size() + " cacheTiles=" + tiles;}
	private void put(int wx, int wy, int f) {
		f = clean(f);
		int cx = wx >> 3;
		int cy = wy >> 3;
		int[] c = chunks.computeIfAbsent(key(cx, cy), k -> empty());
		int i = (wx & 7) + ((wy & 7) << 3);
		if (c[i] == UNKNOWN) tiles++;
		if (c[i] == f) return;
		c[i] = f;
		rev++;
	}
	private static void add(Map<Long, int[]> data, WorldView wv) {
		if (wv == null || wv.isInstance() || wv.getPlane() != 0) return;
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps == null || maps.length == 0 || maps[0] == null) return;
		int[][] flags = maps[0].getFlags();
		int sx1 = flags.length - EDGE;
		int sy1 = flags[0].length - EDGE;
		for (int sx = EDGE; sx < sx1; sx++) {
			for (int sy = EDGE; sy < sy1; sy++) {
				int f = flags[sx][sy];
				if (f != VOID) put(data, wv.getBaseX() + sx, wv.getBaseY() + sy, f);
			}
		}
	}
	private static void put(Map<Long, int[]> data, int wx, int wy, int f) {
		f = clean(f);
		int cx = wx >> 3;
		int cy = wy >> 3;
		int[] c = data.computeIfAbsent(key(cx, cy), k -> empty());
		c[(wx & 7) + ((wy & 7) << 3)] = f;
	}
	private void load() {
		Map<Long, int[]> data = read();
		chunks.clear();
		tiles = 0;
		for (Map.Entry<Long, int[]> e : data.entrySet()) {
			chunks.put(e.getKey(), e.getValue());
			tiles += known(e.getValue());
		}
		rev = 0;
		savedRev = 0;
		viewRev = -1;
		loaded = true;
	}
	private Map<Long, int[]> read() {
		Map<Long, int[]> data = new HashMap<>();
		File file = file();
		if (file.isFile()) {
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
				if (in.readByte() == VERSION) {
					int n = in.readInt();
					for (int i = 0; i < n; i++) {
						int cx = in.readInt();
						int cy = in.readInt();
						long mask = in.readLong();
						long blocked = in.readLong();
						int[] v = new int[64];
						for (int j = 0; j < v.length; j++) v[j] = (mask & 1L << j) == 0 ? UNKNOWN : (blocked & 1L << j) == 0 ? OPEN : BLOCKED;
						data.put(key(cx, cy), v);
					}
				}
			} catch (Exception ignored) {
			}
		}
		return data;
	}
	private void flushQuiet() {
		try {
			flush();
		} catch (Exception ignored) {
		}
	}
	private void flush() {
		Map<Long, int[]> out;
		long save;
		synchronized (this) {
			if (rev == savedRev) return;
			out = snapshot();
			save = rev;
		}
		if (write(out)) {
			synchronized (this) {
				if (savedRev < save) savedRev = save;
			}
		}
	}
	private boolean write(Map<Long, int[]> data) {
		File tmp = new File(dir, "collision.bin.tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
			out.writeByte(VERSION);
			out.writeInt(count(data));
			for (Map.Entry<Long, int[]> e : data.entrySet()) {
				long mask = mask(e.getValue());
				if (mask == 0) continue;
				out.writeInt((int) (e.getKey() >> 32));
				out.writeInt((int) (long) e.getKey());
				out.writeLong(mask);
				out.writeLong(blocked(e.getValue()));
			}
		} catch (Exception ignored) {
			return false;
		}
		try {
			Files.move(tmp.toPath(), file().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			try {
				Files.move(tmp.toPath(), file().toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ignored) {
				return false;
			}
		} catch (Exception ignored) {
			return false;
		}
		return true;
	}
	private static int[] empty() {
		int[] v = new int[64];
		Arrays.fill(v, UNKNOWN);
		return v;
	}
	private static Map<Long, int[]> copy(Map<Long, int[]> data) {
		Map<Long, int[]> out = new HashMap<>();
		for (Map.Entry<Long, int[]> e : data.entrySet()) out.put(e.getKey(), e.getValue().clone());
		return out;
	}
	private File file() {return new File(dir, "collision.bin");}
	private static int count(Map<Long, int[]> data) {
		int n = 0;
		for (int[] v : data.values()) {
			if (mask(v) != 0) n++;
		}
		return n;
	}
	private static int known(int[] v) {
		int n = 0;
		for (int f : v) {
			if (f != UNKNOWN) n++;
		}
		return n;
	}
	private static long mask(int[] v) {
		long m = 0;
		for (int i = 0; i < v.length; i++) {
			if (v[i] != UNKNOWN) m |= 1L << i;
		}
		return m;
	}
	private static long blocked(int[] v) {
		long m = 0;
		for (int i = 0; i < v.length; i++) {
			if (v[i] == BLOCKED) m |= 1L << i;
		}
		return m;
	}
	private static int clean(int f) {
		return (f & MOVE) == 0 ? OPEN : BLOCKED;
	}
	private static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
}
