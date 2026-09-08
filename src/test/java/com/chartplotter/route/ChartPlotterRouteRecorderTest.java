package com.chartplotter.route;

import com.chartplotter.collision.ChartPlotterCollisionCodec;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

public class ChartPlotterRouteRecorderTest {
	@Test
	public void recordingPreservesSearchInputsAndReplaysExactly() throws Exception {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(0, 0, 32, 32);
		long key = ChartPlotterCollisionData.key(30, 30);
		chunks.put(key, new ChartPlotterCollisionData.Chunk(7, 2));
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks, 37);
		WorldEntityConfig config = ChartPlotterRoutingAudit.config(384, 1152);
		ChartPlotterRoute route = new ChartPlotterRouteFinder(data, config, 1536, 100, 100, 140, 105, 5, true, 1, 0.25, 0.75, 100, () -> false).find();
		assertEquals(ChartPlotterRoute.OK, route.status);
		ChartPlotterRouteRecorder recorder = new ChartPlotterRouteRecorder(config, 1408, 2.5, 0.25, 3, 3);
		recorder.position(3200, 3000, 0, new LocalPoint(1234, 567, WorldView.TOPLEVEL), new LocalPoint(1345, 678, WorldView.TOPLEVEL));
		Path dir = recorder.write(data, route, 1536, true, 1, 0.25, 0.75, 123456);
		try {
			Properties properties = new Properties();
			try (InputStream in = Files.newInputStream(dir.resolve("request.properties"))) {properties.load(in);}
			assertEquals("2", properties.getProperty("schema"));
			assertEquals("0.25", properties.getProperty("offsetX"));
			assertEquals("0.75", properties.getProperty("offsetY"));
			assertEquals("1.0", properties.getProperty("planningSpeed"));
			assertEquals("384", properties.getProperty("ship.width"));
			assertEquals("1152", properties.getProperty("ship.height"));
			assertEquals("1408", properties.getProperty("actualHeading"));
			assertEquals("2.5", properties.getProperty("speed"));
			assertEquals("0.25", properties.getProperty("acceleration"));
			assertEquals("3.0", properties.getProperty("maxSpeed"));
			assertEquals("37", properties.getProperty("collisionRevision"));
			assertEquals("123456", properties.getProperty("elapsedNanos"));
			assertEquals("410834", properties.getProperty("actual.xFine"));
			assertEquals("384567", properties.getProperty("actual.yFine"));
			assertEquals("410945", properties.getProperty("anchor.xFine"));
			assertEquals("384678", properties.getProperty("anchor.yFine"));
			Map<Long, ChartPlotterCollisionData.Chunk> restored = ChartPlotterCollisionCodec.read(dir.resolve("collision.bin").toFile());
			assertEquals(data.size(), restored.size());
			assertEquals(7, restored.get(key).known);
			assertEquals(2, restored.get(key).blocked);
			ChartPlotterRoute replay = ChartPlotterRoutingAudit.replay(dir.toString());
			assertEquals(route.status, replay.status);
			assertEquals(route.n, replay.n);
			for (int i = 0; i < route.n; i++) {assertEquals(route.x[i], replay.x[i]); assertEquals(route.y[i], replay.y[i]);}
		} finally {
			Files.deleteIfExists(dir.resolve("request.properties"));
			Files.deleteIfExists(dir.resolve("collision.bin"));
			Files.delete(dir);
		}
	}
	@Test
	public void recordsFailedSearchWithoutShipConfig() throws Exception {
		Map<Long, ChartPlotterCollisionData.Chunk> chunks = ChartPlotterRoutingAudit.open(0, 0, 4, 4);
		ChartPlotterRoutingAudit.block(chunks, 20, 10);
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(chunks);
		ChartPlotterRoute route = ChartPlotterRoute.blocked(10, 10, 20, 10, 5, 100);
		Path dir = new ChartPlotterRouteRecorder(null, -1, 0, 0, 0, 0).write(data, route, -1, false, 1, 0.5, 0.5, 0);
		try {
			assertEquals(ChartPlotterRoute.BLOCKED, ChartPlotterRoutingAudit.replay(dir.toString()).status);
		} finally {
			Files.deleteIfExists(dir.resolve("request.properties"));
			Files.deleteIfExists(dir.resolve("collision.bin"));
			Files.delete(dir);
		}
	}
}
