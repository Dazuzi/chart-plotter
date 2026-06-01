package com.chartplotter;
import com.chartplotter.ChartPlotterCollisionData.Chunk;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
final class ChartPlotterCollisionCodec {
	private static final byte VERSION = 1;
	private static final int USHORT = 0xffff;
	private ChartPlotterCollisionCodec() {}
	static Map<Long, Chunk> read(File file) {
		Map<Long, Chunk> data = new HashMap<>();
		if (!file.isFile()) return data;
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
			if (in.readByte() != VERSION) return data;
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				int cx = in.readUnsignedShort();
				int cy = in.readUnsignedShort();
				long mask = in.readLong();
				long blocked = in.readLong();
				data.put(ChartPlotterCollisionData.key(cx, cy), new Chunk(mask, blocked & mask));
			}
		} catch (Exception ignored) {
		}
		return data;
	}
	static boolean write(File dir, File file, Map<Long, Chunk> data) {
		File tmp = new File(dir, "collision.bin.tmp");
		try {Files.createDirectories(dir.toPath());} catch (Exception ignored) {return false;}
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp))))) {
			out.writeByte(VERSION);
			out.writeInt(count(data));
			for (Map.Entry<Long, Chunk> e : data.entrySet()) {
				Chunk c = e.getValue();
				if (c.empty()) continue;
				int cx = (int) (e.getKey() >> 32);
				int cy = (int) (long) e.getKey();
				if (cx < 0 || cx > USHORT || cy < 0 || cy > USHORT) return false;
				out.writeShort(cx);
				out.writeShort(cy);
				out.writeLong(c.known);
				out.writeLong(c.blocked);
			}
		} catch (Exception ignored) {
			return false;
		}
		return ChartPlotterFiles.replace(tmp, file);
	}
	private static int count(Map<Long, Chunk> data) {
		int n = 0;
		for (Chunk c : data.values()) if (!c.empty()) n++;
		return n;
	}
}
