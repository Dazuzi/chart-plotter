package com.chartplotter.route;
import com.chartplotter.ChartPlotterTurnPreference;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.WorldEntityConfig;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
final class ChartPlotterRouteFinder {
	private static final int MAX_NODES = 4 << 20;
	private static final int MAX_PAGES = 4096;
	private static final int ARRIVAL_RADIUS = 2;
	final ChartPlotterCollisionData data;
	final WorldEntityConfig config;
	final ChartPlotterRouteMotion motion;
	final int sx;
	final int sy;
	final int tx;
	final int ty;
	final int heading;
	final int bias;
	final int weight;
	final boolean reverse;
	final BooleanSupplier cancel;
	ChartPlotterRouteTerrain terrain;
	ChartPlotterRouteHull hull;
	ChartPlotterPathHeap heap;
	int[][] pages;
	int[] pose = new int[65536];
	int[] cost = new int[65536];
	int[] parent = new int[65536];
	int[] priority = new int[65536];
	int[] moves;
	byte[] turns;
	int size = 1;
	int allocatedPages;
	int expanded;
	int routeCost;
	private int turnBias;
	private int[] fromStart;
	private double distanceLimit;
	ChartPlotterRouteFinder(ChartPlotterCollisionData data, WorldEntityConfig config, int heading, int sx, int sy, int tx, int ty, int bias, boolean reverse, double speed, double offsetX, double offsetY, int weight, BooleanSupplier cancel) {
		this.data = data;
		this.config = config;
		motion = new ChartPlotterRouteMotion(speed, offsetX, offsetY);
		this.heading = heading;
		this.sx = sx;
		this.sy = sy;
		this.tx = tx;
		this.ty = ty;
		this.bias = bias;
		turnBias = bias == ChartPlotterTurnPreference.SMOOTH.bias ? ChartPlotterTurnPreference.BALANCED.bias : bias;
		this.reverse = reverse;
		this.weight = Math.max(100, Math.min(250, weight));
		this.cancel = cancel;
	}
	ChartPlotterRoute find() {
		ChartPlotterRoute route = search();
		if (bias == ChartPlotterTurnPreference.SMOOTH.bias && route.status == ChartPlotterRoute.OK) {
			distanceLimit = ChartPlotterRouteSmoother.length(route) * 1.1;
			route = ChartPlotterRouteSmoother.smooth(this, route, distanceLimit);
			if (weight == 100 && terrain != null && !cancel.getAsBoolean()) {
				fromStart = new int[terrain.distance.length];
				terrain.lowerBound(sx, sy, 0, fromStart, hull, cancel);
			}
			for (turnBias = bias; fromStart != null && turnBias > ChartPlotterTurnPreference.BALANCED.bias && !cancel.getAsBoolean(); turnBias /= 2) {
				ChartPlotterRoute candidate = search();
				if (candidate.status != ChartPlotterRoute.OK) continue;
				candidate = ChartPlotterRouteSmoother.smooth(this, candidate, distanceLimit);
				if (ChartPlotterRouteSmoother.length(candidate) <= distanceLimit && candidate.n <= route.n && steering(candidate) < steering(route)) {
					route = candidate;
					break;
				}
			}
			routeCost = steering(route);
			for (int i = 1; i < route.n; i++) {
				int dx = route.x[i] - route.x[i - 1];
				int dy = route.y[i] - route.y[i - 1];
				int d = motion.dir(dx, dy);
				routeCost += motion.cost[d] * (motion.x[d] == 0 ? dy / motion.y[d] : dx / motion.x[d]);
			}
		}
		return route.plan(motion, hull, heading);
	}
	private ChartPlotterRoute search() {
		if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
		int startFlag = data.flagAt(sx, sy);
		int goalFlag = data.flagAt(tx, ty);
		if (startFlag == ChartPlotterCollisionData.UNKNOWN || goalFlag == ChartPlotterCollisionData.UNKNOWN) return result(ChartPlotterRoute.UNCHARTED);
		if (startFlag != ChartPlotterCollisionData.OPEN || goalFlag != ChartPlotterCollisionData.OPEN) return result(ChartPlotterRoute.BLOCKED);
		if (hull == null) hull = new ChartPlotterRouteHull(config, motion, 0, reverse);
		int initial = heading < 0 ? -1 : ((((heading - 1024 + 64) & 2047) >>> 7) + (reverse ? 8 : 0)) & 15;
		startFlag = hull.flag(data, sx, sy, initial);
		if (startFlag == ChartPlotterCollisionData.UNKNOWN) return result(ChartPlotterRoute.UNCHARTED);
		if (startFlag != ChartPlotterCollisionData.OPEN) return result(ChartPlotterRoute.BLOCKED);
		if (heading >= 0 && (heading & 127) != 0) {
			int floor = ((((heading - 1024) & 2047) >>> 7) + (reverse ? 8 : 0)) & 15;
			int f = hull.turn[floor * 16 + (floor + 1 & 15)].flag(data, sx, sy);
			if (f != ChartPlotterCollisionData.OPEN) return result(f == ChartPlotterCollisionData.UNKNOWN ? ChartPlotterRoute.UNCHARTED : ChartPlotterRoute.BLOCKED);
		}
		int radius = hull.circle.flag(data, tx, ty) == ChartPlotterCollisionData.OPEN ? 0 : Math.min(ChartPlotterRoutes.REACH_RADIUS, Math.max(ARRIVAL_RADIUS, hull.circle.radius * 2));
		ChartPlotterRoute direct = direct(initial, tx, ty);
		if (direct != null) return direct;
		for (int r = 1; r <= radius; r++) {
			int best = Integer.MAX_VALUE;
			for (int y = ty - r; y <= ty + r; y++) for (int x = tx - r; x <= tx + r; x++) {
				if (Math.max(Math.abs(x - tx), Math.abs(y - ty)) != r || hull.flag(data, x, y, -1) != ChartPlotterCollisionData.OPEN || !data.clear(x + motion.offsetX, y + motion.offsetY, tx + 0.5, ty + 0.5)) continue;
				ChartPlotterRoute candidate = direct(initial, x, y);
				if (candidate != null && routeCost < best) {direct = candidate; best = routeCost;}
			}
			if (direct != null) {routeCost = best; return direct;}
			if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
		}
		boolean prepare = terrain == null;
		if (prepare) terrain = ChartPlotterRouteTerrain.create(data, motion, sx, sy, cancel);
		if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
		if (terrain == null) return result(ChartPlotterRoute.COMPLEX);
		int start = terrain.at(sx, sy);
		if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
		if (prepare && !terrain.lowerBound(tx, ty, radius, terrain.distance, hull, cancel)) return result(ChartPlotterRoute.PENDING);
		if (terrain.distance[start] == 0) return result(terrain.limited ? ChartPlotterRoute.COMPLEX : ChartPlotterRoute.NO_ROUTE);
		size = 1;
		allocatedPages = 0;
		pages = new int[(terrain.clearance.length + 255) >>> 8][];
		if (moves == null) moves = new int[terrain.clearance.length];
		if (turns == null) turns = new byte[terrain.clearance.length];
		heap = new ChartPlotterPathHeap(priority, cost);
		for (int d = 0; d < 16; d++) {
			if (initial >= 0 && d != initial || !hull.hull[d].clear(terrain, start)) continue;
			add(start * 16 + d, 0, 0);
		}
		if (heap.size == 0) return result(ChartPlotterRoute.BLOCKED);
		int popped = 0;
		boolean limited = terrain.limited;
		while (heap.size > 0) {
			if ((popped++ & 255) == 0 && cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
			int node = heap.poll();
			int state = pose[node];
			int a = state >>> 4;
			int ad = state & 15;
			if (terrain.distance[a] == 1) return route(node);
			int best = pages[state >>> 12][4096 + (a & 255)];
			if (best != node && cost[node] - cost[best] >= turnCost(ad, pose[best] & 15) && freeTurn(a)) continue;
			expanded++;
			int ax = a % terrain.width;
			int ay = a / terrain.width;
			for (int d = 0; d < 16; d++) {
				int x = ax + motion.x[d];
				int y = ay + motion.y[d];
				if (x <= 0 || y <= 0 || x >= terrain.width - 1 || y >= terrain.height - 1) continue;
				int b = a + terrain.delta[d];
				if (terrain.distance[b] == 0) continue;
				if (fromStart != null && (fromStart[b] == 0 || (long) fromStart[b] + terrain.distance[b] - 2 > distanceLimit * 1000)) continue;
				int next = b * 16 + d;
				int g = cost[node] + motion.cost[d] + turnCost(ad, d);
				if (g > ChartPlotterRouteTerrain.MAX_COST) {limited = true; continue;}
				int[] page = pages[next >>> 12];
				int old = page == null ? 0 : page[next & 4095];
				if (old != 0 && cost[old] <= g) continue;
				int min = page == null ? 0 : page[4096 + (b & 255)];
				if (min != 0 && g - cost[min] >= turnCost(d, pose[min] & 15) && freeTurn(b)) continue;
				if (moveBlocked(a, d) || ad != d && !freeTurn(a) && !hull.turn[ad * 16 + d].clear(terrain, a)) continue;
				if (old == 0) {
					if (size == MAX_NODES || page == null && allocatedPages == MAX_PAGES) return result(ChartPlotterRoute.COMPLEX);
					add(next, g, node);
				} else {
					cost[old] = g;
					parent[old] = node;
					priority[old] = g + (int) ((long) (terrain.distance[b] - 1) * weight / 100);
					if (cost[page[4096 + (b & 255)]] > g) page[4096 + (b & 255)] = old;
					heap.add(old);
				}
			}
		}
		return result(limited ? ChartPlotterRoute.COMPLEX : ChartPlotterRoute.NO_ROUTE);
	}
	private ChartPlotterRoute direct(int initial, int gx, int gy) {
		if (sx == gx && sy == gy) {routeCost = 0; return ChartPlotterRoute.ok(sx, sy, tx, ty, new int[]{sx}, new int[]{sy}, 1, bias, weight);}
		int[] dx = motion.x;
		int[] dy = motion.y;
		long vx = (long) gx - sx;
		long vy = (long) gy - sy;
		for (int d = 0; d < 16; d++) {
			int next = d + 1 & 15;
			int det = dx[d] * dy[next] - dy[d] * dx[next];
			long aa = vx * dy[next] - vy * dx[next];
			long bb = dx[d] * vy - dy[d] * vx;
			if (det == 0 || aa % det != 0 || bb % det != 0) continue;
			long a = aa / det;
			long b = bb / det;
			if (a < 0 || b < 0 || a + b > MAX_NODES) continue;
			long base = a * motion.cost[d] + b * motion.cost[next];
			int changes = a > 0 && b > 0 || initial >= 0 && !(a == 0 && initial == next || b == 0 && initial == d) ? turnCost(0, 1) : 0;
			for (int order = 0; order < 2; order++) {
				boolean forward = (a * motion.cost[d] >= b * motion.cost[next]) == (order == 0);
				int first = forward ? d : next;
				int second = forward ? next : d;
				int n = (int) (forward ? a : b);
				int m = (int) (forward ? b : a);
				if (n == 0) continue;
				int extra = (initial < 0 ? 0 : turnCost(initial, first)) + (m == 0 ? 0 : turnCost(first, second));
				if (extra != changes || base + extra > ChartPlotterRouteTerrain.MAX_COST) continue;
				if (initial >= 0 && turnBlocked(sx, sy, initial, first)) continue;
				int mx = sx + dx[first] * n;
				int my = sy + dy[first] * n;
				if (lineBlocked(sx, sy, first, n) || m > 0 && (turnBlocked(mx, my, first, second) || lineBlocked(mx, my, second, m))) continue;
				routeCost = (int) base + extra;
				return ChartPlotterRoute.ok(sx, sy, tx, ty, m == 0 ? new int[]{sx, gx} : new int[]{sx, mx, gx}, m == 0 ? new int[]{sy, gy} : new int[]{sy, my, gy}, m == 0 ? 2 : 3, bias, weight);
			}
		}
		return null;
	}
	boolean lineBlocked(int x, int y, int d, int steps) {
		for (int i = 0; i < steps; i++) {
			if ((i & 31) == 0 && cancel.getAsBoolean()) return true;
			if (terrain == null || moves == null) {
				if (hull.move[d].flag(data, x, y) != ChartPlotterCollisionData.OPEN) return true;
			} else {
				int a = terrain.at(x, y);
				if (a < 0 || moveBlocked(a, d)) return true;
			}
			x += motion.x[d];
			y += motion.y[d];
		}
		return false;
	}
	boolean turnBlocked(int x, int y, int a, int b) {return a != b && hull.circle.flag(data, x, y) != ChartPlotterCollisionData.OPEN && hull.turn[a * 16 + b].flag(data, x, y) != ChartPlotterCollisionData.OPEN;}
	private void add(int state, int g, int previous) {
		if (size == pose.length) {
			pose = Arrays.copyOf(pose, size * 2);
			cost = Arrays.copyOf(cost, size * 2);
			parent = Arrays.copyOf(parent, size * 2);
			priority = Arrays.copyOf(priority, size * 2);
			heap.position = Arrays.copyOf(heap.position, size * 2);
			heap.cost = priority;
			heap.tie = cost;
		}
		int node = size++;
		pose[node] = state;
		cost[node] = g;
		parent[node] = previous;
		priority[node] = g + (int) ((long) (terrain.distance[state >>> 4] - 1) * weight / 100);
		int[] page = pages[state >>> 12];
		if (page == null) {
			page = new int[4352];
			pages[state >>> 12] = page;
			allocatedPages++;
		}
		page[state & 4095] = node;
		int min = 4096 + ((state >>> 4) & 255);
		if (page[min] == 0 || cost[page[min]] > g) page[min] = node;
		heap.add(node);
	}
	private boolean moveBlocked(int a, int d) {
		int known = 1 << d + 16;
		int valid = 1 << d;
		if ((moves[a] & known) != 0) return (moves[a] & valid) == 0;
		moves[a] |= known;
		if (terrain.pointMoveBlocked(a, d) || !hull.move[d].clear(terrain, a)) return true;
		moves[a] |= valid;
		return false;
	}
	private boolean freeTurn(int a) {
		if (turns[a] == 0) turns[a] = hull.circle.clear(terrain, a) ? (byte) 1 : 2;
		return turns[a] == 1;
	}
	int turnCost(int a, int b) {
		if (a == b || turnBias == 0) return 0;
		int d = Math.abs(a - b);
		return 800 * turnBias + 2000 * Math.min(d, 16 - d);
	}
	private int steering(ChartPlotterRoute route) {
		int cost = 0;
		int previous = heading < 0 ? -1 : ((((heading - 1024 + 64) & 2047) >>> 7) + (reverse ? 8 : 0)) & 15;
		for (int i = 1; i < route.n; i++) {
			int d = motion.dir(route.x[i] - route.x[i - 1], route.y[i] - route.y[i - 1]);
			if (previous >= 0 && previous != d) {
				int change = Math.abs(previous - d);
				cost += 800 * bias + 2000 * Math.min(change, 16 - change);
			}
			previous = d;
		}
		return cost;
	}
	private ChartPlotterRoute route(int last) {
		routeCost = cost[last];
		int n = 0;
		for (int node = last; node != 0; node = parent[node]) n++;
		int[] path = new int[n];
		for (int node = last, i = n - 1; node != 0; node = parent[node], i--) path[i] = node;
		int[] x = new int[n];
		int[] y = new int[n];
		int count = 0;
		for (int i = 0; i < n; i++) {
			if (i > 0 && i + 1 < n && (pose[path[i]] & 15) == (pose[path[i + 1]] & 15)) continue;
			int a = pose[path[i]] >>> 4;
			x[count] = terrain.minX + a % terrain.width;
			y[count++] = terrain.minY + a / terrain.width;
		}
		return ChartPlotterRoute.ok(sx, sy, tx, ty, Arrays.copyOf(x, count), Arrays.copyOf(y, count), count, bias, weight);
	}
	private ChartPlotterRoute result(int status) {
		if (status == ChartPlotterRoute.PENDING) return ChartPlotterRoute.pending(sx, sy, tx, ty, bias, weight);
		if (status == ChartPlotterRoute.UNCHARTED) return ChartPlotterRoute.uncharted(sx, sy, tx, ty, bias, weight);
		if (status == ChartPlotterRoute.BLOCKED) return ChartPlotterRoute.blocked(sx, sy, tx, ty, bias, weight);
		if (status == ChartPlotterRoute.COMPLEX) return ChartPlotterRoute.complex(sx, sy, tx, ty, bias, weight);
		return ChartPlotterRoute.none(sx, sy, tx, ty, bias, weight);
	}
	long bytes() {return pose.length * 16L + (heap == null ? 0 : heap.position.length * 4L + heap.nodes.length * 4L) + allocatedPages * 4352L * 4 + (terrain == null ? 0 : terrain.clearance.length * 5L) + (moves == null ? 0 : moves.length * 5L) + (fromStart == null ? 0 : fromStart.length * 4L);}
}
