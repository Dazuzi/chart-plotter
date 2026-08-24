package com.chartplotter.route;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class LongIntMapTest {
	@Test
	public void clearInvalidatesEveryGenerationAcrossMarkerWrap() {
		LongIntMap map = new LongIntMap(2);
		for (int generation = 0; generation < 600; generation++) {
			for (int i = 0; i < 100; i++) map.put((long) generation << 32 ^ i, generation + i);
			assertEquals(generation, map.get((long) generation << 32));
			assertEquals(generation + 99, map.get((long) generation << 32 ^ 99));
			map.clear();
			assertEquals(LongIntMap.MISS, map.get((long) generation << 32));
			assertEquals(0, map.n);
		}
	}
}
