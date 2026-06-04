package com.chartplotter.util;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
public final class ChartPlotterVersions {
	private static final String FILE = "versions.txt";
	private ChartPlotterVersions() {}
	public static synchronized String read(File dir, String key) {
		return readAll(dir).get(key);
	}
	public static synchronized void write(File dir, String key, String version) {
		if (!valid(version)) return;
		Map<String, String> data = readAll(dir);
		File tmp = new File(dir, FILE + ".tmp");
		try {Files.createDirectories(dir.toPath());} catch (Exception ignored) {return;}
		try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
			boolean done = false;
			for (Map.Entry<String, String> e : data.entrySet()) {
				if (key.equals(e.getKey())) {
					out.write(key + " " + version + "\n");
					done = true;
				} else out.write(e.getKey() + " " + e.getValue() + "\n");
			}
			if (!done) out.write(key + " " + version + "\n");
		} catch (Exception ignored) {
			return;
		}
		ChartPlotterFiles.replace(tmp, file(dir));
	}
	public static boolean newer(String src, String dst) {
		return valid(src) && (!valid(dst) || src.compareTo(dst) > 0);
	}
	private static Map<String, String> readAll(File dir) {
		Map<String, String> data = new TreeMap<>();
		File file = file(dir);
		if (!file.isFile()) return data;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String s;
			while ((s = in.readLine()) != null) {
				String[] p = s.trim().split("\\s+");
				if (p.length == 2 && valid(p[1])) data.put(p[0], p[1]);
			}
		} catch (Exception ignored) {
		}
		return data;
	}
	private static boolean valid(String s) {
		if (s == null || s.length() != 10) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (i == 4 || i == 7) {
				if (c != '-') return false;
			} else if (c < '0' || c > '9') return false;
		}
		return true;
	}
	private static File file(File dir) {return new File(dir, FILE);}
}
