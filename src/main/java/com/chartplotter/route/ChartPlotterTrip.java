package com.chartplotter.route;
import com.chartplotter.ChartPlotterRouteEffort;
import java.util.Arrays;
public final class ChartPlotterTrip {
	private final int generation;
	private final int[] x;
	private final int[] y;
	private final ChartPlotterRoute[] routes;
	private ChartPlotterTrip(int generation, int[] x, int[] y, ChartPlotterRoute[] routes) {
		this.generation = generation;
		this.x = x;
		this.y = y;
		this.routes = routes;
	}
	static ChartPlotterTrip empty(int generation) {return new ChartPlotterTrip(generation, new int[0], new int[0], new ChartPlotterRoute[0]);}
	static ChartPlotterTrip single(int generation, int x, int y, ChartPlotterRoute route) {return new ChartPlotterTrip(generation, new int[]{x}, new int[]{y}, new ChartPlotterRoute[]{route});}
	public int size() {return x.length;}
	public boolean empty() {return x.length == 0;}
	public int x(int i) {return x[i];}
	public int y(int i) {return y[i];}
	public ChartPlotterRoute route(int i) {return routes[i];}
	public ChartPlotterRoute active() {return routes.length == 0 ? null : routes[0];}
	int generation() {return generation;}
	ChartPlotterTrip append(int generation, int tx, int ty, ChartPlotterRoute route) {
		int n = x.length;
		int[] nx = Arrays.copyOf(x, n + 1);
		int[] ny = Arrays.copyOf(y, n + 1);
		ChartPlotterRoute[] nr = Arrays.copyOf(routes, n + 1);
		nx[n] = tx;
		ny[n] = ty;
		nr[n] = route;
		return new ChartPlotterTrip(generation, nx, ny, nr);
	}
	ChartPlotterTrip truncate(int generation, int n) {
		if (n <= 0) return empty(generation);
		return new ChartPlotterTrip(generation, Arrays.copyOf(x, n), Arrays.copyOf(y, n), Arrays.copyOf(routes, n));
	}
	ChartPlotterTrip advance(int generation) {
		if (x.length <= 1) return empty(generation);
		return new ChartPlotterTrip(generation, Arrays.copyOfRange(x, 1, x.length), Arrays.copyOfRange(y, 1, y.length), Arrays.copyOfRange(routes, 1, routes.length));
	}
	ChartPlotterTrip move(int generation, int i, int tx, int ty) {
		int[] nx = x.clone();
		int[] ny = y.clone();
		nx[i] = tx;
		ny[i] = ty;
		return new ChartPlotterTrip(generation, nx, ny, routes);
	}
	ChartPlotterTrip generation(int generation) {return new ChartPlotterTrip(generation, x, y, routes);}
	ChartPlotterTrip pending(int generation, int sx, int sy, int turnBias, int weight, ChartPlotterRouteEffort effort, boolean[] selected) {
		ChartPlotterRoute[] nr = routes.clone();
		for (int i = 0; i < nr.length; i++) {
			if (!selected[i]) continue;
			int ax = i == 0 ? sx : x[i - 1];
			int ay = i == 0 ? sy : y[i - 1];
			nr[i] = ChartPlotterRoute.pending(ax, ay, x[i], y[i], turnBias, weight).effort(effort);
		}
		return new ChartPlotterTrip(generation, x, y, nr);
	}
	ChartPlotterTrip route(int i, ChartPlotterRoute route) {
		ChartPlotterRoute[] nr = routes.clone();
		nr[i] = route;
		return new ChartPlotterTrip(generation, x, y, nr);
	}
}
