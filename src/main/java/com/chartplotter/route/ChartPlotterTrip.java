package com.chartplotter.route;
import com.chartplotter.ChartPlotterRouteEffort;
import java.util.Arrays;
public final class ChartPlotterTrip {
	private static final int[] EMPTY_INT = new int[0];
	private static final ChartPlotterRoute[] EMPTY_ROUTE = new ChartPlotterRoute[0];
	private final int generation;
	private final int completed;
	private final int[] x;
	private final int[] y;
	private final ChartPlotterRoute[] routes;
	private ChartPlotterTrip(int generation, int completed, int[] x, int[] y, ChartPlotterRoute[] routes) {
		this.generation = generation;
		this.completed = completed;
		this.x = x;
		this.y = y;
		this.routes = routes;
	}
	static ChartPlotterTrip empty(int generation) {return new ChartPlotterTrip(generation, 0, EMPTY_INT, EMPTY_INT, EMPTY_ROUTE);}
	static ChartPlotterTrip single(int generation, int x, int y, ChartPlotterRoute route) {return new ChartPlotterTrip(generation, 0, new int[]{x}, new int[]{y}, new ChartPlotterRoute[]{route});}
	public int size() {return x.length;}
	public boolean empty() {return x.length == 0;}
	public int stopNumber() {return empty() ? 0 : completed + 1;}
	public int totalStops() {return completed + x.length;}
	public int x(int i) {return x[i];}
	public int y(int i) {return y[i];}
	public ChartPlotterRoute route(int i) {return routes[i];}
	public Object stopKey() {return x;}
	public ChartPlotterRoute active() {return routes.length == 0 ? null : routes[0];}
	public double distance(double bx, double by, boolean total) {
		if (empty()) return Double.NaN;
		double distance = 0;
		for (int i = 0; i < (total ? routes.length : 1); i++) {
			ChartPlotterRoute route = routes[i];
			if (route == null || route.status != ChartPlotterRoute.OK || route.n == 0) return Double.NaN;
			if (i > 0) {
				bx = route.x[0] + route.offsetX;
				by = route.y[0] + route.offsetY;
			}
			for (int j = Math.min(1, route.n - 1); j < route.n; j++) {
				double x = route.x[j] + route.offsetX;
				double y = route.y[j] + route.offsetY;
				distance += Math.hypot(x - bx, y - by);
				bx = x;
				by = y;
			}
		}
		return distance;
	}
	int generation() {return generation;}
	ChartPlotterTrip append(int generation, int tx, int ty, ChartPlotterRoute route) {
		int n = x.length;
		int[] nx = Arrays.copyOf(x, n + 1);
		int[] ny = Arrays.copyOf(y, n + 1);
		ChartPlotterRoute[] nr = Arrays.copyOf(routes, n + 1);
		nx[n] = tx;
		ny[n] = ty;
		nr[n] = route;
		return new ChartPlotterTrip(generation, completed, nx, ny, nr);
	}
	ChartPlotterTrip truncate(int generation, int n) {
		if (n <= 0) return empty(generation);
		return new ChartPlotterTrip(generation, completed, Arrays.copyOf(x, n), Arrays.copyOf(y, n), Arrays.copyOf(routes, n));
	}
	ChartPlotterTrip remove(int generation, int i) {
		if (x.length <= 1) return empty(generation);
		int n = x.length - 1;
		int[] nx = new int[n];
		int[] ny = new int[n];
		ChartPlotterRoute[] nr = new ChartPlotterRoute[n];
		System.arraycopy(x, 0, nx, 0, i);
		System.arraycopy(y, 0, ny, 0, i);
		System.arraycopy(routes, 0, nr, 0, i);
		System.arraycopy(x, i + 1, nx, i, n - i);
		System.arraycopy(y, i + 1, ny, i, n - i);
		System.arraycopy(routes, i + 1, nr, i, n - i);
		if (i < n) nr[i] = null;
		return new ChartPlotterTrip(generation, completed, nx, ny, nr);
	}
	ChartPlotterTrip advance(int generation) {
		if (x.length <= 1) return empty(generation);
		return new ChartPlotterTrip(generation, completed + 1, Arrays.copyOfRange(x, 1, x.length), Arrays.copyOfRange(y, 1, y.length), Arrays.copyOfRange(routes, 1, routes.length));
	}
	ChartPlotterTrip move(int generation, int i, int tx, int ty) {
		int[] nx = x.clone();
		int[] ny = y.clone();
		nx[i] = tx;
		ny[i] = ty;
		return new ChartPlotterTrip(generation, completed, nx, ny, routes);
	}
	ChartPlotterTrip generation(int generation) {return new ChartPlotterTrip(generation, completed, x, y, routes);}
	ChartPlotterTrip pending(int generation, int sx, int sy, int turnBias, int weight, ChartPlotterRouteEffort effort, boolean[] selected) {
		ChartPlotterRoute[] nr = routes.clone();
		for (int i = 0; i < nr.length; i++) {
			if (!selected[i]) continue;
			if (nr[i] != null && nr[i].status == ChartPlotterRoute.OK && nr[i].tx == x[i] && nr[i].ty == y[i]) {
				nr[i] = nr[i].recalculate();
				continue;
			}
			int ax = ChartPlotterRoutes.legStartX(this, i, sx);
			int ay = ChartPlotterRoutes.legStartY(this, i, sy);
			nr[i] = ChartPlotterRoute.pending(ax, ay, x[i], y[i], turnBias, weight).effort(effort);
		}
		return new ChartPlotterTrip(generation, completed, x, y, nr);
	}
	ChartPlotterTrip failed(int id, boolean[] awaiting) {
		if (generation != id) return this;
		ChartPlotterRoute[] next = routes.clone();
		for (int i = 0; i < next.length; i++) {
			ChartPlotterRoute route = next[i];
			if (awaiting[i] && route != null) next[i] = ChartPlotterRoute.failed(route.sx, route.sy, route.tx, route.ty, route.turnBias, route.weight).effort(route.effort);
		}
		return new ChartPlotterTrip(generation, completed, x, y, next);
	}
	ChartPlotterTrip route(int i, ChartPlotterRoute route) {
		ChartPlotterRoute[] nr = routes.clone();
		nr[i] = route != null && route.status == ChartPlotterRoute.PENDING && nr[i] != null && nr[i].status == ChartPlotterRoute.OK && nr[i].tx == route.tx && nr[i].ty == route.ty ? nr[i].recalculate() : route;
		return new ChartPlotterTrip(generation, completed, x, y, nr);
	}
}
