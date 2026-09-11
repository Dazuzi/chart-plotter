package com.chartplotter.collision;

import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.WorldView;

import java.util.Arrays;
import java.util.Collection;

public final class ChartPlotterCollisionScan {
	static final int EDGE = 8;
	static boolean ready(WorldView view) {
		if (view == null || view.isInstance() || view.getPlane() != 0) return false;
		CollisionData[] maps = view.getCollisionMaps();
		if (maps == null || maps.length == 0 || maps[0] == null) return false;
		int[][] flags = maps[0].getFlags();
		return flags != null && flags.length > EDGE * 2 && flags[0] != null && flags[0].length > EDGE * 2;
	}
	final int baseX;
	final int baseY;
	final int width;
	final int height;
	final int[] flags;
	final int[] objects;
	ChartPlotterCollisionScan(int baseX, int baseY, int width, int height, int[] flags, int[] objects) {
		this.baseX = baseX;
		this.baseY = baseY;
		this.width = width;
		this.height = height;
		this.flags = flags;
		this.objects = objects;
	}
	static ChartPlotterCollisionScan capture(WorldView wv, int minX, int minY, int maxX, int maxY, Collection<GameObject> blockers) {
		if (wv == null || wv.isInstance() || wv.getPlane() != 0) return null;
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps == null || maps.length == 0 || maps[0] == null) return null;
		int[][] flags = maps[0].getFlags();
		if (flags == null || flags.length == 0 || flags[0] == null) return null;
		minX = Math.max(EDGE, minX);
		minY = Math.max(EDGE, minY);
		maxX = Math.min(flags.length - EDGE, maxX);
		maxY = Math.min(flags[0].length - EDGE, maxY);
		int width = maxX - minX;
		int height = maxY - minY;
		if (width <= 0 || height <= 0) return null;
		int[] copy = new int[width * height];
		for (int x = minX; x < maxX; x++) for (int y = minY; y < maxY; y++) copy[(x - minX) * height + y - minY] = flags[x] == null || y >= flags[x].length ? ChartPlotterCollisionData.VOID : flags[x][y];
		int[] objects = new int[blockers.size() * 4];
		int n = 0;
		for (GameObject object : blockers) {
			Point min = object.getSceneMinLocation();
			Point max = object.getSceneMaxLocation();
			if (min == null || max == null || max.getX() < minX || max.getY() < minY || min.getX() >= maxX || min.getY() >= maxY) continue;
			objects[n++] = Math.max(minX, min.getX()) - minX;
			objects[n++] = Math.max(minY, min.getY()) - minY;
			objects[n++] = Math.min(maxX - 1, max.getX()) - minX;
			objects[n++] = Math.min(maxY - 1, max.getY()) - minY;
		}
		return new ChartPlotterCollisionScan(wv.getBaseX() + minX, wv.getBaseY() + minY, width, height, copy, Arrays.copyOf(objects, n));
	}
}
