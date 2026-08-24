package com.chartplotter.runtime;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.api.worldmap.WorldMapData;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.geom.Area;

@Singleton
public final class ChartPlotterWorldMap {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int[][] SURFACE = {{3222, 3218}, {3213, 3424}, {2964, 3378}, {2662, 3305}};
	private static final int[] BLOCK = {InterfaceID.Worldmap.OVERVIEW_CONTAINER, InterfaceID.Worldmap.SIDE, InterfaceID.Worldmap.BOTTOM, InterfaceID.Worldmap.MAPLIST_CONTAINER, InterfaceID.Worldmap.CLOSE, InterfaceID.Worldmap.RESIZE_INDICATOR, InterfaceID.Worldmap.RESIZE_GRAPHIC};
	private final Client client;
	private ClipKey clipKey;
	private Shape cachedClip;
	private WorldMapData surfaceData;
	private boolean cachedSurface;
	private volatile boolean cachedClickBlocked = true;
	@Inject
	public ChartPlotterWorldMap(Client client) {
		this.client = client;
	}
	public State state() {
		Widget map = widget();
		WorldMap wm = client.getWorldMap();
		if (map == null || wm == null) {
			cachedClickBlocked = true;
			return null;
		}
		WorldMapData data = wm.getWorldMapData();
		float z = wm.getWorldMapZoom();
		Point pos = wm.getWorldMapPosition();
		boolean blocked = blocked(data);
		cachedClickBlocked = blocked;
		if (blocked || z <= 0 || pos == null) return null;
		Rectangle r = map.getBounds();
		int wt = (int) Math.ceil(r.getWidth() / z);
		int ht = (int) Math.ceil(r.getHeight() / z);
		double c = z - Math.ceil(z / 2.0);
		return new State(data, z, r, wt, ht, pos, c);
	}
	public int[] tile(Point m) {return tile(m, state());}
	public int[] tile(Point m, State s) {
		if (m == null || s == null || !clip(s).contains(m.getX(), m.getY())) return null;
		int wx = (int) Math.floor(worldX(m, s));
		int wy = (int) Math.floor(worldY(m, s));
		return s.data.surfaceContainsPosition(wx, wy) ? new int[]{wx, wy} : null;
	}
	public boolean tile(Point m, State s, int[] out) {
		if (m == null || s == null || out == null || out.length < 2 || !clip(s).contains(m.getX(), m.getY())) return false;
		int wx = (int) Math.floor(worldX(m, s));
		int wy = (int) Math.floor(worldY(m, s));
		if (!s.data.surfaceContainsPosition(wx, wy)) return false;
		out[0] = wx;
		out[1] = wy;
		return true;
	}
	public boolean clickBlocked() {
		WorldMap wm = client.getWorldMap();
		if (wm == null) {
			cachedClickBlocked = true;
			return true;
		}
		WorldMapData data = wm.getWorldMapData();
		boolean blocked = blocked(data);
		cachedClickBlocked = blocked;
		return blocked;
	}
	public boolean cachedClickBlocked() {return cachedClickBlocked;}
	public int pointX(State s, int wx, double fx) {return (int) Math.round(s.r.getX() + (wx + s.wt / 2.0 - s.pos.getX()) * s.z + s.c + (fx - 0.5) * s.z);}
	public int pointY(State s, int wy, double fy) {return (int) Math.round(s.r.getY() + s.r.getHeight() - ((s.pos.getY() - s.ht / 2.0 - wy - 1) * -1 * s.z - s.c) - (fy - 0.5) * s.z);}
	public double worldX(Point m, State s) {return (m.getX() - s.r.getX() - s.c) / s.z - s.wt / 2.0 + s.pos.getX() + 0.5;}
	public double worldY(Point m, State s) {return (s.r.getY() + s.r.getHeight() + s.c - m.getY()) / s.z - 0.5 + s.pos.getY() - s.ht / 2.0;}
	public int mapX(State s, int baseX, int lx) {
		double x = baseX + lx / (double) TS;
		return (int) Math.round(s.r.getX() + (x + s.wt / 2.0 - s.pos.getX() - 0.5) * s.z + s.c);
	}
	public int mapY(State s, int baseY, int ly) {
		double y = baseY + ly / (double) TS;
		return (int) Math.round(s.r.getY() + s.r.getHeight() - (y + s.ht / 2.0 - s.pos.getY() + 0.5) * s.z + s.c);
	}
	public int pathCap(WorldView wv, LocalPoint anchor, State s) {
		double ax = wv.getBaseX() + anchor.getX() / (double) TS;
		double ay = wv.getBaseY() + anchor.getY() / (double) TS;
		double dx = Math.max(Math.abs(ax - (s.pos.getX() - s.wt / 2.0)), Math.abs(ax - (s.pos.getX() + s.wt / 2.0)));
		double dy = Math.max(Math.abs(ay - (s.pos.getY() - s.ht / 2.0)), Math.abs(ay - (s.pos.getY() + s.ht / 2.0)));
		return (int) Math.ceil(Math.max(dx, dy) * 8) + 64;
	}
	public Shape clip(State s) {
		if (clipKey != null && clipKey.same(client, s)) return cachedClip;
		ClipKey k = new ClipKey(client, s);
		Rectangle r = new Rectangle(s.r.x + 1, s.r.y + 1, Math.max(1, s.r.width - 2), Math.max(1, s.r.height - 2));
		Area a = new Area(r);
		boolean cut = false;
		for (int id : BLOCK) {
			Widget w = client.getWidget(id);
			if (w == null || w.isHidden()) continue;
			a.subtract(new Area(w.getBounds()));
			cut = true;
		}
		clipKey = k;
		cachedClip = cut ? a : r;
		return cachedClip;
	}
	private Widget widget() {
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return map == null || map.isHidden() ? null : map;
	}
	private boolean surface(WorldMapData data) {
		if (surfaceData == data) return cachedSurface;
		surfaceData = data;
		for (int[] p : SURFACE) {
			if (!data.surfaceContainsPosition(p[0], p[1])) return cachedSurface = false;
		}
		return cachedSurface = true;
	}
	private boolean blocked(WorldMapData data) {return data == null || !surface(data) || client.getVarcIntValue(VarClientID.WORLDMAP_INTERMAPLINK) > 0;}
	public static final class State {
		public final WorldMapData data;
		public final float z;
		public final Rectangle r;
		public final int wt;
		public final int ht;
		public final Point pos;
		public final double c;
		public State(WorldMapData data, float z, Rectangle r, int wt, int ht, Point pos, double c) {
			this.data = data;
			this.z = z;
			this.r = r;
			this.wt = wt;
			this.ht = ht;
			this.pos = pos;
			this.c = c;
		}
	}
	private static final class ClipKey {
		final int[] v;
		private ClipKey(Client client, State s) {
			v = new int[4 + BLOCK.length * 5];
			int i = 0;
			v[i++] = s.r.x;
			v[i++] = s.r.y;
			v[i++] = s.r.width;
			v[i++] = s.r.height;
			for (int id : BLOCK) {
				Widget w = client.getWidget(id);
				if (w == null || w.isHidden()) {
					v[i++] = 0;
					i += 4;
					continue;
				}
				Rectangle b = w.getBounds();
				v[i++] = 1;
				v[i++] = b.x;
				v[i++] = b.y;
				v[i++] = b.width;
				v[i++] = b.height;
			}
		}
		boolean same(Client client, State s) {
			int i = 0;
			if (v[i++] != s.r.x || v[i++] != s.r.y || v[i++] != s.r.width || v[i++] != s.r.height) return false;
			for (int id : BLOCK) {
				Widget w = client.getWidget(id);
				if (w == null || w.isHidden()) {
					if (v[i] != 0) return false;
					i += 5;
					continue;
				}
				Rectangle b = w.getBounds();
				if (v[i++] != 1 || v[i++] != b.x || v[i++] != b.y || v[i++] != b.width || v[i++] != b.height) return false;
			}
			return true;
		}
	}
}
