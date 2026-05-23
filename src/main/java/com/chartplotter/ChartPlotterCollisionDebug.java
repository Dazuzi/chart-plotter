package com.chartplotter;
public enum ChartPlotterCollisionDebug {
	OFF("Off"),
	DOTS("Dots"),
	MASK("Masked");
	private final String name;
	ChartPlotterCollisionDebug(String name) {
		this.name = name;
	}
	@Override
	public String toString() {return name;}
}
