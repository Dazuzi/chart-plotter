package com.chartplotter;
public enum ChartPlotterTurnPreference {
	DIRECT("Direct", 0),
	BALANCED("Balanced", 5),
	SMOOTH("Smooth", 10);
	final int bias;
	private final String name;
	ChartPlotterTurnPreference(String name, int bias) {
		this.name = name;
		this.bias = bias;
	}
	@Override
	public String toString() {return name;}
}
