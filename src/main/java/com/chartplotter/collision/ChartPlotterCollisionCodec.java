package com.chartplotter.collision;

import com.chartplotter.collision.ChartPlotterCollisionData.Chunk;
import com.chartplotter.util.ChartPlotterFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class ChartPlotterCollisionCodec {
	private static final byte VERSION = 1;
	private static final int USHORT = 0xffff;
	private ChartPlotterCollisionCodec() {}
	public static Map<Long, Chunk> read(File file, BooleanSupplier cancel) {
		Map<Long, Chunk> data = new HashMap<>();
		if (cancel.getAsBoolean() || !file.isFile()) return data;
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
			if (in.readByte() != VERSION) return data;
			int n = in.readInt();
			if (n < 0) return data;
			for (int i = 0; i < n; i++) {
				if ((i & 1023) == 0 && cancel.getAsBoolean()) return new HashMap<>();
				int cx = in.readUnsignedShort();
				int cy = in.readUnsignedShort();
				long mask = in.readLong();
				long blocked = in.readLong();
				if (mask != 0 && data.put(ChartPlotterCollisionData.key(cx, cy), new Chunk(mask, blocked & mask)) != null) return new HashMap<>();
			}
			if (in.read() != -1) return new HashMap<>();
		} catch (Exception ignored) {
			return new HashMap<>();
		}
		return cancel.getAsBoolean() ? new HashMap<>() : data;
	}
	public static Text readText(InputStream src, BooleanSupplier cancel) {
		Map<Long, Chunk> data = new HashMap<>();
		String version = null;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8))) {
			if (cancel.getAsBoolean()) return null;
			String s;
			while ((s = in.readLine()) != null) {
				if (cancel.getAsBoolean()) return null;
				StringTokenizer p = new StringTokenizer(s);
				int n = p.countTokens();
				if (n == 0) continue;
				if (version == null) {
					if (n != 2 || !"data".equals(p.nextToken())) return null;
					version = p.nextToken();
					continue;
				}
				if (n != 3 && n != 4) return null;
				int cx = Integer.parseInt(p.nextToken());
				int cy = Integer.parseInt(p.nextToken());
				if (cx < 0 || cx > USHORT || cy < 0 || cy > USHORT) return null;
				long known = n == 3 ? -1L : Long.parseUnsignedLong(p.nextToken(), 16);
				long blocked = Long.parseUnsignedLong(p.nextToken(), 16);
				if (known == 0L) continue;
				if (data.put(ChartPlotterCollisionData.key(cx, cy), new Chunk(known, blocked & known)) != null) return null;
			}
		} catch (Exception ignored) {
			return null;
		}
		return version == null || cancel.getAsBoolean() ? null : new Text(data, version);
	}
	public static String readVersion(InputStream src, BooleanSupplier cancel) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8))) {
			if (cancel.getAsBoolean()) return null;
			String s;
			while ((s = in.readLine()) != null) {
				if (cancel.getAsBoolean()) return null;
				StringTokenizer p = new StringTokenizer(s);
				if (p.countTokens() == 2 && "data".equals(p.nextToken())) return p.nextToken();
			}
		} catch (Exception ignored) {
		}
		return null;
	}
	public static boolean write(File dir, File file, ChartPlotterCollisionData data, BooleanSupplier cancel) {
		if (cancel.getAsBoolean()) return false;
		File tmp = new File(dir, "collision.bin.tmp");
		try {
			Files.createDirectories(dir.toPath());
			try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp))))) {
				out.writeByte(VERSION);
				out.writeInt(data.size());
				for (int i = 0; i < data.capacity(); i++) {
					if (cancel.getAsBoolean()) return false;
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
			}
			return !cancel.getAsBoolean() && ChartPlotterFiles.replace(tmp, file);
		} catch (Exception ignored) {
			return false;
		} finally {
			try {Files.deleteIfExists(tmp.toPath());} catch (IOException ignored) {}
		}
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
