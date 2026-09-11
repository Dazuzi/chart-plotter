package com.chartplotter;
import com.chartplotter.util.ChartPlotterVersions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
public final class ChartPlotterMigration {
	private static final String[] KEYS = {"routeEffort", "infoTripEta", "infoStopEta", "infoTurnEta", "nodeEditor", "sparseRouteDebug", "recordNextRoute", "sailingSlide"};
	private ChartPlotterMigration() {}
	static void config(Function<String, String> read, BiConsumer<String, String> write) {
		for (String key : KEYS) {
			String old = read.apply(key);
			String next = value(key, old);
			if (Objects.equals(old, next)) continue;
			write.accept(key, next);
		}
		String hints = read.apply("worldMapTripHints");
		if (hints == null) return;
		String tooltips = value("worldMapTripHints", hints);
		if (tooltips != null && read.apply("worldMapTooltips") == null) write.accept("worldMapTooltips", tooltips);
		write.accept("worldMapTripHints", null);
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
			case "worldMapTripHints":
				if ("true".equalsIgnoreCase(value)) return ChartPlotterMapTooltip.BOTH.name();
				if ("false".equalsIgnoreCase(value)) return ChartPlotterMapTooltip.TRIP_INFO.name();
				return null;
			case "nodeEditor":
			case "sparseRouteDebug":
			case "recordNextRoute":
			case "sailingSlide":
				return null;
			default:
				return value;
		}
	}
	static synchronized void files(File dir) throws IOException {
		Files.deleteIfExists(new File(dir, "sparse.bin").toPath());
		Files.deleteIfExists(new File(dir, "sparse.bin.tmp").toPath());
		ChartPlotterVersions.remove(dir, "sparse");
	}
}
