package com.chartplotter;
import javax.inject.Singleton;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
@Singleton
final class ChartPlotterScene {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private Slot cache;
	Area area(WorldView wv) {
		Tile[][] tiles = tiles(wv);
		Slot s = cache;
		if (s != null && s.same(wv, tiles)) return s.area;
		Area area = area(wv, tiles);
		cache = new Slot(wv, tiles, area);
		return area;
	}
	static long key(Area a) {
		if (a == null) return 0;
		long h = 1125899906842597L;
		h = h * 31 + a.baseX;
		h = h * 31 + a.baseY;
		h = h * 31 + a.offX;
		h = h * 31 + a.offY;
		h = h * 31 + a.minX;
		h = h * 31 + a.minY;
		h = h * 31 + a.maxX;
		h = h * 31 + a.maxY;
		h = h * 31 + a.n;
		for (boolean b : a.chunks) h = h * 31 + (b ? 1 : 0);
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
	static final class Area {
		final Tile[][] tiles;
		final int baseX;
		final int baseY;
		final int offX;
		final int offY;
		final int minX;
		final int minY;
		final int maxX;
		final int maxY;
		final int minCX;
		final int minCY;
		final int cw;
		final int ch;
		final boolean[] chunks;
		final int[] cx;
		final int[] cy;
		final int n;
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
		boolean missing(int lx, int ly) {
			int x = Math.floorDiv(lx, TS) + offX;
			int y = Math.floorDiv(ly, TS) + offY;
			return x < 0 || y < 0 || x >= tiles.length || tiles[x] == null || y >= tiles[x].length || tiles[x][y] == null;
		}
		boolean chunk(int x, int y) {
			x -= minCX;
			y -= minCY;
			return x >= 0 && y >= 0 && x < cw && y < ch && chunks[x * ch + y];
		}
		int minWX() {return baseX + minX;}
		int minWY() {return baseY + minY;}
		int maxWX() {return baseX + maxX;}
		int maxWY() {return baseY + maxY;}
	}
}
