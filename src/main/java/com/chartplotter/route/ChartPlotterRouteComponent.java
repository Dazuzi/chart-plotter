package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionData;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
final class ChartPlotterRouteComponent {
	final ChartPlotterCollisionData data;
	private final LongIntMap chunks;
	private final long[] reached;
	private ChartPlotterRouteComponent(ChartPlotterCollisionData data, LongIntMap chunks, long[] reached) {
		this.data = data;
		this.chunks = chunks;
		this.reached = reached;
	}
	boolean contains(int x, int y) {
		int a = chunks.get(ChartPlotterCollisionData.key(x >> 3, y >> 3));
		return a != LongIntMap.MISS && (reached[a] & 1L << ((x & 7) + ((y & 7) << 3))) != 0;
	}
	long bytes() {return reached.length * 8L + chunks.k.length * 13L;}
	static ChartPlotterRouteComponent create(ChartPlotterCollisionData data, int sx, int sy, BooleanSupplier cancel) {
		if (data.flagAt(sx, sy) != ChartPlotterCollisionData.OPEN) return null;
		LongIntMap seen = new LongIntMap(256);
		long[] keys = new long[Math.min(128, data.size())];
		long[] reached = new long[keys.length];
		boolean[] queued = new boolean[keys.length];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		keys[0] = ChartPlotterCollisionData.key(sx >> 3, sy >> 3);
		reached[0] = 1L << ((sx & 7) + ((sy & 7) << 3));
		seen.put(keys[0], 0);
		queued[0] = true;
		queue.add(0);
		int count = 1;
		int visited = 0;
		while (!queue.isEmpty()) {
			if ((visited++ & 255) == 0 && cancel.getAsBoolean()) return null;
			int a = queue.remove();
			queued[a] = false;
			int x = (int) (keys[a] >> 32);
			int y = (int) keys[a];
			ChartPlotterCollisionData.Chunk current = data.chunk(x, y);
			if (current == null) continue;
			long open = current.known & ~current.blocked;
			long bits = reached[a];
			while (true) {
				long next = (bits | (bits & 0xfefefefefefefefeL) >>> 1 | (bits & 0x7f7f7f7f7f7f7f7fL) << 1 | bits >>> 8 | bits << 8) & open;
				if (next == bits) break;
				bits = next;
			}
			reached[a] = bits;
			for (int d = 0; d < 4; d++) {
				int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
				int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
				long key = ChartPlotterCollisionData.key(nx, ny);
				long edge = d == 0 ? bits >>> 7 & 0x0101010101010101L : d == 1 ? bits << 7 & 0x8080808080808080L : d == 2 ? bits >>> 56 : bits << 56;
				if (edge == 0) continue;
				ChartPlotterCollisionData.Chunk chunk = data.chunk(nx, ny);
				if (chunk == null) continue;
				edge &= chunk.known & ~chunk.blocked;
				if (edge == 0) continue;
				int b = seen.get(key);
				if (b == LongIntMap.MISS) {
					if (count == keys.length) {
						int capacity = Math.min(data.size(), count * 2);
						keys = Arrays.copyOf(keys, capacity);
						reached = Arrays.copyOf(reached, capacity);
						queued = Arrays.copyOf(queued, capacity);
					}
					b = count++;
					keys[b] = key;
					seen.put(key, b);
				}
				if ((edge & ~reached[b]) == 0) continue;
				reached[b] |= edge;
				if (!queued[b]) {queue.add(b); queued[b] = true;}
			}
		}
		return cancel.getAsBoolean() ? null : new ChartPlotterRouteComponent(data, seen, Arrays.copyOf(reached, count));
	}
}
