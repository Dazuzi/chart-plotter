package com.chartplotter.route;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChartPlotterPathQueueTest {
	@Test
	public void distantBucketsAndRecycledPagesPreserveOrdering() {
		int[] cost = new int[1 << 20];
		ChartPlotterPathQueue queue = new ChartPlotterPathQueue(cost, 40000);
		for (int round = 0; round < 100; round++) {
			int offset = round * 4096;
			for (int i = 0; i < 16; i++) {
				int node = offset + i * 256;
				cost[node] = 50000000 + round * 100000 + i * 2000;
				queue.add(node, 0);
			}
			for (int i = 15; i >= 0; i--) {
				int node = offset + i * 256;
				int previous = cost[node];
				cost[node] -= 1000;
				queue.add(node, previous);
			}
			for (int i = 0; i < 16; i++) assertEquals(offset + i * 256, queue.poll());
			assertEquals(0, queue.size);
		}
		assertTrue(queue.bytes() < cost.length);
	}
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
