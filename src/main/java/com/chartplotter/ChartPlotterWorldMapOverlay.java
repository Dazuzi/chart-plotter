package com.chartplotter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
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
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
public class ChartPlotterWorldMapOverlay extends Overlay {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterOverlay world;
	@Inject
	ChartPlotterWorldMapOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterOverlay world) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.world = world;
		setLayer(OverlayLayer.MANUAL);
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}
	@Override
	public Dimension render(Graphics2D g) {
		if (!plugin.isSailing() || !config.worldMapEnabled()) return null;
		Widget map = map();
		WorldMap wm = client.getWorldMap();
		if (map == null || wm == null || wm.getWorldMapData() == null) return null;
		WorldView top = client.getTopLevelWorldView();
		WorldEntity ship = plugin.getShip();
		if (ship == null || top == null) return null;
		LocalPoint anchor = ship.getTargetLocation();
		LocalPoint center = ship.getLocalLocation();
		if (anchor == null) anchor = center;
		if (anchor == null || center == null) return null;
		int from = ChartPlotterPlugin.norm(ship.getTargetOrientation());
		int course = plugin.course(ship);
		Shape clip = clip(map.getBounds());
		int mouse = hoverHeading(top, center, map, clip);
		ChartPlotterOverlay.Path cur = world.path(top, anchor, from, course);
		ChartPlotterOverlay.Path pot = mouse >= 0 ? world.path(top, anchor, from, mouse) : null;
		int skip = pot != null ? ChartPlotterOverlay.match(cur, pot) : 0;
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setClip(clip);
		g.setStroke(new BasicStroke(config.worldMapLineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		draw(g, top, map, cur, config.worldMapLineColor(), skip);
		if (pot != null) draw(g, top, map, pot, config.worldMapPotentialColor(), 0);
		g.setStroke(oldStroke);
		g.setClip(oldClip);
		return null;
	}
	private Widget map() {
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return map == null || map.isHidden() ? null : map;
	}
	private void draw(Graphics2D g, WorldView wv, Widget map, ChartPlotterOverlay.Path p, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) return;
		int start = skip > 0 ? skip - 1 : 0;
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		for (int i = start; i < p.n; i++) {
			Point q = mapPoint(wv, p.x[i], p.y[i], map);
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
		g.setColor(color);
		g.draw(line);
	}
	private Point mapPoint(WorldView wv, int lx, int ly, Widget map) {
		WorldMap wm = client.getWorldMap();
		float z = wm.getWorldMapZoom();
		if (z <= 0) return null;
		int sx = Math.floorDiv(lx, TS);
		int sy = Math.floorDiv(ly, TS);
		int wx = wv.getBaseX() + sx;
		int wy = wv.getBaseY() + sy;
		if (!wm.getWorldMapData().surfaceContainsPosition(wx, wy)) return null;
		Rectangle r = map.getBounds();
		int wt = (int) Math.ceil(r.getWidth() / z);
		int ht = (int) Math.ceil(r.getHeight() / z);
		Point pos = wm.getWorldMapPosition();
		double fx = (lx - sx * TS) / (double) TS;
		double fy = (ly - sy * TS) / (double) TS;
		double c = z - Math.ceil(z / 2.0);
		double x = r.getX() + (wx + wt / 2.0 - pos.getX()) * z + c + (fx - 0.5) * z;
		double y = r.getY() + r.getHeight() - ((pos.getY() - ht / 2.0 - wy - 1) * -1 * z - c) - (fy - 0.5) * z;
		return new Point((int) x, (int) y);
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
}
