package com.chartplotter;
public enum ChartPlotterRouteEffort {
	VERY_HIGH("Very high", 16, false, true),
	HIGH("High", 8, false, true),
	MEDIUM("Medium", 16, true, true),
	LOW("Low", 8, true, true),
	VERY_LOW("Very low", 8, true, false);
	final int dirs;
	final boolean fast;
	final boolean footprint;
	private final String name;
	ChartPlotterRouteEffort(String name, int dirs, boolean fast, boolean footprint) {
		this.name = name;
		this.dirs = dirs;
		this.fast = fast;
		this.footprint = footprint;
	}
	@Override
	public String toString() {return name;}
}
