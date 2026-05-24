package com.chartplotter;
import java.util.Map;
final class ChartPlotterCollisionData {
	final Map<Long, ChartPlotterCollisionCache.Chunk> base;
	final Map<Long, ChartPlotterCollisionCache.Chunk> live;
	final int size;
	ChartPlotterCollisionData(Map<Long, ChartPlotterCollisionCache.Chunk> base) {
		this(base, null);
	}
	ChartPlotterCollisionData(Map<Long, ChartPlotterCollisionCache.Chunk> base, Map<Long, ChartPlotterCollisionCache.Chunk> live) {
		this.base = base;
		this.live = live;
		this.size = size(base, live);
	}
	ChartPlotterCollisionCache.Chunk chunk(int x, int y) {
		long k = key(x, y);
		ChartPlotterCollisionCache.Chunk c = live == null ? null : live.get(k);
		return c == null ? base.get(k) : c;
	}
	boolean uncached(int x, int y) {
		ChartPlotterCollisionCache.Chunk c = chunk(x, y);
		return c == null || c.empty();
	}
	Iterable<Map.Entry<Long, ChartPlotterCollisionCache.Chunk>> entries() {return base.entrySet();}
	static long key(int x, int y) {return (long) x << 32 ^ y & 0xffffffffL;}
	private static int size(Map<Long, ChartPlotterCollisionCache.Chunk> base, Map<Long, ChartPlotterCollisionCache.Chunk> live) {
		int n = base.size();
		if (live != null) {
			for (Long k : live.keySet()) if (!base.containsKey(k)) n++;
		}
		return n;
	}
}
