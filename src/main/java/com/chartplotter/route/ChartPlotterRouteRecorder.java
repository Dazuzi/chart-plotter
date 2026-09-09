package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionCodec;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.RuneLite;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ChartPlotterRouteRecorder {
	private final Properties input = new Properties();
	ChartPlotterRouteRecorder(WorldEntityConfig config, int actualHeading, double speed, double acceleration, double maxSpeed, int moveMode) {
		input.setProperty("schema", "3");
		input.setProperty("ship.present", Boolean.toString(config != null));
		if (config != null) {
			input.setProperty("ship.id", Integer.toString(config.getId()));
			input.setProperty("ship.category", Integer.toString(config.getCategory()));
			input.setProperty("ship.x", Integer.toString(config.getBoundsX()));
			input.setProperty("ship.y", Integer.toString(config.getBoundsY()));
			input.setProperty("ship.width", Integer.toString(config.getBoundsWidth()));
			input.setProperty("ship.height", Integer.toString(config.getBoundsHeight()));
		}
		input.setProperty("actualHeading", Integer.toString(actualHeading));
		input.setProperty("speed", Double.toString(speed));
		input.setProperty("acceleration", Double.toString(acceleration));
		input.setProperty("maxSpeed", Double.toString(maxSpeed));
		input.setProperty("moveMode", Integer.toString(moveMode));
	}
	void position(int baseX, int baseY, int plane, LocalPoint actual, LocalPoint anchor) {
		input.setProperty("plane", Integer.toString(plane));
		if (actual != null) {
			input.setProperty("actual.xFine", Integer.toString(baseX * Perspective.LOCAL_TILE_SIZE + actual.getX()));
			input.setProperty("actual.yFine", Integer.toString(baseY * Perspective.LOCAL_TILE_SIZE + actual.getY()));
		}
		if (anchor != null) {
			input.setProperty("anchor.xFine", Integer.toString(baseX * Perspective.LOCAL_TILE_SIZE + anchor.getX()));
			input.setProperty("anchor.yFine", Integer.toString(baseY * Perspective.LOCAL_TILE_SIZE + anchor.getY()));
		}
	}
	Path write(ChartPlotterCollisionData data, ChartPlotterRoute route, double speed, long elapsedNanos) throws IOException {
		Path root = RuneLite.RUNELITE_DIR.toPath().resolve("chart-plotter/route-recordings");
		Files.createDirectories(root);
		Path dir = Files.createTempDirectory(root, "route-");
		if (!ChartPlotterCollisionCodec.write(dir.toFile(), dir.resolve("collision.bin").toFile(), data)) throw new IOException("Could not save routing datasets to " + dir);
		Properties properties = new Properties();
		properties.putAll(input);
		properties.setProperty("sx", Integer.toString(route.sx));
		properties.setProperty("sy", Integer.toString(route.sy));
		properties.setProperty("tx", Integer.toString(route.tx));
		properties.setProperty("ty", Integer.toString(route.ty));
		properties.setProperty("planningSpeed", Double.toString(speed));
		properties.setProperty("turnBias", Integer.toString(route.turnBias));
		properties.setProperty("weight", Integer.toString(route.weight));
		properties.setProperty("elapsedNanos", Long.toString(elapsedNanos));
		properties.setProperty("status", Integer.toString(route.status));
		properties.setProperty("collisionRevision", Long.toString(data.rev));
		properties.setProperty("route.x", coordinates(route.x, route.n));
		properties.setProperty("route.y", coordinates(route.y, route.n));
		try (OutputStream out = Files.newOutputStream(dir.resolve("request.properties"))) {properties.store(out, null);}
		return dir;
	}
	private static String coordinates(int[] values, int n) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < n; i++) {
			if (i > 0) text.append(',');
			text.append(values[i]);
		}
		return text.toString();
	}
}
