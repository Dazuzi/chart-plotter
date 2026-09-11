package com.chartplotter;
public enum ChartPlotterMapTooltip {
	OFF("Off", false, false),
	INSTRUCTIONS("Instructions", true, false),
	TRIP_INFO("Trip info", false, true),
	BOTH("Both", true, true);
	public final boolean instructions;
	public final boolean info;
	private final String name;
	ChartPlotterMapTooltip(String name, boolean instructions, boolean info) {
		this.name = name;
		this.instructions = instructions;
		this.info = info;
	}
	@Override
	public String toString() {return name;}
}
