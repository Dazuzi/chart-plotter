package com.chartplotter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
@Singleton
final class ChartPlotterSparseNodes {
	private static final byte VERSION = 1;
	private static final int USHORT = 0xffff;
	private final File dir = new File(RuneLite.RUNELITE_DIR, "chart-plotter");
	private int[] x = new int[64];
	private int[] y = new int[64];
	private int n;
	private boolean loaded;
	synchronized Snapshot snapshot() {
		load();
		return new Snapshot(Arrays.copyOf(x, n), Arrays.copyOf(y, n), n);
	}
	synchronized int nodeAt(int wx, int wy, int r) {
		load();
		for (int i = 0; i < n; i++) {
			if (dist(wx, wy, x[i], y[i]) <= r) return i;
		}
		return -1;
	}
	synchronized void add(int wx, int wy) {
		load();
		if (n == x.length) grow();
		x[n] = wx;
		y[n] = wy;
		n++;
		flushQuiet();
	}
	synchronized void remove(int i) {
		load();
		if (i < 0 || i >= n) return;
		int m = n - i - 1;
		if (m > 0) {
			System.arraycopy(x, i + 1, x, i, m);
			System.arraycopy(y, i + 1, y, i, m);
		}
		n--;
		flushQuiet();
	}
	synchronized String text(String op) {
		load();
		StringBuilder s = new StringBuilder("chart-plotter nodes ");
		s.append(op).append(" [");
		for (int i = 0; i < n; i++) {
			if (i > 0) s.append(' ');
			s.append(x[i]).append(',').append(y[i]);
		}
		s.append(']');
		return s.toString();
	}
	private void load() {
		if (loaded) return;
		if (!read(file())) defaults();
		loaded = true;
	}
	private void defaults() {
		try (InputStream in = ChartPlotterSparseNodes.class.getResourceAsStream("/com/chartplotter/sparse-nodes.txt")) {
			if (in == null) return;
			byte[] b = in.readAllBytes();
			parse(new String(b, StandardCharsets.UTF_8));
		} catch (Exception ignored) {
		}
	}
	private void parse(String s) {
		int[] v = new int[1024];
		int c = 0;
		int p = 0;
		while (p < s.length()) {
			while (p < s.length() && !digit(s.charAt(p))) p++;
			if (p >= s.length()) break;
			int q = p;
			while (q < s.length() && digit(s.charAt(q))) q++;
			if (c == v.length) v = Arrays.copyOf(v, v.length << 1);
			v[c++] = Integer.parseInt(s.substring(p, q));
			p = q;
		}
		n = 0;
		ensure(c / 2);
		for (int i = 1; i < c; i += 2) {
			x[n] = v[i - 1];
			y[n] = v[i];
			n++;
		}
	}
	private boolean read(File file) {
		if (!file.isFile()) return false;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			if (in.readByte() != VERSION) return false;
			int c = in.readInt();
			if (c < 0) return false;
			n = 0;
			ensure(c);
			for (int i = 0; i < c; i++) {
				x[n] = in.readUnsignedShort();
				y[n] = in.readUnsignedShort();
				n++;
			}
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}
	private void flushQuiet() {
		try {
			write();
		} catch (Exception ignored) {
		}
	}
	private boolean write() {
		File tmp = new File(dir, "sparse.bin.tmp");
		try {Files.createDirectories(dir.toPath());} catch (Exception ignored) {return false;}
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
			out.writeByte(VERSION);
			out.writeInt(n);
			for (int i = 0; i < n; i++) {
				if (x[i] < 0 || x[i] > USHORT || y[i] < 0 || y[i] > USHORT) return false;
				out.writeShort(x[i]);
				out.writeShort(y[i]);
			}
		} catch (Exception ignored) {
			return false;
		}
		try {
			Files.move(tmp.toPath(), file().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			try {
				Files.move(tmp.toPath(), file().toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ignored) {
				return false;
			}
		} catch (Exception ignored) {
			return false;
		}
		return true;
	}
	private File file() {return new File(dir, "sparse.bin");}
	private void ensure(int c) {
		while (x.length < c) grow();
	}
	private void grow() {
		x = Arrays.copyOf(x, x.length << 1);
		y = Arrays.copyOf(y, y.length << 1);
	}
	private static boolean digit(char c) {return c >= '0' && c <= '9';}
	private static int dist(int ax, int ay, int bx, int by) {return Math.max(Math.abs(ax - bx), Math.abs(ay - by));}
	static final class Snapshot {
		final int[] x;
		final int[] y;
		final int n;
		private Snapshot(int[] x, int[] y, int n) {
			this.x = x;
			this.y = y;
			this.n = n;
		}
	}
}
