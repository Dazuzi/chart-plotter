package com.chartplotter.runtime;

import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;

import javax.inject.Singleton;

@Singleton
public final class ChartPlotterScene {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private Slot cache;
	public Area area(WorldView wv) {
		Tile[][] tiles = tiles(wv);
		Slot s = cache;
		if (s != null && s.same(wv, tiles)) return s.area;
		Area area = area(wv, tiles);
		cache = new Slot(wv, tiles, area);
		return area;
	}
	static long key(Area a) {
		if (a == null) return 0;
		return a.key;
	}
	private static long key(int baseX, int baseY, int offX, int offY, int minX, int minY, int maxX, int maxY, int n, boolean[] chunks) {
		long h = 1125899906842597L;
		h = h * 31 + baseX;
		h = h * 31 + baseY;
		h = h * 31 + offX;
		h = h * 31 + offY;
		h = h * 31 + minX;
		h = h * 31 + minY;
		h = h * 31 + maxX;
		h = h * 31 + maxY;
		h = h * 31 + n;
		for (boolean b : chunks) h = h * 31 + (b ? 1 : 0);
		return h;
	}
	private static Area area(WorldView wv, Tile[][] tiles) {
		if (tiles == null) return null;
		int offX = wv.isTopLevel() ? (tiles.length - wv.getSizeX()) / 2 : 0;
		int offY = 0;
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int x = 0; x < tiles.length; x++) {
			Tile[] row = tiles[x];
			if (row == null) continue;
			if (x == 0) offY = wv.isTopLevel() ? (row.length - wv.getSizeY()) / 2 : 0;
			for (int y = 0; y < row.length; y++) {
				if (row[y] == null) continue;
				int sx = x - offX;
				int sy = y - offY;
				if (sx < minX) minX = sx;
				if (sy < minY) minY = sy;
				if (sx + 1 > maxX) maxX = sx + 1;
				if (sy + 1 > maxY) maxY = sy + 1;
			}
		}
		if (minX == Integer.MAX_VALUE) return null;
		int minCX = (wv.getBaseX() + minX) >> 3;
		int minCY = (wv.getBaseY() + minY) >> 3;
		int maxCX = (wv.getBaseX() + maxX - 1) >> 3;
		int maxCY = (wv.getBaseY() + maxY - 1) >> 3;
		int cw = maxCX - minCX + 1;
		int ch = maxCY - minCY + 1;
		boolean[] chunks = new boolean[cw * ch];
		int n = 0;
		for (int x = 0; x < tiles.length; x++) {
			Tile[] row = tiles[x];
			if (row == null) continue;
			for (int y = 0; y < row.length; y++) {
				if (row[y] == null) continue;
				int sx = x - offX;
				int sy = y - offY;
				int cx = ((wv.getBaseX() + sx) >> 3) - minCX;
				int cy = ((wv.getBaseY() + sy) >> 3) - minCY;
				int i = cx * ch + cy;
				if (!chunks[i]) {
					chunks[i] = true;
					n++;
				}
			}
		}
		return new Area(tiles, wv.getBaseX(), wv.getBaseY(), offX, offY, minX, minY, maxX, maxY, minCX, minCY, cw, ch, chunks, n);
	}
	private static Tile[][] tiles(WorldView wv) {
		Tile[][][] all = wv.getScene().getExtendedTiles();
		int plane = wv.getPlane();
		if (all == null || plane < 0 || plane >= all.length) return null;
		return all[plane];
	}
	private static final class Slot {
		final int baseX;
		final int baseY;
		final int plane;
		final int sizeX;
		final int sizeY;
		final boolean top;
		final Tile[][] tiles;
		final Area area;
		private Slot(WorldView wv, Tile[][] tiles, Area area) {
			baseX = wv.getBaseX();
			baseY = wv.getBaseY();
			plane = wv.getPlane();
			sizeX = wv.getSizeX();
			sizeY = wv.getSizeY();
			top = wv.isTopLevel();
			this.tiles = tiles;
			this.area = area;
		}
		boolean same(WorldView wv, Tile[][] tiles) {return baseX == wv.getBaseX() && baseY == wv.getBaseY() && plane == wv.getPlane() && sizeX == wv.getSizeX() && sizeY == wv.getSizeY() && top == wv.isTopLevel() && this.tiles == tiles;}
	}
	public static final class Area {
		public final Tile[][] tiles;
		public final int baseX;
		public final int baseY;
		public final int offX;
		public final int offY;
		public final int minX;
		public final int minY;
		public final int maxX;
		public final int maxY;
		public final int minCX;
		public final int minCY;
		public final int cw;
		public final int ch;
		public final boolean[] chunks;
		public final int[] cx;
		public final int[] cy;
		public final int n;
		public final long key;
		private Area(Tile[][] tiles, int baseX, int baseY, int offX, int offY, int minX, int minY, int maxX, int maxY, int minCX, int minCY, int cw, int ch, boolean[] chunks, int n) {
			this.tiles = tiles;
			this.baseX = baseX;
			this.baseY = baseY;
			this.offX = offX;
			this.offY = offY;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
			this.minCX = minCX;
			this.minCY = minCY;
			this.cw = cw;
			this.ch = ch;
			this.chunks = chunks;
			this.n = n;
			key = key(baseX, baseY, offX, offY, minX, minY, maxX, maxY, n, chunks);
			cx = new int[n];
			cy = new int[n];
			int j = 0;
			for (int x = 0; x < cw; x++) {
				for (int y = 0; y < ch; y++) {
					if (!chunks[x * ch + y]) continue;
					cx[j] = minCX + x;
					cy[j] = minCY + y;
					j++;
				}
			}
		}
		public boolean missing(int lx, int ly) {
			int x = Math.floorDiv(lx, TS) + offX;
			int y = Math.floorDiv(ly, TS) + offY;
			return x < 0 || y < 0 || x >= tiles.length || tiles[x] == null || y >= tiles[x].length || tiles[x][y] == null;
		}
		public boolean chunk(int x, int y) {
			x -= minCX;
			y -= minCY;
			return x >= 0 && y >= 0 && x < cw && y < ch && chunks[x * ch + y];
		}
		public int minWX() {return baseX + minX;}
		public int minWY() {return baseY + minY;}
		public int maxWX() {return baseX + maxX;}
		public int maxWY() {return baseY + maxY;}
	}
}
