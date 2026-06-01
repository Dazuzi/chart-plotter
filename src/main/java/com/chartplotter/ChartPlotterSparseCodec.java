package com.chartplotter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
final class ChartPlotterSparseCodec {
	private static final byte VERSION = 1;
	private static final int USHORT = 0xffff;
	private ChartPlotterSparseCodec() {}
	static ChartPlotterSparseNodes.Snapshot read(File file) {
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
	static boolean write(File dir, File file, ChartPlotterSparseNodes.Snapshot nodes) {
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
}
