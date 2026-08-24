package com.chartplotter.route;
import com.chartplotter.collision.ChartPlotterCollisionCodec;
import com.chartplotter.collision.ChartPlotterCollisionData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
public class ChartPlotterCodecTest {
	@Rule public final TemporaryFolder files = new TemporaryFolder();
	@Test
	public void readsSeedVersionsWithoutDecodingPayloads() {
		assertEquals("2026-08-24", ChartPlotterCollisionCodec.readVersion(input("data 2026-08-24\n1 2 f\n")));
		assertEquals("2026-08-24", ChartPlotterSparseCodec.readVersion(input("data 2026-08-24\n1 2\n")));
	}
	@Test
	public void decodesCollisionMasksAndSparseNodes() {
		ChartPlotterCollisionCodec.Text collision = ChartPlotterCollisionCodec.readText(input("data 2026-08-24\n1 2 0000000000000003 0000000000000002\n"));
		assertNotNull(collision);
		ChartPlotterCollisionData.Chunk chunk = collision.data.get(ChartPlotterCollisionData.key(1, 2));
		assertEquals(3, chunk.known);
		assertEquals(2, chunk.blocked);
		ChartPlotterSparseCodec.Text sparse = ChartPlotterSparseCodec.readText(input("data 2026-08-24\n10 20\n30 40\n"));
		assertNotNull(sparse);
		assertArrayEquals(new int[]{10, 30}, sparse.nodes.x);
		assertArrayEquals(new int[]{20, 40}, sparse.nodes.y);
	}
	@Test
	public void textDecodersHonorCancellation() {
		try {
			Thread.currentThread().interrupt();
			assertNull(ChartPlotterCollisionCodec.readText(input("data 2026-08-24\n1 2 0\n")));
			assertNull(ChartPlotterSparseCodec.readText(input("data 2026-08-24\n1 2\n")));
		} finally {
			assertTrue(Thread.interrupted());
		}
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
		assertTrue(ChartPlotterCollisionCodec.write(dir, file, new ChartPlotterCollisionData(source)));
		Map<Long, ChartPlotterCollisionData.Chunk> decoded = ChartPlotterCollisionCodec.read(file);
		assertEquals(2, decoded.size());
		assertEquals(3, decoded.get(ChartPlotterCollisionData.key(0, 0)).known);
		assertEquals(2, decoded.get(ChartPlotterCollisionData.key(0, 0)).blocked);
		assertEquals(-1L, decoded.get(ChartPlotterCollisionData.key(65535, 65535)).known);
		assertEquals(4, decoded.get(ChartPlotterCollisionData.key(65535, 65535)).blocked);
	}
	private static ByteArrayInputStream input(String value) {return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
}
