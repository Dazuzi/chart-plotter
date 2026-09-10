package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionCodec;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class ChartPlotterCodecTest {
	@Rule public final TemporaryFolder files = new TemporaryFolder();
	@Test
	public void readsSeedVersionsWithoutDecodingPayloads() {
		assertEquals("2026-08-24", ChartPlotterCollisionCodec.readVersion(input("data 2026-08-24\n1 2 f\n"), () -> false));
	}
	@Test
	public void decodesCollisionMasks() {
		ChartPlotterCollisionCodec.Text collision = ChartPlotterCollisionCodec.readText(input("data 2026-08-24\n1 2 0000000000000003 0000000000000002\n"), () -> false);
		assertNotNull(collision);
		ChartPlotterCollisionData.Chunk chunk = collision.data.get(ChartPlotterCollisionData.key(1, 2));
		assertEquals(3, chunk.known);
		assertEquals(2, chunk.blocked);
	}
	@Test
	public void textDecodersHonorCancellation() {
		AtomicBoolean cancel = new AtomicBoolean(true);
		assertNull(ChartPlotterCollisionCodec.readVersion(input("data 2026-08-24\n1 2 0\n"), cancel::get));
		assertNull(ChartPlotterCollisionCodec.readText(input("data 2026-08-24\n1 2 0\n"), cancel::get));
		cancel.set(false);
		assertNotNull(ChartPlotterCollisionCodec.readText(input("data 2026-08-24\n1 2 0\n"), cancel::get));
	}
	@Test
	public void textCancellationDiscardsPartialDataAndClosesInput() {
		AtomicBoolean closed = new AtomicBoolean();
		AtomicInteger checks = new AtomicInteger();
		ByteArrayInputStream src = new ByteArrayInputStream("data 2026-08-24\n1 2 0\n2 3 0\n".getBytes(StandardCharsets.UTF_8)) {
			@Override
			public void close() {closed.set(true);}
		};
		assertNull(ChartPlotterCollisionCodec.readText(src, () -> checks.incrementAndGet() >= 4));
		assertEquals(4, checks.get());
		assertTrue(closed.get());
	}
	@Test
	public void collisionSnapshotsAreCompactAndImmutable() {
		Map<Long, ChartPlotterCollisionData.Chunk> source = new HashMap<>();
		ChartPlotterCollisionData.Chunk zero = new ChartPlotterCollisionData.Chunk(-1L, 1L);
		ChartPlotterCollisionData.Chunk negative = new ChartPlotterCollisionData.Chunk(-1L, 2L);
		source.put(ChartPlotterCollisionData.key(0, 0), zero);
		source.put(ChartPlotterCollisionData.key(-1, -1), negative);
		source.put(ChartPlotterCollisionData.key(5, 5), new ChartPlotterCollisionData.Chunk(0, -1L));
		ChartPlotterCollisionData data = new ChartPlotterCollisionData(source);
		source.clear();
		assertEquals(2, data.size());
		assertSame(zero, data.chunk(0, 0));
		assertSame(negative, data.chunk(-1, -1));
		assertNull(data.chunk(5, 5));
	}
	@Test
	public void collisionBinaryRoundTripsPrimitiveSnapshot() throws Exception {
		Map<Long, ChartPlotterCollisionData.Chunk> source = new HashMap<>();
		source.put(ChartPlotterCollisionData.key(0, 0), new ChartPlotterCollisionData.Chunk(3, 2));
		source.put(ChartPlotterCollisionData.key(65535, 65535), new ChartPlotterCollisionData.Chunk(-1L, 4));
		source.put(ChartPlotterCollisionData.key(5, 5), new ChartPlotterCollisionData.Chunk(0, -1L));
		File dir = files.newFolder();
		File file = new File(dir, "collision.bin");
		assertTrue(ChartPlotterCollisionCodec.write(dir, file, new ChartPlotterCollisionData(source), () -> false));
		Map<Long, ChartPlotterCollisionData.Chunk> decoded = ChartPlotterCollisionCodec.read(file, () -> false);
		assertEquals(2, decoded.size());
		assertEquals(3, decoded.get(ChartPlotterCollisionData.key(0, 0)).known);
		assertEquals(2, decoded.get(ChartPlotterCollisionData.key(0, 0)).blocked);
		assertEquals(-1L, decoded.get(ChartPlotterCollisionData.key(65535, 65535)).known);
		assertEquals(4, decoded.get(ChartPlotterCollisionData.key(65535, 65535)).blocked);
	}
	@Test
	public void binaryCancellationDiscardsPartialData() throws Exception {
		Map<Long, ChartPlotterCollisionData.Chunk> source = new HashMap<>();
		for (int i = 0; i < 2050; i++) source.put(ChartPlotterCollisionData.key(i, 0), new ChartPlotterCollisionData.Chunk(-1L, i));
		File dir = files.newFolder();
		File file = new File(dir, "collision.bin");
		assertTrue(ChartPlotterCollisionCodec.write(dir, file, new ChartPlotterCollisionData(source), () -> false));
		for (int limit : new int[]{1, 3, 5}) {
			AtomicInteger checks = new AtomicInteger();
			assertTrue(ChartPlotterCollisionCodec.read(file, () -> checks.incrementAndGet() >= limit).isEmpty());
			assertEquals(limit, checks.get());
		}
		assertEquals(source.size(), ChartPlotterCollisionCodec.read(file, () -> false).size());
	}
	@Test
	public void cancelledWritesPreserveSavedDataAndRemoveTemporaryFiles() throws Exception {
		Map<Long, ChartPlotterCollisionData.Chunk> source = new HashMap<>();
		source.put(ChartPlotterCollisionData.key(0, 0), new ChartPlotterCollisionData.Chunk(3, 2));
		File dir = files.newFolder();
		File file = new File(dir, "collision.bin");
		assertTrue(ChartPlotterCollisionCodec.write(dir, file, new ChartPlotterCollisionData(source), () -> false));
		byte[] saved = Files.readAllBytes(file.toPath());
		for (int i = 0; i < 100; i++) source.put(ChartPlotterCollisionData.key(i, 0), new ChartPlotterCollisionData.Chunk(-1L, i));
		ChartPlotterCollisionData next = new ChartPlotterCollisionData(source);
		for (int limit : new int[]{1, next.capacity() / 2 + 1, next.capacity() + 2}) {
			AtomicInteger checks = new AtomicInteger();
			assertFalse(ChartPlotterCollisionCodec.write(dir, file, next, () -> checks.incrementAndGet() >= limit));
			assertEquals(limit, checks.get());
			assertArrayEquals(saved, Files.readAllBytes(file.toPath()));
			assertFalse(new File(dir, "collision.bin.tmp").exists());
		}
		assertTrue(ChartPlotterCollisionCodec.write(dir, file, next, () -> false));
		assertEquals(source.size(), ChartPlotterCollisionCodec.read(file, () -> false).size());
	}
	private static ByteArrayInputStream input(String value) {return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
}
