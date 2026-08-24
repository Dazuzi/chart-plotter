package com.chartplotter.collision;

import net.runelite.api.*;

import java.util.Arrays;

public final class ChartPlotterCollisionScan {
	private static final int EDGE = 8;
	private static final int[] EMPTY = new int[0];
	final int baseX;
	final int baseY;
	final int width;
	final int height;
	final int[] flags;
	final int[] objects;
	private ChartPlotterCollisionScan(int baseX, int baseY, int width, int height, int[] flags, int[] objects) {
		this.baseX = baseX;
		this.baseY = baseY;
		this.width = width;
		this.height = height;
		this.flags = flags;
		this.objects = objects;
	}
	static ChartPlotterCollisionScan capture(WorldView wv) {
		if (wv == null || wv.isInstance() || wv.getPlane() != 0) return null;
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps == null || maps.length == 0 || maps[0] == null) return null;
		int[][] flags = maps[0].getFlags();
		if (flags == null || flags.length <= EDGE * 2 || flags[0] == null || flags[0].length <= EDGE * 2) return null;
		int fullWidth = flags.length;
		int fullHeight = flags[0].length;
		int width = fullWidth - EDGE * 2;
		int height = fullHeight - EDGE * 2;
		int[] copy = new int[width * height];
		for (int x = 0; x < width; x++) {
			int[] row = flags[x + EDGE];
			if (row == null || row.length < fullHeight) return null;
			System.arraycopy(row, EDGE, copy, x * height, height);
		}
		return new ChartPlotterCollisionScan(wv.getBaseX() + EDGE, wv.getBaseY() + EDGE, width, height, copy, objects(wv));
	}
	private static int[] objects(WorldView wv) {
		Scene scene = wv.getScene();
		if (scene == null) return EMPTY;
		Tile[][][] tiles = scene.getExtendedTiles();
		int plane = wv.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) return EMPTY;
		Objects objects = new Objects();
		for (Tile[] row : tiles[plane]) {
			if (row == null) continue;
			for (Tile tile : row) {
				if (tile == null) continue;
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects == null) continue;
				for (GameObject object : gameObjects) {
					if (object == null || !ChartPlotterCollisionObjects.blocked(object.getId())) continue;
					objects.add(object);
				}
			}
		}
		return objects.array();
	}
	private static final class Objects {
		int[] data = EMPTY;
		int n;
		void add(GameObject object) {
			Point min = object.getSceneMinLocation();
			Point max = object.getSceneMaxLocation();
			if (min == null || max == null) return;
			int minX = min.getX() - ChartPlotterCollisionScan.EDGE;
			int minY = min.getY() - ChartPlotterCollisionScan.EDGE;
			int maxX = max.getX() - ChartPlotterCollisionScan.EDGE;
			int maxY = max.getY() - ChartPlotterCollisionScan.EDGE;
			for (int i = 0; i < n; i += 4) if (data[i] == minX && data[i + 1] == minY && data[i + 2] == maxX && data[i + 3] == maxY) return;
			if (n + 4 > data.length) data = Arrays.copyOf(data, Math.max(32, data.length << 1));
			data[n++] = minX;
			data[n++] = minY;
			data[n++] = maxX;
			data[n++] = maxY;
		}
		int[] array() {return n == data.length ? data : Arrays.copyOf(data, n);}
	}
}
