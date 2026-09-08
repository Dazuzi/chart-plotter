package com.chartplotter.route;
import java.util.Arrays;
final class ChartPlotterPathQueue {
	private final int[] cost;
	private final int[] first;
	private final int[] next;
	private final int[] previous;
	private int cursor;
	int size;
	ChartPlotterPathQueue(int[] cost, int step) {
		this.cost = cost;
		first = new int[step + 1];
		next = new int[cost.length];
		previous = new int[cost.length];
		Arrays.fill(first, -1);
	}
	void add(int node, int oldCost) {
		if (oldCost != 0) remove(node, oldCost % first.length);
		int bucket = cost[node] % first.length;
		previous[node] = -1;
		next[node] = first[bucket];
		if (next[node] >= 0) previous[next[node]] = node;
		first[bucket] = node;
		size++;
	}
	int poll() {
		while (first[cursor % first.length] < 0) cursor++;
		int bucket = cursor % first.length;
		int node = first[bucket];
		remove(node, bucket);
		return node;
	}
	private void remove(int node, int bucket) {
		if (previous[node] < 0) first[bucket] = next[node];
		else next[previous[node]] = next[node];
		if (next[node] >= 0) previous[next[node]] = previous[node];
		size--;
	}
}
