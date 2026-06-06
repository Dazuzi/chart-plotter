package com.chartplotter.collision;
import net.runelite.api.gameval.ObjectID;
public final class ChartPlotterCollisionObjects {
	private static final int[] BLOCKED_IDS = {
		ObjectID.SAILING_FETID_POOL,
		ObjectID.SAILING_FETID_POOL_2X2,
		ObjectID.SAILING_FETID_POOL_3X3A,
		ObjectID.SAILING_FETID_POOL_3X3B,
		ObjectID.SAILING_FETID_POOL_3X3C
	};
	private ChartPlotterCollisionObjects() {}
	static boolean blocked(int id) {
		for (int block : BLOCKED_IDS) if (block == id) return true;
		return false;
	}
}
