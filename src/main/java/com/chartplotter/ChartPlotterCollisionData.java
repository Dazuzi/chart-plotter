package com.chartplotter;
import java.util.Map;
final class ChartPlotterCollisionData {
	final Map<Long, ChartPlotterCollisionCache.Chunk> base;
	final long rev;
	ChartPlotterCollisionData(Map<Long, ChartPlotterCollisionCache.Chunk> base) {
		this(base, 0);
	}
	ChartPlotterCollisionData(Map<Long, ChartPlotterCollisionCache.Chunk> base, long rev) {
		this.base = base;
		this.rev = rev;
	}
	ChartPlotterCollisionCache.Chunk chunk(int x, int y) {
		return base.get(key(x, y));
	}
	int flagAt(int x, int y) {
		ChartPlotterCollisionCache.Chunk c = chunk(x >> 3, y >> 3);
		return c == null ? ChartPlotterCollisionCache.UNKNOWN : c.flag((x & 7) + ((y & 7) << 3));
	}
	boolean uncached(int x, int y) {
		ChartPlotterCollisionCache.Chunk c = chunk(x, y);
		return c == null || c.empty();
	}
	Iterable<Map.Entry<Long, ChartPlotterCollisionCache.Chunk>> entries() {return base.entrySet();}
	static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
}
