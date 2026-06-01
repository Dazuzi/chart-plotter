package com.chartplotter.collision;
import java.lang.reflect.Field;
public final class ChartPlotterCollisionObjects {
	private static final String[] BLOCKED = {
		"SAILING_FETID_POOL",
		"SAILING_FETID_POOL_2X2",
		"SAILING_FETID_POOL_3X3A",
		"SAILING_FETID_POOL_3X3B",
		"SAILING_FETID_POOL_3X3C"
	};
	private static final int[] FALLBACK = {60359, 60360, 60361, 60362, 60363};
	private static final int[] BLOCKED_IDS = ids();
	private ChartPlotterCollisionObjects() {}
	static boolean blocked(int id) {
		for (int block : BLOCKED_IDS) if (block == id) return true;
		return false;
	}
	private static int[] ids() {
		int[] ids = new int[ChartPlotterCollisionObjects.BLOCKED.length];
		for (int i = 0; i < ChartPlotterCollisionObjects.BLOCKED.length; i++) {
			int id = id(ChartPlotterCollisionObjects.BLOCKED[i]);
			ids[i] = id >= 0 ? id : FALLBACK[i];
		}
		return ids;
	}
	private static int id(String name) {
		int id = id("net.runelite.api.gameval.ObjectID1", name);
		if (id >= 0) return id;
		id = id("net.runelite.api.gameval.ObjectID", name);
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
