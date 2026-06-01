package com.chartplotter.collision;
import java.util.Arrays;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
public final class ChartPlotterCollisionScan {
	private static final int EDGE = 8;
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
		int width = flags.length;
		int height = flags[0].length;
		int[] copy = new int[width * height];
		for (int x = 0; x < width; x++) {
			int[] row = flags[x];
			if (row == null || row.length < height) return null;
			System.arraycopy(row, 0, copy, x * height, height);
		}
		return new ChartPlotterCollisionScan(wv.getBaseX(), wv.getBaseY(), width, height, copy, objects(wv));
	}
	private static int[] objects(WorldView wv) {
		Scene scene = wv.getScene();
		if (scene == null) return new int[0];
		Tile[][][] tiles = scene.getExtendedTiles();
		int plane = wv.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) return new int[0];
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
		int[] data = new int[32];
		int n;
		void add(GameObject object) {
			Point min = object.getSceneMinLocation();
			Point max = object.getSceneMaxLocation();
			if (min == null || max == null) return;
			if (n + 4 > data.length) data = Arrays.copyOf(data, data.length << 1);
			data[n++] = min.getX();
			data[n++] = min.getY();
			data[n++] = max.getX();
			data[n++] = max.getY();
		}
		int[] array() {return n == data.length ? data : Arrays.copyOf(data, n);}
	}
}
