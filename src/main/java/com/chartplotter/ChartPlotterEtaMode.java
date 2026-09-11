package com.chartplotter;
import net.runelite.api.Constants;
public enum ChartPlotterEtaMode {
	OFF("Off"),
	SECONDS("Seconds"),
	TICKS("Ticks");
	private final String name;
	ChartPlotterEtaMode(String name) {this.name = name;}
	public String format(double ticks) {
		if (!Double.isFinite(ticks) || ticks < 0) return "-";
		if (this == TICKS) return (long) Math.ceil(ticks) + "t";
		long seconds = (long) Math.ceil(ticks * Constants.GAME_TICK_LENGTH / 1000);
		return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
	}
	@Override
	public String toString() {return name;}
}
