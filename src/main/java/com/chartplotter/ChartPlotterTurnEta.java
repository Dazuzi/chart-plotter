package com.chartplotter;
public enum ChartPlotterTurnEta {
	OFF("Off"),
	SECONDS("Seconds"),
	TICKS("Ticks");
	private final String name;
	ChartPlotterTurnEta(String name) {this.name = name;}
	@Override
	public String toString() {return name;}
}
