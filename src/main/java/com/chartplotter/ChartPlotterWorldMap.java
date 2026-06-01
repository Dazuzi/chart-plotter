package com.chartplotter;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.api.worldmap.WorldMapData;
@Singleton
final class ChartPlotterWorldMap {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private final Client client;
	@Inject
	ChartPlotterWorldMap(Client client) {
		this.client = client;
	}
	State state() {
		Widget map = widget();
		WorldMap wm = client.getWorldMap();
		if (map == null || wm == null) return null;
		WorldMapData data = wm.getWorldMapData();
		float z = wm.getWorldMapZoom();
		Point pos = wm.getWorldMapPosition();
		if (data == null || z <= 0 || pos == null) return null;
		Rectangle r = map.getBounds();
		int wt = (int) Math.ceil(r.getWidth() / z);
		int ht = (int) Math.ceil(r.getHeight() / z);
		double c = z - Math.ceil(z / 2.0);
		return new State(data, z, r, wt, ht, pos, c, 0, 0);
	}
	int[] tile(Point m) {return tile(m, state());}
	int[] tile(Point m, State s) {
		if (m == null || s == null || !clip(s).contains(m.getX(), m.getY())) return null;
		double[] p = world(m, s);
		int wx = (int) Math.floor(p[0]);
		int wy = (int) Math.floor(p[1]);
		return s.data.surfaceContainsPosition(wx, wy) ? new int[]{wx, wy} : null;
	}
	Point point(State s, int wx, int wy, double fx, double fy) {
		double x = s.r.getX() + (wx + s.wt / 2.0 - s.pos.getX()) * s.z + s.c + (fx - 0.5) * s.z;
		double y = s.r.getY() + s.r.getHeight() - ((s.pos.getY() - s.ht / 2.0 - wy - 1) * -1 * s.z - s.c) - (fy - 0.5) * s.z;
		return new Point((int) Math.round(x), (int) Math.round(y));
	}
	double[] world(Point m, State s) {
		double wx = (m.getX() - s.r.getX() - s.c) / s.z - s.wt / 2.0 + s.pos.getX() + 0.5;
		double wy = (s.r.getY() + s.r.getHeight() + s.c - m.getY()) / s.z - 0.5 + s.pos.getY() - s.ht / 2.0;
		return new double[]{wx, wy};
	}
	int mapX(State s, int lx) {
		double x = s.baseX + lx / (double) TS;
		return (int) Math.round(s.r.getX() + (x + s.wt / 2.0 - s.pos.getX() - 0.5) * s.z + s.c);
	}
	int mapY(State s, int ly) {
		double y = s.baseY + ly / (double) TS;
		return (int) Math.round(s.r.getY() + s.r.getHeight() - (y + s.ht / 2.0 - s.pos.getY() + 0.5) * s.z + s.c);
	}
	int pathCap(WorldView wv, LocalPoint anchor, State s) {
		double ax = wv.getBaseX() + anchor.getX() / (double) TS;
		double ay = wv.getBaseY() + anchor.getY() / (double) TS;
		double dx = Math.max(Math.abs(ax - (s.pos.getX() - s.wt / 2.0)), Math.abs(ax - (s.pos.getX() + s.wt / 2.0)));
		double dy = Math.max(Math.abs(ay - (s.pos.getY() - s.ht / 2.0)), Math.abs(ay - (s.pos.getY() + s.ht / 2.0)));
		return (int) Math.ceil(Math.max(dx, dy) * 8) + 64;
	}
	Shape clip(State s) {
		Rectangle r = new Rectangle(s.r.x + 1, s.r.y + 1, Math.max(1, s.r.width - 2), Math.max(1, s.r.height - 2));
		Widget overview = client.getWidget(InterfaceID.Worldmap.OVERVIEW_CONTAINER);
		Widget selector = client.getWidget(InterfaceID.Worldmap.MAPLIST_BOX_GRAPHIC0);
		Area a = new Area(r);
		boolean cut = false;
		if (overview != null && !overview.isHidden()) {
			a.subtract(new Area(overview.getBounds()));
			cut = true;
		}
		if (selector != null && !selector.isHidden()) {
			a.subtract(new Area(selector.getBounds()));
			cut = true;
		}
		return cut ? a : r;
	}
	private Widget widget() {
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return map == null || map.isHidden() ? null : map;
	}
	static final class State {
		final WorldMapData data;
		final float z;
		final Rectangle r;
		final int wt;
		final int ht;
		final Point pos;
		final double c;
		final int baseX;
		final int baseY;
		State(WorldMapData data, float z, Rectangle r, int wt, int ht, Point pos, double c, int baseX, int baseY) {
			this.data = data;
			this.z = z;
			this.r = r;
			this.wt = wt;
			this.ht = ht;
			this.pos = pos;
			this.c = c;
			this.baseX = baseX;
			this.baseY = baseY;
		}
		State base(WorldView wv) {return new State(data, z, r, wt, ht, pos, c, wv.getBaseX(), wv.getBaseY());}
	}
}
