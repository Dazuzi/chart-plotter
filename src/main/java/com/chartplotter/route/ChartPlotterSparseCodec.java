package com.chartplotter.route;

import com.chartplotter.util.ChartPlotterFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class ChartPlotterSparseCodec {
	private static final byte VERSION = 1;
	private static final int USHORT = 0xffff;
	private ChartPlotterSparseCodec() {}
	public static ChartPlotterSparseNodes.Snapshot read(File file) {
		if (!file.isFile()) return null;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			if (in.readByte() != VERSION) return null;
			int n = in.readInt();
			if (n < 0) return null;
			int[] x = new int[n];
			int[] y = new int[n];
			for (int i = 0; i < n; i++) {
				x[i] = in.readUnsignedShort();
				y[i] = in.readUnsignedShort();
			}
			return new ChartPlotterSparseNodes.Snapshot(x, y);
		} catch (Exception ignored) {
			return null;
		}
	}
	public static Text readText(InputStream src) {
		int[] x = new int[1024];
		int[] y = new int[1024];
		int n = 0;
		String version = null;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8))) {
			String s;
			while ((s = in.readLine()) != null) {
				String[] p = s.trim().split("\\s+");
				if (p.length == 1 && p[0].isEmpty()) continue;
				if (p.length == 2 && "data".equals(p[0])) {
					version = p[1];
					continue;
				}
				if (p.length != 2) continue;
				int wx = Integer.parseInt(p[0]);
				int wy = Integer.parseInt(p[1]);
				if (wx < 0 || wx > USHORT || wy < 0 || wy > USHORT) continue;
				if (n == x.length) {
					x = Arrays.copyOf(x, x.length << 1);
					y = Arrays.copyOf(y, y.length << 1);
				}
				x[n] = wx;
				y[n++] = wy;
			}
			if (version == null) return null;
			return new Text(new ChartPlotterSparseNodes.Snapshot(Arrays.copyOf(x, n), Arrays.copyOf(y, n)), version);
		} catch (Exception ignored) {
			return null;
		}
	}
	public static boolean write(File dir, File file, ChartPlotterSparseNodes.Snapshot nodes) {
		File tmp = new File(dir, "sparse.bin.tmp");
		try {Files.createDirectories(dir.toPath());} catch (Exception ignored) {return false;}
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
			out.writeByte(VERSION);
			out.writeInt(nodes.x.length);
			for (int i = 0; i < nodes.x.length; i++) {
				if (nodes.x[i] < 0 || nodes.x[i] > USHORT || nodes.y[i] < 0 || nodes.y[i] > USHORT) return false;
				out.writeShort(nodes.x[i]);
				out.writeShort(nodes.y[i]);
			}
		} catch (Exception ignored) {
			return false;
		}
		return ChartPlotterFiles.replace(tmp, file);
	}
	public static final class Text {
		public final ChartPlotterSparseNodes.Snapshot nodes;
		public final String version;
		private Text(ChartPlotterSparseNodes.Snapshot nodes, String version) {
			this.nodes = nodes;
			this.version = version;
		}
	}
}
