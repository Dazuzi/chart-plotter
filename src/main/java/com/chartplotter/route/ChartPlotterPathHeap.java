package com.chartplotter.route;
import java.util.Arrays;
final class ChartPlotterPathHeap {
	int[] cost;
	int[] tie;
	int[] position;
	int[] nodes;
	int size;
	int peak;
	ChartPlotterPathHeap(int[] cost, int[] tie) {
		this.cost = cost;
		this.tie = tie;
		position = new int[cost.length];
		nodes = new int[Math.min(16384, cost.length)];
	}
	void add(int node) {
		int i = position[node] - 1;
		if (i < 0) {
			if (size == nodes.length) nodes = Arrays.copyOf(nodes, size * 2);
			i = size++;
			peak = Math.max(peak, size);
		}
		while (i > 0) {
			int p = (i - 1) >>> 2;
			if (!less(node, nodes[p])) break;
			nodes[i] = nodes[p];
			position[nodes[i]] = i + 1;
			i = p;
		}
		nodes[i] = node;
		position[node] = i + 1;
	}
	int poll() {
		int node = nodes[0];
		position[node] = 0;
		int last = nodes[--size];
		if (size == 0) return node;
		int i = 0;
		while (i * 4 + 1 < size) {
			int c = i * 4 + 1;
			int end = Math.min(c + 4, size);
			for (int next = c + 1; next < end; next++) if (less(nodes[next], nodes[c])) c = next;
			if (!less(nodes[c], last)) break;
			nodes[i] = nodes[c];
			position[nodes[i]] = i + 1;
			i = c;
		}
		nodes[i] = last;
		position[last] = i + 1;
		return node;
	}
	private boolean less(int a, int b) {return cost[a] != cost[b] ? cost[a] < cost[b] : tie != null && tie[a] != tie[b] ? tie[a] > tie[b] : a < b;}
}
