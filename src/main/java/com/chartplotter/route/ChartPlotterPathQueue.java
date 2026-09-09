package com.chartplotter.route;
import java.util.Arrays;
final class ChartPlotterPathQueue {
	private final int[] cost;
	private final int[] first;
	private final int[][] links;
	private final int[] active;
	private int[][] free = new int[16][];
	private int freeCount;
	private int allocatedPages;
	private int cursor;
	int size;
	ChartPlotterPathQueue(int[] cost, int step) {
		this.cost = cost;
		int capacity = 1;
		while (capacity <= step) capacity <<= 1;
		first = new int[capacity];
		links = new int[(cost.length + 255) >>> 8][];
		active = new int[links.length];
		Arrays.fill(first, -1);
	}
	void add(int node, int oldCost) {
		if (oldCost != 0) remove(node, oldCost & (first.length - 1));
		cursor = size == 0 ? cost[node] : Math.min(cursor, cost[node]);
		int bucket = cost[node] & (first.length - 1);
		int[] page = links[node >>> 8];
		if (page == null) {
			if (freeCount == 0) {page = new int[512]; allocatedPages++;}
			else {page = free[--freeCount]; free[freeCount] = null;}
			links[node >>> 8] = page;
		}
		active[node >>> 8]++;
		int index = node & 255;
		page[index + 256] = -1;
		page[index] = first[bucket];
		if (page[index] >= 0) links[page[index] >>> 8][256 + (page[index] & 255)] = node;
		first[bucket] = node;
		size++;
	}
	int poll() {
		while (first[cursor & (first.length - 1)] < 0) cursor++;
		int bucket = cursor & (first.length - 1);
		int node = first[bucket];
		remove(node, bucket);
		return node;
	}
	private void remove(int node, int bucket) {
		int[] page = links[node >>> 8];
		int next = page[node & 255];
		int previous = page[256 + (node & 255)];
		if (previous < 0) first[bucket] = next;
		else links[previous >>> 8][previous & 255] = next;
		if (next >= 0) links[next >>> 8][256 + (next & 255)] = previous;
		if (--active[node >>> 8] == 0) {
			if (freeCount == free.length) free = Arrays.copyOf(free, free.length * 2);
			free[freeCount++] = page;
			links[node >>> 8] = null;
		}
		size--;
	}
	long bytes() {return (first.length + active.length) * 4L + (links.length + free.length) * 8L + allocatedPages * 512L * 4;}
}
