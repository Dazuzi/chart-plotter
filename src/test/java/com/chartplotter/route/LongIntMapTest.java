package com.chartplotter.route;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class LongIntMapTest {
	@Test
	public void growthUpdatesAndMarkerWrapPreserveOnlyCurrentEntries() {
		LongIntMap map = new LongIntMap(2);
		for (int i = -64; i < 64; i++) map.put((long) i << 32 ^ i, i);
		for (int i = -64; i < 64; i++) {
			long key = (long) i << 32 ^ i;
			assertEquals(i, map.get(key));
			map.put(key, ~i);
			assertEquals(~i, map.get(key));
		}
		assertEquals(128, map.n);
		for (int generation = 0; generation < 512; generation++) {
			map.clear();
			for (int i = -64; i < 64; i++) assertEquals("generation=" + generation + " key=" + i, LongIntMap.MISS, map.get((long) i << 32 ^ i));
			assertEquals(0, map.n);
		}
		map.put(0, 7);
		assertEquals(7, map.get(0));
		assertEquals(1, map.n);
	}
}
