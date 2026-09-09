package com.chartplotter;
import com.chartplotter.util.ChartPlotterVersions;
import net.runelite.client.config.ConfigManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
public final class ChartPlotterMigration {
	private static final String[] KEYS = {"routeEffort", "infoTripEta", "infoStopEta", "infoTurnEta", "nodeEditor", "sparseRouteDebug", "recordNextRoute"};
	private ChartPlotterMigration() {}
	static void config(ConfigManager manager) {
		for (String key : KEYS) {
			String old = manager.getConfiguration("chartplotter", key);
			String next = value(key, old);
			if (Objects.equals(old, next)) continue;
			if (next == null) manager.unsetConfiguration("chartplotter", key);
			else manager.setConfiguration("chartplotter", key, next);
		}
	}
	static String value(String key, String value) {
		switch (key) {
			case "routeEffort":
				if ("HIGH".equals(value) || "REFINED".equals(value)) return ChartPlotterRouteEffort.BALANCED.name();
				if ("VERY_HIGH".equals(value)) return ChartPlotterRouteEffort.MAXIMUM.name();
				return value;
			case "infoTripEta":
			case "infoStopEta":
			case "infoTurnEta":
				if ("true".equalsIgnoreCase(value)) return ChartPlotterEtaMode.SECONDS.name();
				if ("false".equalsIgnoreCase(value)) return ChartPlotterEtaMode.OFF.name();
				return value;
			case "nodeEditor":
			case "sparseRouteDebug":
			case "recordNextRoute":
				return null;
			default:
				return value;
		}
	}
	public static void files(File dir) throws IOException {
		Files.deleteIfExists(new File(dir, "sparse.bin").toPath());
		Files.deleteIfExists(new File(dir, "sparse.bin.tmp").toPath());
		ChartPlotterVersions.remove(dir, "sparse");
	}
}
