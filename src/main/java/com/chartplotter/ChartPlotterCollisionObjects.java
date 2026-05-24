package com.chartplotter;
import java.lang.reflect.Field;
import java.util.Arrays;
final class ChartPlotterCollisionObjects {
	private static final String[] BLOCKED = {
		"SAILING_FETID_POOL",
		"SAILING_FETID_POOL_2X2",
		"SAILING_FETID_POOL_3X3A",
		"SAILING_FETID_POOL_3X3B",
		"SAILING_FETID_POOL_3X3C"
	};
	private static final int[] BLOCKED_IDS = ids();
	private ChartPlotterCollisionObjects() {}
	static boolean blocked(int id) {
		for (int block : BLOCKED_IDS) if (block == id) return true;
		return false;
	}
	private static int[] ids() {
		int[] ids = new int[ChartPlotterCollisionObjects.BLOCKED.length];
		int n = 0;
		for (String name : ChartPlotterCollisionObjects.BLOCKED) {
			int id = id(name);
			if (id >= 0) ids[n++] = id;
		}
		return Arrays.copyOf(ids, n);
	}
	private static int id(String name) {
		int id = id("net.runelite.api.gameval.ObjectID", name);
		return id >= 0 ? id : id("net.runelite.api.ObjectID", name);
	}
	private static int id(String type, String name) {
		try {
			Field f = Class.forName(type).getField(name);
			return f.getInt(null);
		} catch (Exception ignored) {
			return -1;
		}
	}
}
