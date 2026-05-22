package com.chartplotter;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
public class ChartPlotterPluginTest {
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		ExternalPluginManager.loadBuiltin(ChartPlotterPlugin.class);
		RuneLite.main(args);
	}
}
