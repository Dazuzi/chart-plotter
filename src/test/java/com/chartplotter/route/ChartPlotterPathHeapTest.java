package com.chartplotter.route;

import org.junit.Test;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class ChartPlotterPathHeapTest {
	@Test
	public void growthDecreasesReopeningAndTiesMatchAnIndependentQueue() {
		int[] cost = new int[40000];
		int[] tie = new int[cost.length];
		int[] version = new int[cost.length];
		boolean[] queued = new boolean[cost.length];
		Comparator<int[]> order = Comparator.<int[]>comparingInt(a -> a[1]).thenComparing((a, b) -> Integer.compare(b[2], a[2])).thenComparingInt(a -> a[0]);
		PriorityQueue<int[]> expected = new PriorityQueue<>(order);
		ChartPlotterPathHeap heap = new ChartPlotterPathHeap(cost, tie);
		Random random = new Random(2903);
		int size = 0;
		for (int step = 0; step < 150000; step++) {
			if (step >= cost.length && step % 3 == 0 && size > 0) {
				int[] entry;
				do {entry = expected.remove();} while (!queued[entry[0]] || version[entry[0]] != entry[3]);
				assertEquals(entry[0], heap.poll());
				queued[entry[0]] = false;
				size--;
			} else {
				int node = step < cost.length ? step : random.nextInt(cost.length);
				cost[node] = queued[node] ? cost[node] - 1 - random.nextInt(20) : random.nextInt(1000);
				tie[node] = random.nextInt(100);
				version[node]++;
				if (!queued[node]) size++;
				queued[node] = true;
				expected.add(new int[]{node, cost[node], tie[node], version[node]});
				heap.add(node);
			}
			assertEquals(size, heap.size);
		}
		while (size > 0) {
			int[] entry;
			do {entry = expected.remove();} while (!queued[entry[0]] || version[entry[0]] != entry[3]);
			assertEquals(entry[0], heap.poll());
			queued[entry[0]] = false;
			size--;
		}
		assertEquals(0, heap.size);
	}
}
