package com.chartplotter.route;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChartPlotterPathQueueTest {
	@Test
	public void decreasesAndBucketWrapsPreserveDijkstraOrder() {
		Random random = new Random(813);
		int[] distance = new int[1000];
		boolean[] queued = new boolean[distance.length];
		ChartPlotterPathQueue queue = new ChartPlotterPathQueue(distance, 100);
		int cursor = 1;
		for (int step = 0; step < 20000; step++) {
			for (int i = 0; i < 8; i++) {
				int node = random.nextInt(distance.length);
				int next = cursor + random.nextInt(101);
				if (queued[node] && distance[node] <= next) continue;
				int previous = queued[node] ? distance[node] : 0;
				distance[node] = next;
				queue.add(node, previous);
				queued[node] = true;
			}
			int min = Integer.MAX_VALUE;
			for (int i = 0; i < distance.length; i++) if (queued[i]) min = Math.min(min, distance[i]);
			int node = queue.poll();
			assertEquals(min, distance[node]);
			cursor = distance[node];
			queued[node] = false;
		}
		while (queue.size > 0) {
			int node = queue.poll();
			assertTrue(distance[node] >= cursor);
			cursor = distance[node];
		}
	}
}
