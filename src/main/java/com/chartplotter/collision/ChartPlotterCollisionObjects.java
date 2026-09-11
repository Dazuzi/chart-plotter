package com.chartplotter.collision;

import net.runelite.api.*;
import net.runelite.api.gameval.ObjectID;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class ChartPlotterCollisionObjects {
	private static final int[] BLOCKED_IDS = {
		ObjectID.SAILING_FETID_POOL,
		ObjectID.SAILING_FETID_POOL_2X2,
		ObjectID.SAILING_FETID_POOL_3X3A,
		ObjectID.SAILING_FETID_POOL_3X3B,
		ObjectID.SAILING_FETID_POOL_3X3C
	};
	final Set<GameObject> blockers = Collections.newSetFromMap(new IdentityHashMap<>());
	void seed(WorldView view) {
		blockers.clear();
		Scene scene = view.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getExtendedTiles();
		int plane = view.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) return;
		for (Tile[] row : tiles[plane]) {
			if (row == null) continue;
			for (Tile tile : row) {
				if (tile == null || tile.getGameObjects() == null) continue;
				for (GameObject object : tile.getGameObjects()) changed(object, true);
			}
		}
	}
	void changed(TileObject object, boolean spawned) {
		if (!(object instanceof GameObject) || !blocked(object.getId())) return;
		if (spawned) blockers.add((GameObject) object);
		else blockers.remove(object);
	}
	private static boolean blocked(int id) {
		for (int block : BLOCKED_IDS) if (block == id) return true;
		return false;
	}
}
