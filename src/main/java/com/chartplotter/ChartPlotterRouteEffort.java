package com.chartplotter;
public enum ChartPlotterRouteEffort {
	FAST("Fast", 140, 1),
	REFINED("Refined", 110, 3),
	MAXIMUM("Maximum", 100, 8);
	public final int weight;
	public final long nanos;
	private final String name;
	ChartPlotterRouteEffort(String name, int weight, int seconds) {
		this.name = name;
		this.weight = weight;
		nanos = seconds * 1_000_000_000L;
	}
	@Override
	public String toString() {return name;}
}
