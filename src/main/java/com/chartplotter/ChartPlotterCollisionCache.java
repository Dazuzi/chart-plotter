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
import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.client.RuneLite;
@Singleton
final class ChartPlotterCollisionCache {
	static final int UNKNOWN = -1;
	static final int OPEN = 0;
	static final int BLOCKED = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	static final int VOID = 0xffffff;
	private static final byte VERSION = 1;
	private static final int EDGE = 8;
	private static final int USHORT = 0xffff;
	private static final int MOVE = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST | CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private final Map<Long, Chunk> chunks = new HashMap<>();
	private ChartPlotterCollisionData view = new ChartPlotterCollisionData(new HashMap<>());
	private boolean loaded;
	private ScheduledExecutorService io;
	private long rev;
	private long savedRev;
	private long viewRev = -1;
	synchronized void start() {
		if (io != null) return;
		try {Files.createDirectories(dir.toPath());} catch (Exception ignored) {}
		io = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-collision");
			t.setDaemon(true);
			return t;
		});
		if (!loaded) io.execute(this::loadQuiet);
		io.scheduleWithFixedDelay(this::flushQuiet, 30, 30, TimeUnit.SECONDS);
	}
	void stop() {
		ScheduledExecutorService ex;
		synchronized (this) {
			ex = io;
			io = null;
		}
		if (ex == null) return;
		try {ex.execute(this::flushQuiet);} catch (RuntimeException ignored) {}
		ex.shutdown();
	}
	void capture(WorldView wv) {
		ScheduledExecutorService ex;
		synchronized (this) {
			ex = io;
		}
		if (ex == null) return;
		Scan scan = scan(wv);
		if (scan == null) return;
		synchronized (this) {
			if (io != ex) return;
			try {io.execute(() -> mergeQuiet(scan));} catch (RuntimeException ignored) {}
		}
	}
	synchronized int flag(WorldView wv, int sx, int sy) {
		if (!loaded) load();
		int wx = wv.getBaseX() + sx;
		int wy = wv.getBaseY() + sy;
		Chunk c = chunks.get(key(wx >> 3, wy >> 3));
		return c == null ? UNKNOWN : c.flag((wx & 7) + ((wy & 7) << 3));
	}
	synchronized ChartPlotterCollisionData snapshot() {
		if (!loaded) load();
		if (viewRev != rev) {
			view = new ChartPlotterCollisionData(new HashMap<>(chunks));
			viewRev = rev;
		}
		return view;
	}
	synchronized long rev() {return rev;}
	private void mergeQuiet(Scan scan) {
		try {
			merge(scan);
		} catch (Exception ignored) {
		}
	}
	private synchronized void merge(Scan scan) {
		if (!loaded) load();
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
	}
	private static Scan scan(WorldView wv) {
		if (wv == null || wv.isInstance() || wv.getPlane() != 0) return null;
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps == null || maps.length == 0 || maps[0] == null) return null;
		int[][] flags = maps[0].getFlags();
		if (flags == null || flags.length <= EDGE * 2 || flags[0] == null || flags[0].length <= EDGE * 2) return null;
		int width = flags.length;
		int height = flags[0].length;
		int[] copy = new int[width * height];
		for (int x = 0; x < width; x++) {
			int[] row = flags[x];
			if (row == null || row.length < height) return null;
			System.arraycopy(row, 0, copy, x * height, height);
		}
		return new Scan(wv.getBaseX(), wv.getBaseY(), width, height, copy, objects(wv));
	}
	private static int[] objects(WorldView wv) {
		Scene scene = wv.getScene();
		if (scene == null) return new int[0];
		Tile[][][] tiles = scene.getExtendedTiles();
		int plane = wv.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) return new int[0];
		Objects objects = new Objects();
		for (Tile[] row : tiles[plane]) {
			if (row == null) continue;
			for (Tile tile : row) {
				if (tile == null) continue;
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects == null) continue;
				for (GameObject object : gameObjects) {
					if (object == null || !ChartPlotterCollisionObjects.blocked(object.getId())) continue;
					objects.add(object);
				}
			}
		}
		return objects.array();
	}
	private static void putObject(Map<Long, Builder> data, Map<Long, Chunk> base, Scan scan, int i) {
		for (int sx = scan.objects[i]; sx <= scan.objects[i + 2]; sx++) {
			for (int sy = scan.objects[i + 1]; sy <= scan.objects[i + 3]; sy++) {
				put(data, base, scan.baseX + sx, scan.baseY + sy, BLOCKED);
			}
		}
	}
	private static void put(Map<Long, Builder> data, Map<Long, Chunk> base, int wx, int wy, int f) {
		f = clean(f);
		int cx = wx >> 3;
		int cy = wy >> 3;
		long k = key(cx, cy);
		Builder b = data.computeIfAbsent(k, x -> new Builder(base.get(x)));
		b.put((wx & 7) + ((wy & 7) << 3), f);
	}
	private void merge(Map<Long, Builder> data) {
		for (Map.Entry<Long, Builder> e : data.entrySet()) {
			Chunk old = chunks.get(e.getKey());
			Chunk c = e.getValue().chunk();
			if (same(old, c)) continue;
			chunks.put(e.getKey(), c);
			rev++;
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
	private synchronized void load() {
		Map<Long, Chunk> data = read();
		chunks.clear();
		chunks.putAll(data);
		rev = 0;
		savedRev = 0;
		viewRev = -1;
		loaded = true;
	}
	private Map<Long, Chunk> read() {
		Map<Long, Chunk> data = new HashMap<>();
		File file = file();
		if (file.isFile()) {
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
				if (in.readByte() == VERSION) {
					int n = in.readInt();
					for (int i = 0; i < n; i++) {
						int cx = in.readUnsignedShort();
						int cy = in.readUnsignedShort();
						long mask = in.readLong();
						long blocked = in.readLong();
						data.put(key(cx, cy), new Chunk(mask, blocked & mask));
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
		ChartPlotterCollisionData out;
		long save;
		synchronized (this) {
			if (rev == savedRev) return;
			out = snapshot();
			save = rev;
		}
		if (write(out.base)) {
			synchronized (this) {
				if (savedRev < save) savedRev = save;
			}
		}
	}
	private boolean write(Map<Long, Chunk> data) {
		File tmp = new File(dir, "collision.bin.tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
			out.writeByte(VERSION);
			out.writeInt(count(data));
			for (Map.Entry<Long, Chunk> e : data.entrySet()) {
				Chunk c = e.getValue();
				if (c.empty()) continue;
				int cx = (int) (e.getKey() >> 32);
				int cy = (int) (long) e.getKey();
				if (cx < 0 || cx > USHORT || cy < 0 || cy > USHORT) return false;
				out.writeShort(cx);
				out.writeShort(cy);
				out.writeLong(c.known);
				out.writeLong(c.blocked);
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
	private File file() {return new File(dir, "collision.bin");}
	private static int count(Map<Long, Chunk> data) {
		int n = 0;
		for (Chunk c : data.values()) if (!c.empty()) n++;
		return n;
	}
	private static int clean(int f) {
		return (f & MOVE) == 0 ? OPEN : BLOCKED;
	}
	private static long key(int x, int y) {return ChartPlotterCollisionData.key(x, y);}
	private static final class Scan {
		final int baseX;
		final int baseY;
		final int width;
		final int height;
		final int[] flags;
		final int[] objects;
		private Scan(int baseX, int baseY, int width, int height, int[] flags, int[] objects) {
			this.baseX = baseX;
			this.baseY = baseY;
			this.width = width;
			this.height = height;
			this.flags = flags;
			this.objects = objects;
		}
	}
	static final class Chunk {
		final long known;
		final long blocked;
		private Chunk(long known, long blocked) {
			this.known = known;
			this.blocked = blocked;
		}
		int flag(int i) {
			long b = 1L << i;
			return (known & b) == 0 ? UNKNOWN : (blocked & b) == 0 ? OPEN : BLOCKED;
		}
		boolean empty() {return known == 0;}
	}
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
	private static final class Objects {
		int[] data = new int[32];
		int n;
		void add(GameObject object) {
			Point min = object.getSceneMinLocation();
			Point max = object.getSceneMaxLocation();
			if (min == null || max == null) return;
			if (n + 4 > data.length) data = Arrays.copyOf(data, data.length << 1);
			data[n++] = min.getX();
			data[n++] = min.getY();
			data[n++] = max.getX();
			data[n++] = max.getY();
		}
		int[] array() {return n == data.length ? data : Arrays.copyOf(data, n);}
	}
}
