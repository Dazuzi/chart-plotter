package com.chartplotter;
public enum ChartPlotterRouteEffort {
	VERY_HIGH("Very high", 100, 120),
	HIGH("High", 125, 100),
	BALANCED("Balanced", 175, 80),
	FAST("Fast", 250, 60);
	public final int weight;
	public final int corridor;
	private final String name;
	ChartPlotterRouteEffort(String name, int weight, int corridor) {
		this.name = name;
		this.weight = weight;
		this.corridor = corridor;
	}
	@Override
	public String toString() {return name;}
}
