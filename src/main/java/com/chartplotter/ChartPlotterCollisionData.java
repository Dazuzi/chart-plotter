package com.chartplotter;
import java.util.Map;
final class ChartPlotterCollisionData {
	final Map<Long, ChartPlotterCollisionCache.Chunk> base;
	final int size;
	ChartPlotterCollisionData(Map<Long, ChartPlotterCollisionCache.Chunk> base) {
		this.base = base;
		this.size = base.size();
	}
	ChartPlotterCollisionCache.Chunk chunk(int x, int y) {
		return base.get(key(x, y));
	}
	boolean uncached(int x, int y) {
		ChartPlotterCollisionCache.Chunk c = chunk(x, y);
		return c == null || c.empty();
	}
	Iterable<Map.Entry<Long, ChartPlotterCollisionCache.Chunk>> entries() {return base.entrySet();}
	static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
}
