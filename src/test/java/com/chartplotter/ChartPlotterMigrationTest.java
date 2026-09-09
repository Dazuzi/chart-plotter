package com.chartplotter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
public class ChartPlotterMigrationTest {
	@Rule public final TemporaryFolder files = new TemporaryFolder();
	@Test
	public void preservesSearchEffortIntent() {
		assertEquals("BALANCED", ChartPlotterMigration.value("routeEffort", "HIGH"));
		assertEquals("MAXIMUM", ChartPlotterMigration.value("routeEffort", "VERY_HIGH"));
		assertEquals("BALANCED", ChartPlotterMigration.value("routeEffort", "REFINED"));
		for (ChartPlotterRouteEffort effort : ChartPlotterRouteEffort.values()) assertEquals(effort.name(), ChartPlotterMigration.value("routeEffort", effort.name()));
		assertNull(ChartPlotterMigration.value("routeEffort", null));
	}
	@Test
	public void preservesEnabledAndDisabledBooleanEtas() {
		for (String key : new String[]{"infoTripEta", "infoStopEta", "infoTurnEta"}) {
			assertEquals("SECONDS", ChartPlotterMigration.value(key, "true"));
			assertEquals("OFF", ChartPlotterMigration.value(key, "false"));
			assertNull(ChartPlotterMigration.value(key, null));
			for (ChartPlotterEtaMode mode : ChartPlotterEtaMode.values()) assertEquals(mode.name(), ChartPlotterMigration.value(key, mode.name()));
		}
	}
	@Test
	public void removesRetiredSettingsRegardlessOfValue() {
		for (String key : new String[]{"nodeEditor", "sparseRouteDebug", "recordNextRoute"}) {
			for (String value : new String[]{null, "true", "false", "invalid"}) assertNull(ChartPlotterMigration.value(key, value));
		}
	}
	@Test
	public void leavesUnchangedReleaseSettingsAndUnknownValuesAlone() {
		for (String key : new String[]{"courseTurnEta", "routeShape", "worldLineMode", "minimapLineMode", "worldMapLineMode", "lineColor", "infoBoatSpeed", "futureSetting"}) {
			for (String value : new String[]{null, "OFF", "SECONDS", "TICKS", "SMOOTH", "true", "-1174602497"}) assertEquals(value, ChartPlotterMigration.value(key, value));
		}
		assertEquals("unknown", ChartPlotterMigration.value("routeEffort", "unknown"));
		assertEquals("unknown", ChartPlotterMigration.value("infoTripEta", "unknown"));
	}
	@Test
	public void configMigrationIsIdempotent() {
		for (String key : new String[]{"routeEffort", "infoTripEta", "infoStopEta", "infoTurnEta", "nodeEditor", "sparseRouteDebug", "recordNextRoute"}) {
			for (String value : new String[]{null, "HIGH", "VERY_HIGH", "REFINED", "true", "false", "TICKS"}) {
				String next = ChartPlotterMigration.value(key, value);
				assertEquals(next, ChartPlotterMigration.value(key, next));
			}
		}
	}
	@Test
	public void removesSparseCachesAndMetadataWithoutChangingRetainedData() throws Exception {
		File dir = files.newFolder();
		Path sparse = write(dir, "sparse.bin", "old nodes");
		Path pending = write(dir, "sparse.bin.tmp", "pending nodes");
		Path collision = write(dir, "collision.bin", "collision data");
		Path collisionPending = write(dir, "collision.bin.tmp", "pending collision data");
		Path unrelated = write(dir, "notes.txt", "user data");
		File recordings = new File(dir, "route-recordings");
		assertTrue(recordings.mkdir());
		Path recording = write(recordings, "request.properties", "recorded route");
		Path versions = write(dir, "versions.txt", "collision 2026-08-24\n sparse\t2026-08-25\nsparse invalid\nsparseExtra 2026-08-26\nfuture metadata retained verbatim\n");
		ChartPlotterMigration.files(dir);
		assertFalse(Files.exists(sparse));
		assertFalse(Files.exists(pending));
		assertEquals("collision data", Files.readString(collision));
		assertEquals("pending collision data", Files.readString(collisionPending));
		assertEquals("user data", Files.readString(unrelated));
		assertEquals("recorded route", Files.readString(recording));
		assertEquals(Arrays.asList("collision 2026-08-24", "sparseExtra 2026-08-26", "future metadata retained verbatim"), Files.readAllLines(versions));
		assertFalse(new File(dir, "versions.txt.tmp").exists());
		Files.setLastModifiedTime(versions, FileTime.fromMillis(1_700_000_000_000L));
		FileTime modified = Files.getLastModifiedTime(versions);
		ChartPlotterMigration.files(dir);
		assertEquals(modified, Files.getLastModifiedTime(versions));
	}
	@Test
	public void freshInstallsCreateNoFiles() throws Exception {
		File dir = new File(files.getRoot(), "absent");
		ChartPlotterMigration.files(dir);
		assertFalse(dir.exists());
	}
	@Test
	public void failedCacheDeletionCanBeRetriedWithoutRemovingDirectoriesRecursively() throws Exception {
		File dir = files.newFolder();
		File sparse = new File(dir, "sparse.bin");
		assertTrue(sparse.mkdir());
		Path child = write(sparse, "keep", "user data");
		Path versions = write(dir, "versions.txt", "collision 2026-08-24\nsparse 2026-08-25\n");
		assertThrows(IOException.class, () -> ChartPlotterMigration.files(dir));
		assertEquals("user data", Files.readString(child));
		assertTrue(Files.readString(versions).contains("sparse 2026-08-25"));
		Files.delete(child);
		Files.delete(sparse.toPath());
		ChartPlotterMigration.files(dir);
		assertEquals(List.of("collision 2026-08-24"), Files.readAllLines(versions));
	}
	@Test
	public void failedMetadataWritePreservesOriginalAndCanBeRetried() throws Exception {
		File dir = files.newFolder();
		String original = "collision 2026-08-24\nsparse 2026-08-25\n";
		Path versions = write(dir, "versions.txt", original);
		Path blocked = new File(dir, "versions.txt.tmp").toPath();
		Files.createDirectory(blocked);
		assertThrows(IOException.class, () -> ChartPlotterMigration.files(dir));
		assertEquals(original, Files.readString(versions));
		Files.delete(blocked);
		ChartPlotterMigration.files(dir);
		assertEquals(List.of("collision 2026-08-24"), Files.readAllLines(versions));
	}
	@Test
	public void unreadableMetadataIsNotOverwritten() throws Exception {
		File dir = files.newFolder();
		byte[] invalid = {(byte) 0xc3, (byte) 0x28};
		Path versions = new File(dir, "versions.txt").toPath();
		Files.write(versions, invalid);
		assertThrows(IOException.class, () -> ChartPlotterMigration.files(dir));
		assertArrayEquals(invalid, Files.readAllBytes(versions));
	}
	private static Path write(File dir, String name, String value) throws IOException {
		return Files.writeString(new File(dir, name).toPath(), value, StandardCharsets.UTF_8);
	}
}
