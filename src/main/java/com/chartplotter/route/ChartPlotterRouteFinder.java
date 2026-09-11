package com.chartplotter.route;
import com.chartplotter.ChartPlotterTurnPreference;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.WorldEntityConfig;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
final class ChartPlotterRouteFinder {
	private static final int MAX_NODES = 4 << 20;
	private static final int MAX_PAGES = 4096;
	private static final int PAGE_SIZE = 4608;
	final ChartPlotterCollisionData data;
	final WorldEntityConfig config;
	final ChartPlotterRouteMotion motion;
	final int sx;
	final int sy;
	final int tx;
	final int ty;
	final int bias;
	final int weight;
	final int heading;
	int startBlocked;
	final BooleanSupplier cancel;
	ChartPlotterRouteTerrain terrain;
	ChartPlotterRouteHull hull;
	ChartPlotterRouteTarget source;
	ChartPlotterRouteTarget target;
	ChartPlotterRouteComponent component;
	ChartPlotterPathHeap heap;
	int[][] pages;
	int[] pose;
	int[] cost;
	int[] parent;
	int[] priority;
	int[] moves;
	byte[] turns;
	int size = 1;
	int allocatedPages;
	int expanded;
	int peakOpen;
	int routeCost;
	private final int[] directClear = new int[16];
	private int directBlocked;
	private int directX;
	private int directY;
	private ChartPlotterRouteDistances toGoal;
	private int searchWeight;
	private int costLimit = Integer.MAX_VALUE;
	private boolean exact;
	ChartPlotterRouteFinder(ChartPlotterCollisionData data, WorldEntityConfig config, int sx, int sy, int tx, int ty, int bias, double speed, int weight, BooleanSupplier cancel) {
		this(data, config, sx, sy, tx, ty, bias, speed, weight, 0.5, 0.5, -1, cancel);
	}
	ChartPlotterRouteFinder(ChartPlotterCollisionData data, WorldEntityConfig config, int sx, int sy, int tx, int ty, int bias, double speed, int weight, double offsetX, double offsetY, int heading, BooleanSupplier cancel) {
		this.data = data;
		this.config = config;
		ChartPlotterRouteMotion movement;
		try {movement = new ChartPlotterRouteMotion(speed, offsetX, offsetY);}
		catch (ArithmeticException limit) {movement = null;}
		motion = movement;
		this.heading = heading;
		this.sx = sx;
		this.sy = sy;
		this.tx = tx;
		this.ty = ty;
		this.bias = bias;
		this.weight = Math.max(100, Math.min(250, weight));
		searchWeight = bias == ChartPlotterTurnPreference.SMOOTH.bias ? 250 : this.weight;
		this.cancel = cancel;
	}
	ChartPlotterRoute find() {
		if (motion == null) return result(ChartPlotterRoute.COMPLEX);
		ChartPlotterRoute route = search();
		if (route.status == ChartPlotterRoute.OK && (route.x[route.n - 1] != tx || route.y[route.n - 1] != ty) && hull.flag(data, tx, ty) == ChartPlotterCollisionData.OPEN && !cancel.getAsBoolean()) {
			int fallbackCost = routeCost;
			exact = true;
			toGoal = null;
			ChartPlotterRoute candidate = search();
			if (candidate.status == ChartPlotterRoute.OK) route = candidate;
			else {
				routeCost = fallbackCost;
				exact = false;
				toGoal = null;
			}
		}
		if (bias > 0 && route.status == ChartPlotterRoute.OK) {
			route = ChartPlotterRouteSmoother.smooth(this, route);
			while (searchWeight > weight && terrain != null && !cancel.getAsBoolean()) {
				searchWeight = Math.max(weight, searchWeight - 50);
				if (!exact && route.x[route.n - 1] == tx && route.y[route.n - 1] == ty) {
					exact = true;
					toGoal = null;
				}
				costLimit = routeCost(route);
				ChartPlotterRoute candidate = search();
				if (candidate.status == ChartPlotterRoute.NO_ROUTE) break;
				if (candidate.status == ChartPlotterRoute.OK) {
					candidate = ChartPlotterRouteSmoother.smooth(this, candidate);
					if (routeCost(candidate) <= costLimit) route = candidate;
				}
			}
			routeCost = routeCost(route);
		}
		route = route.plan(motion, hull, heading);
		if (heap != null) peakOpen = Math.max(peakOpen, heap.peak);
		return route.status == ChartPlotterRoute.OK ? route.connect(data, source, target) : route;
	}
	private ChartPlotterRoute search() {
		if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
		if (heading >= 0 && config == null) return result(ChartPlotterRoute.UNCHARTED);
		int startFlag = data.flagAt(sx, sy);
		int goalFlag = data.flagAt(tx, ty);
		if (startFlag == ChartPlotterCollisionData.UNKNOWN || goalFlag == ChartPlotterCollisionData.UNKNOWN) return result(ChartPlotterRoute.UNCHARTED);
		if (startFlag != ChartPlotterCollisionData.OPEN || goalFlag != ChartPlotterCollisionData.OPEN) return result(ChartPlotterRoute.BLOCKED);
		if (target == null) target = ChartPlotterRouteTarget.create(data, tx, ty, cancel);
		if (source == null) source = ChartPlotterRouteTarget.create(data, sx, sy, cancel);
		if (target == null || source == null) return result(ChartPlotterRoute.PENDING);
		if (hull == null) {
			try {hull = new ChartPlotterRouteHull(config, motion, 0, false);}
			catch (ArithmeticException limit) {return result(ChartPlotterRoute.COMPLEX);}
			if (heading >= 0) {
				double x = sx + motion.offsetX;
				double y = sy + motion.offsetY;
				for (int d = 0; d < 16; d++) if (hull.geometry.sweep(data, x, y, heading, x, y, ChartPlotterRouteMoves.OR[d]) != ChartPlotterCollisionData.OPEN) startBlocked |= 1 << d;
			}
		}
		if (heading >= 0) {
			int flag = hull.geometry.pose(data, sx + motion.offsetX, sy + motion.offsetY, heading);
			if (flag == ChartPlotterCollisionData.UNKNOWN) return result(ChartPlotterRoute.UNCHARTED);
		}
		int departureRadius = radius(sx, sy);
		int bx = sx;
		int by = sy;
		int distance = Integer.MAX_VALUE;
		boolean unknown = false;
		for (int r = 0; r <= departureRadius && distance == Integer.MAX_VALUE; r++) {
			if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
			for (int y = sy - r; y <= sy + r; y++) for (int x = sx - r; x <= sx + r; x++) {
				if (Math.max(Math.abs(x - sx), Math.abs(y - sy)) != r || !source.contains(x, y)) continue;
				int flag = hull.flag(data, x, y);
				unknown |= flag == ChartPlotterCollisionData.UNKNOWN;
				if (flag != ChartPlotterCollisionData.OPEN || x == sx && y == sy && startBlocked == 65535) continue;
				int d = (x - sx) * (x - sx) + (y - sy) * (y - sy);
				if (d < distance || d == distance && Math.hypot(x - tx, y - ty) < Math.hypot(bx - tx, by - ty)) {bx = x; by = y; distance = d;}
			}
		}
		if (distance == Integer.MAX_VALUE) return result(unknown ? ChartPlotterRoute.UNCHARTED : ChartPlotterRoute.BLOCKED);
		if (directX != bx || directY != by) {
			directX = bx;
			directY = by;
			Arrays.fill(directClear, 0);
			directBlocked = 0;
		}
		int radius = exact ? 0 : ChartPlotterRouteTarget.RADIUS;
		ChartPlotterRoute direct = direct(bx, by, tx, ty, false);
		if (direct != null) return direct;
		if (!exact) {
			direct = direct(bx, by, tx, ty, true);
			if (direct != null) return direct;
			direct = direct(bx, by, Math.max(tx - radius, Math.min(tx + radius, bx)), Math.max(ty - radius, Math.min(ty + radius, by)), true);
			if (direct != null) return direct;
		}
		boolean arrival = exact;
		for (int r = 0; !exact && r <= radius; r++) {
			int best = Integer.MAX_VALUE;
			for (int y = ty - r; y <= ty + r; y++) for (int x = tx - r; x <= tx + r; x++) {
				if (Math.max(Math.abs(x - tx), Math.abs(y - ty)) != r || !target.contains(x, y) || hull.flag(data, x, y) != ChartPlotterCollisionData.OPEN) continue;
				arrival = true;
				ChartPlotterRoute candidate = direct(bx, by, x, y, true);
				if (candidate != null && routeCost < best) {direct = candidate; best = routeCost;}
			}
			if (direct != null) {routeCost = best; return direct;}
			if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
		}
		if (!arrival) return result(ChartPlotterRoute.NO_ROUTE);
		if (terrain == null) {
			if (component == null || component.data != data || !component.contains(sx, sy)) component = ChartPlotterRouteComponent.create(data, sx, sy, cancel);
			if (component == null) return result(ChartPlotterRoute.PENDING);
			if (!component.contains(tx, ty)) return result(ChartPlotterRoute.NO_ROUTE);
			terrain = ChartPlotterRouteTerrain.create(data, motion, sx, sy, cancel);
		}
		if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
		if (terrain == null) return result(ChartPlotterRoute.COMPLEX);
		if (moves == null) moves = new int[terrain.clearance.length];
		if (toGoal == null) toGoal = new ChartPlotterRouteDistances(terrain, hull, tx, ty, radius, sx, sy, departureRadius, target, cancel);
		size = 1;
		allocatedPages = 0;
		pages = new int[(terrain.clearance.length + 255) >>> 8][];
		if (turns == null) turns = new byte[terrain.clearance.length];
		if (pose == null) {
			pose = new int[65536];
			cost = new int[65536];
			parent = new int[65536];
			priority = new int[65536];
		}
		if (heap != null) peakOpen = Math.max(peakOpen, heap.peak);
		heap = new ChartPlotterPathHeap(priority, cost);
		for (int y = sy - departureRadius; y <= sy + departureRadius; y++) {
			if (cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
			for (int x = sx - departureRadius; x <= sx + departureRadius; x++) {
				int a = terrain.at(x, y);
				if (a < 0 || !source.contains(x, y)) continue;
				int g = (int) (1000 * Math.hypot(x - sx, y - sy));
				for (int d = 0; d < 16; d++) {
					if (x == sx && y == sy && (startBlocked & 1 << d) != 0 || !hull.hull[d].clear(terrain, a)) continue;
					int h = toGoal.get(a);
					if (h < 0) return result(ChartPlotterRoute.PENDING);
					if (h > 0) add(a * 16 + d, g, 0, h);
				}
			}
		}
		int popped = 0;
		boolean limited = terrain.limited;
		while (heap.size > 0) {
			if ((popped++ & 255) == 0 && cancel.getAsBoolean()) return result(ChartPlotterRoute.PENDING);
			int node = heap.poll();
			int state = pose[node];
			int a = state >>> 4;
			int ad = state & 15;
			int wx = terrain.minX + a % terrain.width;
			int wy = terrain.minY + a / terrain.width;
			if (exact ? wx == tx && wy == ty : target.contains(wx, wy)) return route(node);
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
				if (terrain.clearance[b] == 0) continue;
				int next = b * 16 + d;
				int g = cost[node] + motion.cost[d] + turnCost(ad, d);
				int[] page = pages[next >>> 12];
				int old = page == null ? 0 : page[next & 4095];
				if (old != 0 && cost[old] <= g) continue;
				int min = page == null ? 0 : page[4096 + (b & 255)];
				if (min != 0 && g - cost[min] >= turnCost(d, pose[min] & 15) && freeTurn(b)) continue;
				if (hull.moveBlocked(terrain, moves, a, d) || ad != d && !freeTurn(a) && turnBlocked(a, ad, d)) continue;
				int h = toGoal.get(b);
				if (h < 0) return result(ChartPlotterRoute.PENDING);
				if (h == 0) continue;
				if ((long) g + h - 1 >= costLimit) continue;
				if (g > ChartPlotterRouteTerrain.MAX_COST) {limited = true; continue;}
				if (old == 0) {
					if (size == MAX_NODES || page == null && allocatedPages == MAX_PAGES) return result(ChartPlotterRoute.COMPLEX);
					add(next, g, node, h);
				} else {
					cost[old] = g;
					parent[old] = node;
					priority[old] = g + (int) ((long) (h - 1) * searchWeight / 100);
					if (cost[page[4096 + (b & 255)]] > g) page[4096 + (b & 255)] = old;
					heap.add(old);
				}
			}
		}
		return result(limited || terrain.limited ? ChartPlotterRoute.COMPLEX : ChartPlotterRoute.NO_ROUTE);
	}
	private int radius(int x, int y) {return hull.circle.flag(data, x, y) == ChartPlotterCollisionData.OPEN ? 0 : ChartPlotterRouteTarget.RADIUS;}
	private ChartPlotterRoute direct(int ax, int ay, int gx, int gy, boolean relaxed) {
		if (!target.contains(gx, gy)) return null;
		int departureCost = (int) (1000 * Math.hypot(ax - sx, ay - sy));
		if (ax == gx && ay == gy || relaxed && target.contains(ax, ay)) {routeCost = departureCost; return ChartPlotterRoute.ok(sx, sy, tx, ty, new int[]{ax}, new int[]{ay}, 1, bias, weight);}
		int[] dx = motion.x;
		int[] dy = motion.y;
		long vx = (long) gx - ax;
		long vy = (long) gy - ay;
		ChartPlotterRoute route = null;
		long best = (long) ChartPlotterRouteTerrain.MAX_COST + 1;
		for (int d = 0; d < 16; d++) {
			int next = d + 1 & 15;
			int det = dx[d] * dy[next] - dy[d] * dx[next];
			long aa = vx * dy[next] - vy * dx[next];
			long bb = dx[d] * vy - dy[d] * vx;
			if (det == 0 || aa % det != 0 || bb % det != 0) continue;
			long a = aa / det;
			long b = bb / det;
			if (a < 0 || b < 0 || a + b > MAX_NODES) continue;
			for (int order = 0; order < 2; order++) {
				boolean forward = (a * motion.cost[d] >= b * motion.cost[next]) == (order == 0);
				int first = forward ? d : next;
				int second = forward ? next : d;
				int n = (int) (forward ? a : b);
				int m = (int) (forward ? b : a);
				if (n == 0) continue;
				if (relaxed) {
					int arrival = arrival(ax, ay, first, n);
					if (arrival >= 0) {n = arrival; m = 0;}
					else m = arrival(ax + dx[first] * n, ay + dy[first] * n, second, m);
					if (m < 0) continue;
				}
				long base = departureCost + (long) n * motion.cost[first] + (long) m * motion.cost[second];
				int extra = m == 0 ? 0 : turnCost(first, second);
				if (base + extra >= best) continue;
				int mx = ax + dx[first] * n;
				int my = ay + dy[first] * n;
				if (departureBlocked(first, n) || m > 0 && (turnBlocked(mx, my, first, second) || lineBlocked(mx, my, second, m))) continue;
				best = base + extra;
				routeCost = (int) best;
				route = ChartPlotterRoute.ok(sx, sy, tx, ty, m == 0 ? new int[]{ax, mx} : new int[]{ax, mx, mx + dx[second] * m}, m == 0 ? new int[]{ay, my} : new int[]{ay, my, my + dy[second] * m}, m == 0 ? 2 : 3, bias, weight);
			}
		}
		return route;
	}
	private int arrival(int ax, int ay, int d, int steps) {
		if (Math.abs(ax + motion.x[d] * steps - tx) > ChartPlotterRouteTarget.RADIUS || Math.abs(ay + motion.y[d] * steps - ty) > ChartPlotterRouteTarget.RADIUS) return -1;
		for (int i = Math.max(0, steps - ChartPlotterRouteTarget.RADIUS * 2); i <= steps; i++) if (target.contains(ax + motion.x[d] * i, ay + motion.y[d] * i)) return i;
		return -1;
	}
	private boolean departureBlocked(int d, int steps) {
		if (directX == sx && directY == sy && (startBlocked & 1 << d) != 0) return true;
		if (steps <= directClear[d]) return false;
		if ((directBlocked & 1 << d) != 0) return true;
		while (directClear[d] < steps) {
			int i = directClear[d];
			if ((i & 31) == 0 && cancel.getAsBoolean()) return true;
			if (hull.move[d].flag(data, directX + motion.x[d] * i, directY + motion.y[d] * i) != ChartPlotterCollisionData.OPEN) {directBlocked |= 1 << d; return true;}
			directClear[d]++;
		}
		return false;
	}
	boolean lineBlocked(int x, int y, int d, int steps) {
		for (int i = 0; i < steps; i++) {
			if ((i & 31) == 0 && cancel.getAsBoolean()) return true;
			if (terrain == null || moves == null) {
				if (hull.move[d].flag(data, x, y) != ChartPlotterCollisionData.OPEN) return true;
			} else {
				int a = terrain.at(x, y);
				if (a < 0 || hull.moveBlocked(terrain, moves, a, d)) return true;
			}
			x += motion.x[d];
			y += motion.y[d];
		}
		return false;
	}
	boolean turnBlocked(int x, int y, int a, int b) {
		if (a == b) return false;
		int tile = terrain == null ? -1 : terrain.at(x, y);
		if (tile >= 0 && pages != null && pages[tile >>> 8] != null) return !freeTurn(tile) && turnBlocked(tile, a, b);
		return hull.circle.flag(data, x, y) != ChartPlotterCollisionData.OPEN && hull.turn(a, b).flag(data, x, y) != ChartPlotterCollisionData.OPEN;
	}
	private boolean turnBlocked(int a, int from, int to) {
		int change = (to - from + 24 & 15) - 8;
		if (change == -8) return true;
		int mask = ((1 << Math.abs(change)) - 1) << (change > 0 ? from : to);
		mask = (mask | mask >>> 16) & 65535;
		int[] page = pages[a >>> 8];
		int index = 4352 + (a & 255);
		int flags = page[index];
		if ((mask & flags >>> 16 & ~flags) != 0) return true;
		for (int missing = mask & ~(flags >>> 16); missing != 0; missing &= missing - 1) {
			int d = Integer.numberOfTrailingZeros(missing);
			flags |= 1 << (d + 16);
			if (!hull.turn(d, d + 1 & 15).clear(terrain, a)) {page[index] = flags; return true;}
			flags |= 1 << d;
		}
		page[index] = flags;
		return false;
	}
	private void add(int state, int g, int previous, int h) {
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
		priority[node] = g + (int) ((long) (h - 1) * searchWeight / 100);
		int[] page = pages[state >>> 12];
		if (page == null) {
			page = new int[PAGE_SIZE];
			pages[state >>> 12] = page;
			allocatedPages++;
		}
		page[state & 4095] = node;
		int min = 4096 + ((state >>> 4) & 255);
		if (page[min] == 0 || cost[page[min]] > g) page[min] = node;
		heap.add(node);
	}
	private boolean freeTurn(int a) {
		if (turns[a] == 0) turns[a] = hull.circle.clear(terrain, a) ? (byte) 1 : 2;
		return turns[a] == 1;
	}
	int turnCost(int a, int b) {
		if (a < 0 || b < 0 || a == b || bias == 0) return 0;
		int d = Math.abs(a - b);
		return 800 * bias + 2000 * Math.min(d, 16 - d);
	}
	int routeCost(ChartPlotterRoute route) {
		int cost = (int) (1000 * Math.hypot(route.x[0] - sx, route.y[0] - sy));
		int previous = -1;
		for (int i = 1; i < route.n; i++) {
			int dx = route.x[i] - route.x[i - 1];
			int dy = route.y[i] - route.y[i - 1];
			int d = motion.dir(dx, dy);
			cost += motion.cost[d] * (motion.x[d] == 0 ? dy / motion.y[d] : dx / motion.x[d]) + turnCost(previous, d);
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
	long bytes() {return (pose == null ? 0 : pose.length * 16L) + (heap == null ? 0 : heap.position.length * 4L + heap.nodes.length * 4L) + allocatedPages * PAGE_SIZE * 4L + (terrain == null ? 0 : terrain.clearance.length + terrain.open.length * 8L) + (moves == null ? 0 : moves.length * 5L) + (toGoal == null ? 0 : toGoal.bytes()) + (source == null ? 0 : source.bytes()) + (target == null ? 0 : target.bytes()) + (component == null ? 0 : component.bytes());}
}
