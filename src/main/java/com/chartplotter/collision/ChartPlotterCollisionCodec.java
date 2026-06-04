package com.chartplotter.collision;
import com.chartplotter.collision.ChartPlotterCollisionData.Chunk;
import com.chartplotter.util.ChartPlotterFiles;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
public final class ChartPlotterCollisionCodec {
	private static final byte VERSION = 1;
	private static final int USHORT = 0xffff;
	private ChartPlotterCollisionCodec() {}
	public static Map<Long, Chunk> read(File file) {
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
	public static Text readText(InputStream src) {
		Map<Long, Chunk> data = new HashMap<>();
		String version = null;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8))) {
			String s;
			while ((s = in.readLine()) != null) {
				String[] p = s.trim().split("\\s+");
				if (p.length == 2 && "data".equals(p[0])) {
					version = p[1];
					continue;
				}
				if (p.length != 3 && p.length != 4) continue;
				int cx = Integer.parseInt(p[0]);
				int cy = Integer.parseInt(p[1]);
				if (cx < 0 || cx > USHORT || cy < 0 || cy > USHORT) continue;
				long known = p.length == 3 ? -1L : Long.parseUnsignedLong(p[2], 16);
				long blocked = Long.parseUnsignedLong(p[p.length - 1], 16);
				if (known == 0L) continue;
				data.put(ChartPlotterCollisionData.key(cx, cy), new Chunk(known, blocked & known));
			}
		} catch (Exception ignored) {
			return null;
		}
		return version == null ? null : new Text(data, version);
	}
	public static boolean write(File dir, File file, Map<Long, Chunk> data) {
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
	public static final class Text {
		public final Map<Long, Chunk> data;
		public final String version;
		private Text(Map<Long, Chunk> data, String version) {
			this.data = data;
			this.version = version;
		}
	}
}
