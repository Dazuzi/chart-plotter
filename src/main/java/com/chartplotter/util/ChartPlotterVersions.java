package com.chartplotter.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ChartPlotterVersions {
	private static final String FILE = "versions.txt";
	private ChartPlotterVersions() {}
	public static synchronized String read(File dir, String key) {
		return readAll(dir).get(key);
	}
	public static synchronized boolean write(File dir, String key, String version) {
		if (!valid(version)) return false;
		Map<String, String> data = readAll(dir);
		File tmp = new File(dir, FILE + ".tmp");
		try {Files.createDirectories(dir.toPath());} catch (Exception ignored) {return false;}
		try {
			try (BufferedWriter out = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
				data.put(key, version);
				for (Map.Entry<String, String> e : data.entrySet()) out.write(e.getKey() + " " + e.getValue() + "\n");
			}
			return ChartPlotterFiles.replace(tmp, file(dir));
		} catch (Exception ignored) {
			return false;
		} finally {
			try {Files.deleteIfExists(tmp.toPath());} catch (IOException ignored) {}
		}
	}
	public static synchronized void remove(File dir, String key) throws IOException {
		File tmp = new File(dir, FILE + ".tmp");
		try {
			List<String> data;
			try {data = Files.readAllLines(file(dir).toPath(), StandardCharsets.UTF_8);}
			catch (NoSuchFileException e) {return;}
			if (!data.removeIf(line -> key.equals(line.trim().split("\\s+", 2)[0]))) return;
			Files.write(tmp.toPath(), data, StandardCharsets.UTF_8);
			if (!ChartPlotterFiles.replace(tmp, file(dir))) throw new IOException("Unable to update dataset versions");
		} finally {Files.deleteIfExists(tmp.toPath());}
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
		try {LocalDate.parse(s); return true;} catch (DateTimeParseException e) {return false;}
	}
	private static File file(File dir) {return new File(dir, FILE);}
}
