package com.chartplotter.collision;

import com.chartplotter.collision.ChartPlotterCollisionData.Chunk;
import com.chartplotter.util.ChartPlotterFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
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
			if (n < 0) return data;
			for (int i = 0; i < n; i++) {
				if ((i & 1023) == 0 && Thread.currentThread().isInterrupted()) return new HashMap<>();
				int cx = in.readUnsignedShort();
				int cy = in.readUnsignedShort();
				long mask = in.readLong();
				long blocked = in.readLong();
				if (mask != 0) data.put(ChartPlotterCollisionData.key(cx, cy), new Chunk(mask, blocked & mask));
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
				if (Thread.currentThread().isInterrupted()) return null;
				StringTokenizer p = new StringTokenizer(s);
				int n = p.countTokens();
				if (n == 2 && "data".equals(p.nextToken())) {
					version = p.nextToken();
					continue;
				}
				if (n != 3 && n != 4) continue;
				int cx = Integer.parseInt(p.nextToken());
				int cy = Integer.parseInt(p.nextToken());
				if (cx < 0 || cx > USHORT || cy < 0 || cy > USHORT) continue;
				long known = n == 3 ? -1L : Long.parseUnsignedLong(p.nextToken(), 16);
				long blocked = Long.parseUnsignedLong(p.nextToken(), 16);
				if (known == 0L) continue;
				data.put(ChartPlotterCollisionData.key(cx, cy), new Chunk(known, blocked & known));
			}
		} catch (Exception ignored) {
			return null;
		}
		return version == null ? null : new Text(data, version);
	}
	public static String readVersion(InputStream src) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8))) {
			String s;
			while ((s = in.readLine()) != null) {
				StringTokenizer p = new StringTokenizer(s);
				if (p.countTokens() == 2 && "data".equals(p.nextToken())) return p.nextToken();
			}
		} catch (Exception ignored) {
		}
		return null;
	}
	public static boolean write(File dir, File file, ChartPlotterCollisionData data) {
		File tmp = new File(dir, "collision.bin.tmp");
		try {Files.createDirectories(dir.toPath());} catch (Exception ignored) {return false;}
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp))))) {
			out.writeByte(VERSION);
			out.writeInt(data.size());
			for (int i = 0; i < data.capacity(); i++) {
				if (Thread.currentThread().isInterrupted()) return false;
				Chunk c = data.chunkAt(i);
				if (c == null) continue;
				long key = data.keyAt(i);
				int cx = (int) (key >> 32);
				int cy = (int) key;
				if (cx < 0 || cx > USHORT || cy < 0 || cy > USHORT) return false;
				out.writeShort(cx);
				out.writeShort(cy);
				out.writeLong(c.known);
				out.writeLong(c.blocked);
			}
		} catch (Exception ignored) {
			return false;
		}
		if (Thread.currentThread().isInterrupted()) return false;
		return ChartPlotterFiles.replace(tmp, file);
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
