package com.chartplotter;
public enum ChartPlotterCacheOverlay {
	OFF("Off", false, false),
	WORLD("World", true, false),
	WORLD_MAP("World map", false, true),
	BOTH("Both", true, true);
	final boolean world;
	final boolean worldMap;
	private final String name;
	ChartPlotterCacheOverlay(String name, boolean world, boolean worldMap) {
		this.name = name;
		this.world = world;
		this.worldMap = worldMap;
	}
	@Override
	public String toString() {return name;}
}
