package com.chartplotter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
public class ChartPlotterMinimapOverlay extends Overlay {
	private static final int DIST = 32768;
	private static final int[] ORBS = {InterfaceID.Orbs.HEALTH_BACKING, InterfaceID.Orbs.PRAYER_BACKING, InterfaceID.Orbs.RUNENERGY_BACKING, InterfaceID.Orbs.SPECENERGY_BACKING, InterfaceID.Orbs.ORB_WORLDMAP};
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterOverlay world;
	private volatile Shape clip;
	@Inject
	ChartPlotterMinimapOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterOverlay world) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.world = world;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
	}
	@Override
	public Dimension render(Graphics2D g) {
		if (!plugin.isSailing()) {
			clip = null;
			return null;
		}
		Widget m = minimap(client);
		if (m == null || m.isHidden()) {
			clip = null;
			return null;
		}
		Shape c = clip(client, m);
		clip = c;
		if (!config.minimapEnabled()) return null;
		WorldView top = client.getTopLevelWorldView();
		WorldEntity ship = plugin.getShip();
		if (ship == null || top == null) return null;
		LocalPoint anchor = ship.getTargetLocation();
		LocalPoint center = ship.getLocalLocation();
		if (anchor == null) anchor = center;
		if (anchor == null || center == null) return null;
		int from = ChartPlotterPlugin.norm(ship.getTargetOrientation());
		int course = plugin.course(ship);
		int mouse = hoverHeading(top, center);
		ChartPlotterOverlay.Path cur = world.path(top, ship.getConfig(), anchor, from, course);
		ChartPlotterOverlay.Path pot = mouse >= 0 ? world.path(top, ship.getConfig(), anchor, from, mouse) : null;
		int skip = pot != null ? ChartPlotterOverlay.match(cur, pot) : 0;
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setClip(c);
		g.setStroke(new BasicStroke(config.minimapLineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		drawRoute(g, top, plugin.route());
		draw(g, top, cur, config.minimapLineColor(), skip);
		if (pot != null) draw(g, top, pot, config.minimapPotentialColor(), 0);
		g.setStroke(oldStroke);
		g.setClip(oldClip);
		return null;
	}
	boolean overMinimap(Point p) {return p != null && clip != null && clip.contains(p.getX(), p.getY());}
	static int mouseHeading(Client client, LocalPoint anchor, Point mouse) {
		if (mouse == null) return -1;
		Widget w = minimap(client);
		if (w == null || w.isHidden() || !clip(client, w).contains(mouse.getX(), mouse.getY())) return -1;
		Point center = Perspective.localToMinimap(client, anchor, DIST);
		if (center == null) {
			java.awt.Rectangle b = w.getBounds();
			center = new Point((int) b.getCenterX(), (int) b.getCenterY());
		}
		int sx = mouse.getX() - center.getX();
		int sy = mouse.getY() - center.getY();
		if (sx == 0 && sy == 0) return -1;
		int a = client.getCameraYawTarget() & 2047;
		int x = Perspective.COSINE[a] * sx + Perspective.SINE[a] * sy >> 16;
		int y = Perspective.SINE[a] * sx - Perspective.COSINE[a] * sy >> 16;
		double d = Math.toDegrees(Math.atan2(y, x));
		return ChartPlotterPlugin.norm((int) Math.round((270 - d) / 360 * 16) * 128);
	}
	static Widget minimap(Client client) {
		if (client.isResized()) {
			if (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1) return client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
			return client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
		}
		return client.getWidget(InterfaceID.Toplevel.MINIMAP);
	}
	private void draw(Graphics2D g, WorldView wv, ChartPlotterOverlay.Path p, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) {
			if (p.blocked && p.n == 1 && skip < p.n) drawBlock(g, wv, p, color);
			return;
		}
		int start = skip > 0 ? skip - 1 : 0;
		int mid = Math.min(p.blockedAt, p.n);
		segment(g, wv, p, color, start, mid);
		if (mid < p.n) segment(g, wv, p, config.blockedColor(), Math.max(start, mid - 1), p.n);
	}
	private void segment(Graphics2D g, WorldView wv, ChartPlotterOverlay.Path p, Color color, int from, int to) {
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		for (int i = from; i < to; i++) {
			Point q = Perspective.localToMinimap(client, new LocalPoint(p.x[i], p.y[i], wv), DIST);
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
	private void drawBlock(Graphics2D g, WorldView wv, ChartPlotterOverlay.Path p, Color color) {
		Point q = Perspective.localToMinimap(client, new LocalPoint(p.x[0], p.y[0], wv), DIST);
		if (q == null) return;
		int r = 5;
		g.setColor(color);
		g.drawLine(q.getX() - r, q.getY() - r, q.getX() + r, q.getY() + r);
		g.drawLine(q.getX() + r, q.getY() - r, q.getX() - r, q.getY() + r);
	}
	private void drawRoute(Graphics2D g, WorldView wv, ChartPlotterRoute r) {
		if (r == null || r.status != ChartPlotterRoute.OK || r.n < 2) return;
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		for (int i = 0; i < r.n; i++) {
			int lx = (r.x[i] - wv.getBaseX()) * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_TILE_SIZE / 2;
			int ly = (r.y[i] - wv.getBaseY()) * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_TILE_SIZE / 2;
			Point q = Perspective.localToMinimap(client, new LocalPoint(lx, ly, wv), DIST);
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
		g.setColor(config.minimapChartColor());
		g.draw(line);
	}
	private int hoverHeading(WorldView wv, LocalPoint anchor) {
		Point m = client.getMouseCanvasPosition();
		if (m == null || client.getCanvas().getMousePosition() == null || client.isMenuOpen()) return -1;
		if (overMinimap(m)) return mouseHeading(client, anchor, m);
		if (!viewport(m) || !activeHeading(wv)) return -1;
		return ChartPlotterOverlay.mouseHeading(client, wv, anchor, m);
	}
	private boolean activeHeading(WorldView wv) {
		if (wv.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return false;
		MenuEntry[] es = client.getMenu().getMenuEntries();
		return es.length > 0 && es[es.length - 1].getType() == MenuAction.SET_HEADING;
	}
	private boolean viewport(Point m) {
		int x = client.getViewportXOffset();
		int y = client.getViewportYOffset();
		return m.getX() >= x && m.getY() >= y && m.getX() < x + client.getViewportWidth() && m.getY() < y + client.getViewportHeight();
	}
	private static Shape clip(Client client, Widget minimap) {
		java.awt.Rectangle b = minimap.getBounds();
		Area a = ellipse(b, client.isResized() ? 1 : 3);
		for (int id : ORBS) {
			Widget o = client.getWidget(id);
			if (o != null && !o.isHidden()) a.subtract(ellipse(o.getBounds(), 0));
		}
		return a;
	}
	private static Area ellipse(java.awt.Rectangle b, int p) {return new Area(new Ellipse2D.Double(b.getX() - p, b.getY() - p, b.getWidth() + p * 2, b.getHeight() + p * 2));}
}
