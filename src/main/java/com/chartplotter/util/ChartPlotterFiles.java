package com.chartplotter.util;
import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
public final class ChartPlotterFiles {
	private ChartPlotterFiles() {}
	public static boolean replace(File tmp, File dst) {
		try {
			Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (AtomicMoveNotSupportedException e) {
			try {
				Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
				return true;
			} catch (Exception ignored) {
				return false;
			}
		} catch (Exception ignored) {
			return false;
		}
	}
}
