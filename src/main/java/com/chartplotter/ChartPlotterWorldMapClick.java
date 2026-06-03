package com.chartplotter;
public enum ChartPlotterWorldMapClick {
	CLICK("Click"),
	CTRL_CLICK("Ctrl-click");
	private final String name;
	ChartPlotterWorldMapClick(String name) {this.name = name;}
	@Override
	public String toString() {return name;}
}
