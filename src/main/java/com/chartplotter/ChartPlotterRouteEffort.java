package com.chartplotter;
public enum ChartPlotterRouteEffort {
	VERY_HIGH("Very high", 16, false, true, true),
	HIGH("High", 8, false, true, true),
	MEDIUM("Medium", 16, true, true, false),
	LOW("Low", 8, true, true, false),
	VERY_LOW("Very low", 8, true, false, false);
	final int dirs;
	final boolean fast;
	final boolean footprint;
	final boolean refine;
	private final String name;
	ChartPlotterRouteEffort(String name, int dirs, boolean fast, boolean footprint, boolean refine) {
		this.name = name;
		this.dirs = dirs;
		this.fast = fast;
		this.footprint = footprint;
		this.refine = refine;
	}
	@Override
	public String toString() {return name;}
}
