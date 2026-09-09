package com.chartplotter;
public enum ChartPlotterEtaMode {
	OFF("Off"),
	SECONDS("Seconds"),
	TICKS("Ticks");
	private final String name;
	ChartPlotterEtaMode(String name) {this.name = name;}
	@Override
	public String toString() {return name;}
}
