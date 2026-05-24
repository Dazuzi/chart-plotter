package com.chartplotter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.api.worldmap.WorldMapData;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
public class ChartPlotterWorldMapOverlay extends Overlay {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterOverlay world;
	private final ChartPlotterCollisionCache collisionCache;
	@Inject
	ChartPlotterWorldMapOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterOverlay world, ChartPlotterCollisionCache collisionCache) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.world = world;
		this.collisionCache = collisionCache;
		setLayer(OverlayLayer.MANUAL);
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}
	@Override
	public Dimension render(Graphics2D g) {
		if (!plugin.isSailing()) return null;
		boolean showWorldMap = config.worldMapEnabled();
		ChartPlotterCacheOverlay cacheOverlay = config.cacheOverlay();
		if (!showWorldMap && !cacheOverlay.worldMap) return null;
		Widget map = map();
		WorldMap wm = client.getWorldMap();
		if (map == null || wm == null || wm.getWorldMapData() == null) return null;
		Shape clip = clip(map.getBounds());
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setClip(clip);
		if (cacheOverlay.worldMap) drawCache(g, map, wm);
		if (!showWorldMap) {
			g.setStroke(oldStroke);
			g.setClip(oldClip);
			return null;
		}
		WorldView top = client.getTopLevelWorldView();
		WorldEntity ship = plugin.getShip();
		if (ship == null || top == null) {
			g.setStroke(oldStroke);
			g.setClip(oldClip);
			return null;
		}
		LocalPoint anchor = ship.getTargetLocation();
		LocalPoint center = ship.getLocalLocation();
		if (anchor == null) anchor = center;
		if (anchor == null || center == null) {
			g.setStroke(oldStroke);
			g.setClip(oldClip);
			return null;
		}
		int from = ChartPlotterPlugin.norm(ship.getTargetOrientation());
		int course = plugin.course(ship);
		int mouse = hoverHeading(top, center, map, clip);
		int cap = pathCap(top, anchor, map, wm);
		ChartPlotterOverlay.Path cur = world.path(top, ship.getConfig(), anchor, from, course, cap);
		ChartPlotterOverlay.Path pot = null;
		if (mouse >= 0) pot = world.path(top, ship.getConfig(), anchor, from, mouse, cap);
		int skip = pot != null ? ChartPlotterOverlay.match(cur, pot) : 0;
		g.setStroke(new BasicStroke(config.worldMapLineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		drawRoute(g, map, wm, plugin.route());
		draw(g, top, map, wm, cur, config.worldMapLineColor(), skip);
		if (pot != null) draw(g, top, map, wm, pot, config.worldMapPotentialColor(), 0);
		g.setStroke(oldStroke);
		g.setClip(oldClip);
		return null;
	}
	private Widget map() {
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return map == null || map.isHidden() ? null : map;
	}
	int[] tile(Point m) {
		Widget map = map();
		WorldMap wm = client.getWorldMap();
		if (m == null || map == null || wm == null || wm.getWorldMapData() == null) return null;
		if (wm.getWorldMapZoom() <= 0) return null;
		if (!clip(map.getBounds()).contains(m.getX(), m.getY())) return null;
		double[] p = world(m, map, wm);
		int wx = (int) Math.floor(p[0]);
		int wy = (int) Math.floor(p[1]);
		return wm.getWorldMapData().surfaceContainsPosition(wx, wy) ? new int[]{wx, wy} : null;
	}
	private void draw(Graphics2D g, WorldView wv, Widget map, WorldMap wm, ChartPlotterOverlay.Path p, Color color, int skip) {
		MapState s = state(wv, map, wm);
		if (s == null) return;
		if (p.n < 2 || skip >= p.n) {
			if (p.blocked && p.n == 1 && skip < p.n) drawBlock(g, s, p, color);
			return;
		}
		int start = skip > 0 ? skip - 1 : 0;
		int mid = Math.min(p.blockedAt, p.n);
		segment(g, s, p, color, start, mid);
		if (mid < p.n) segment(g, s, p, config.blockedColor(), Math.max(start, mid - 1), p.n);
	}
	private void segment(Graphics2D g, MapState s, ChartPlotterOverlay.Path p, Color color, int from, int to) {
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		for (int i = from; i < to; i++) {
			int sx = Math.floorDiv(p.x[i], TS);
			int sy = Math.floorDiv(p.y[i], TS);
			if (!s.data.surfaceContainsPosition(s.baseX + sx, s.baseY + sy)) {
				have = false;
				continue;
			}
			int x = mapX(s, p.x[i]);
			int y = mapY(s, p.y[i]);
			if (have) line.lineTo(x, y);
			else {
				line.moveTo(x, y);
				have = true;
			}
		}
		g.setColor(color);
		g.draw(line);
	}
	private void drawBlock(Graphics2D g, MapState s, ChartPlotterOverlay.Path p, Color color) {
		int sx = Math.floorDiv(p.x[0], TS);
		int sy = Math.floorDiv(p.y[0], TS);
		if (!s.data.surfaceContainsPosition(s.baseX + sx, s.baseY + sy)) return;
		int x = mapX(s, p.x[0]);
		int y = mapY(s, p.y[0]);
		int r = 5;
		g.setColor(color);
		g.drawLine(x - r, y - r, x + r, y + r);
		g.drawLine(x + r, y - r, x - r, y + r);
	}
	private void drawRoute(Graphics2D g, Widget map, WorldMap wm, ChartPlotterRoute r) {
		if (r == null) return;
		Color c = r.status == ChartPlotterRoute.OK ? config.worldMapChartColor() : r.status == ChartPlotterRoute.UNCHARTED ? new Color(255, 80, 60, 220) : r.status == ChartPlotterRoute.BLOCKED ? new Color(170, 170, 170, 220) : new Color(255, 190, 40, 220);
		if (r.status == ChartPlotterRoute.OK && r.n > 1) {
			Path2D.Double line = new Path2D.Double();
			boolean have = false;
			for (int i = 0; i < r.n; i++) {
				Point q = mapPoint(map, wm, r.x[i], r.y[i], 0.5, 0.5);
				if (q == null) {
					have = false;
					continue;
				}
				if (have) line.lineTo(q.getX(), q.getY());
				else {
					line.moveTo(q.getX(), q.getY());
					have = true;
				}
			}
			g.setColor(c);
			g.draw(line);
		}
		Point t = mapPoint(map, wm, r.tx, r.ty, 0.5, 0.5);
		if (t == null) return;
		g.setColor(c);
		g.fill(new Ellipse2D.Double(t.getX() - 3.5, t.getY() - 3.5, 7, 7));
		g.draw(new Ellipse2D.Double(t.getX() - 7.5, t.getY() - 7.5, 15, 15));
		String s = r.text();
		if (s != null) tip(g, map.getBounds(), t, s);
	}
	private void drawCache(Graphics2D g, Widget map, WorldMap wm) {
		Stroke old = g.getStroke();
		ChartPlotterCollisionData data = collisionCache.snapshot();
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		g.setColor(new Color(0, 210, 120, 150));
		for (Map.Entry<Long, ChartPlotterCollisionCache.Chunk> e : data.entries()) {
			if (e.getValue().empty()) continue;
			int cx = (int) (e.getKey() >> 32);
			int cy = (int) (long) e.getKey();
			if (!cacheChunkVisible(wm, cx, cy)) continue;
			int wx = cx << 3;
			int wy = cy << 3;
			if (data.uncached(cx - 1, cy)) drawCacheEdge(g, map, wm, wx, wy, 0, 0, wx, wy + 7, 0, 1);
			if (data.uncached(cx + 1, cy)) drawCacheEdge(g, map, wm, wx + 7, wy, 1, 0, wx + 7, wy + 7, 1, 1);
			if (data.uncached(cx, cy - 1)) drawCacheEdge(g, map, wm, wx, wy, 0, 0, wx + 7, wy, 1, 0);
			if (data.uncached(cx, cy + 1)) drawCacheEdge(g, map, wm, wx, wy + 7, 0, 1, wx + 7, wy + 7, 1, 1);
		}
		g.setStroke(old);
	}
	private boolean cacheChunkVisible(WorldMap wm, int cx, int cy) {
		return wm.getWorldMapData().surfaceContainsPosition((cx << 3) + 4, (cy << 3) + 4);
	}
	private void drawCacheEdge(Graphics2D g, Widget map, WorldMap wm, int ax, int ay, double afx, double afy, int bx, int by, double bfx, double bfy) {
		Point a = mapPoint(map, wm, ax, ay, afx, afy);
		Point b = mapPoint(map, wm, bx, by, bfx, bfy);
		if (a == null || b == null) return;
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private Point mapPoint(Widget map, WorldMap wm, int wx, int wy, double fx, double fy) {
		float z = wm.getWorldMapZoom();
		if (z <= 0) return null;
		Rectangle r = map.getBounds();
		int wt = (int) Math.ceil(r.getWidth() / z);
		int ht = (int) Math.ceil(r.getHeight() / z);
		Point pos = wm.getWorldMapPosition();
		double c = z - Math.ceil(z / 2.0);
		double x = r.getX() + (wx + wt / 2.0 - pos.getX()) * z + c + (fx - 0.5) * z;
		double y = r.getY() + r.getHeight() - ((pos.getY() - ht / 2.0 - wy - 1) * -1 * z - c) - (fy - 0.5) * z;
		return new Point((int) Math.round(x), (int) Math.round(y));
	}
	private double[] world(Point m, Widget map, WorldMap wm) {
		float z = wm.getWorldMapZoom();
		Rectangle r = map.getBounds();
		int wt = (int) Math.ceil(r.getWidth() / z);
		int ht = (int) Math.ceil(r.getHeight() / z);
		Point pos = wm.getWorldMapPosition();
		double c = z - Math.ceil(z / 2.0);
		double wx = (m.getX() - r.getX() - c) / z - wt / 2.0 + pos.getX() + 0.5;
		double wy = (r.getY() + r.getHeight() + c - m.getY()) / z - 0.5 + pos.getY() - ht / 2.0;
		return new double[]{wx, wy};
	}
	private MapState state(WorldView wv, Widget map, WorldMap wm) {
		WorldMapData data = wm.getWorldMapData();
		float z = wm.getWorldMapZoom();
		Point pos = wm.getWorldMapPosition();
		if (data == null || z <= 0 || pos == null) return null;
		Rectangle r = map.getBounds();
		int wt = (int) Math.ceil(r.getWidth() / z);
		int ht = (int) Math.ceil(r.getHeight() / z);
		double c = z - Math.ceil(z / 2.0);
		return new MapState(data, z, r, wt, ht, pos, c, wv.getBaseX(), wv.getBaseY());
	}
	private static int mapX(MapState s, int lx) {
		double x = s.baseX + lx / (double) TS;
		return (int) Math.round(s.r.getX() + (x + s.wt / 2.0 - s.pos.getX() - 0.5) * s.z + s.c);
	}
	private static int mapY(MapState s, int ly) {
		double y = s.baseY + ly / (double) TS;
		return (int) Math.round(s.r.getY() + s.r.getHeight() - (y + s.ht / 2.0 - s.pos.getY() + 0.5) * s.z + s.c);
	}
	private void tip(Graphics2D g, Rectangle r, Point p, String s) {
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(s) + 10;
		int h = fm.getHeight() + 6;
		int x = p.getX() + 12;
		int y = p.getY() - h - 8;
		if (x + w > r.x + r.width) x = p.getX() - w - 12;
		if (y < r.y) y = p.getY() + 12;
		x = Math.max(r.x + 4, Math.min(x, r.x + r.width - w - 4));
		y = Math.max(r.y + 4, Math.min(y, r.y + r.height - h - 4));
		g.setColor(new Color(20, 20, 20, 220));
		g.fillRect(x, y, w, h);
		g.setColor(Color.WHITE);
		g.drawString(s, x + 5, y + fm.getAscent() + 3);
	}
	private int pathCap(WorldView wv, LocalPoint anchor, Widget map, WorldMap wm) {
		float z = wm.getWorldMapZoom();
		if (z <= 0) return 512;
		Rectangle r = map.getBounds();
		int wt = (int) Math.ceil(r.getWidth() / z);
		int ht = (int) Math.ceil(r.getHeight() / z);
		Point pos = wm.getWorldMapPosition();
		double ax = wv.getBaseX() + anchor.getX() / (double) TS;
		double ay = wv.getBaseY() + anchor.getY() / (double) TS;
		double dx = Math.max(Math.abs(ax - (pos.getX() - wt / 2.0)), Math.abs(ax - (pos.getX() + wt / 2.0)));
		double dy = Math.max(Math.abs(ay - (pos.getY() - ht / 2.0)), Math.abs(ay - (pos.getY() + ht / 2.0)));
		return (int) Math.ceil(Math.max(dx, dy) * 8) + 64;
	}
	private int hoverHeading(WorldView wv, LocalPoint anchor, Widget map, Shape clip) {
		Point m = client.getMouseCanvasPosition();
		if (m == null || client.getCanvas().getMousePosition() == null || client.isMenuOpen() || !clip.contains(m.getX(), m.getY())) return -1;
		WorldMap wm = client.getWorldMap();
		float z = wm.getWorldMapZoom();
		if (z <= 0) return -1;
		Rectangle r = map.getBounds();
		int wt = (int) Math.ceil(r.getWidth() / z);
		int ht = (int) Math.ceil(r.getHeight() / z);
		Point pos = wm.getWorldMapPosition();
		double c = z - Math.ceil(z / 2.0);
		double wx = (m.getX() - r.getX() - c) / z - wt / 2.0 + pos.getX() + 0.5;
		double wy = (r.getY() + r.getHeight() + c - m.getY()) / z - 0.5 + pos.getY() - ht / 2.0;
		double ax = wv.getBaseX() + anchor.getX() / (double) TS;
		double ay = wv.getBaseY() + anchor.getY() / (double) TS;
		double dx = wx - ax;
		double dy = wy - ay;
		if (dx == 0 && dy == 0) return -1;
		double d = Math.toDegrees(Math.atan2(dy, dx));
		return ChartPlotterPlugin.norm((int) Math.round((270 - d) / 360 * 16) * 128);
	}
	private Shape clip(Rectangle r) {
		r = new Rectangle(r.x + 1, r.y + 1, Math.max(1, r.width - 2), Math.max(1, r.height - 2));
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
	private static final class MapState {
		final WorldMapData data;
		final float z;
		final Rectangle r;
		final int wt;
		final int ht;
		final Point pos;
		final double c;
		final int baseX;
		final int baseY;
		private MapState(WorldMapData data, float z, Rectangle r, int wt, int ht, Point pos, double c, int baseX, int baseY) {
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
	}
}
