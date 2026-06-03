package com.chartplotter;
public enum ChartPlotterLineMode {
	OFF("Off", false, false),
	ON("On", true, false),
	BLOCKED("On + blocked", true, true);
	public final boolean on;
	public final boolean blocked;
	private final String name;
	ChartPlotterLineMode(String name, boolean on, boolean blocked) {
		this.name = name;
		this.on = on;
		this.blocked = blocked;
	}
	@Override
	public String toString() {return name;}
}
