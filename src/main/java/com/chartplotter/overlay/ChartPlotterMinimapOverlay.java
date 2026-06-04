package com.chartplotter.overlay;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterLineMode;
import com.chartplotter.ChartPlotterPlugin;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRouteMoves;
import com.chartplotter.runtime.ChartPlotterProjection;
import com.chartplotter.util.ChartPlotterMath;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.Arrays;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
public class ChartPlotterMinimapOverlay extends Overlay {
	private static final int DIST = 32768;
	private static final float[] DASH = {8, 6};
	private static final int[] ORBS = {InterfaceID.Orbs.HEALTH_BACKING, InterfaceID.Orbs.PRAYER_BACKING, InterfaceID.Orbs.RUNENERGY_BACKING, InterfaceID.Orbs.SPECENERGY_BACKING, InterfaceID.Orbs.ORB_WORLDMAP};
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterProjection projection;
	private volatile Shape clip;
	private ClipKey clipKey;
	private Shape cachedClip;
	@Inject
	ChartPlotterMinimapOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterProjection projection) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.projection = projection;
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
		Shape c = clip(m);
		clip = c;
		WorldView top = plugin.top();
		boolean active = plugin.courseLine(top);
		ChartPlotterLineMode courseMode = config.minimapLineMode();
		ChartPlotterLineMode projectedMode = config.minimapProjectedLineMode();
		boolean showCourse = active && courseMode.on;
		boolean showProjected = active && projectedMode.on;
		boolean showChart = config.minimapChartLine();
		if (!showCourse && !showProjected && !showChart) return null;
		WorldEntity ship = plugin.getShip();
		if (ship == null || top == null) return null;
		LocalPoint anchor = ship.getTargetLocation();
		LocalPoint center = ship.getLocalLocation();
		if (anchor == null) anchor = center;
		if (anchor == null || center == null) return null;
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setClip(c);
		g.setStroke(new BasicStroke(config.minimapLineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		if (showChart) drawRoute(g, top, plugin.route());
		if (showCourse || showProjected) {
			int from = plugin.heading(ship);
			int course = plugin.course(ship);
			int mouse = showProjected ? hoverHeading(top, center, m, c) : -1;
			ChartPlotterProjection.Path cur = showCourse ? projection.path(top, ship.getConfig(), anchor, from, course, courseMode.blocked) : null;
			ChartPlotterProjection.Path pot = null;
			if (mouse >= 0) pot = projection.path(top, ship.getConfig(), anchor, from, mouse, projectedMode.blocked);
			int skip = cur != null && pot != null ? ChartPlotterProjection.match(cur, pot) : 0;
			if (cur != null) draw(g, top, cur, config.lineColor(), skip);
			if (pot != null) draw(g, top, pot, config.potentialColor(), 0);
		}
		g.setStroke(oldStroke);
		g.setClip(oldClip);
		return null;
	}
	public boolean overMinimap(Point p) {return p != null && clip != null && clip.contains(p.getX(), p.getY());}
	public static int mouseHeading(Client client, LocalPoint anchor, Point mouse) {
		if (mouse == null) return -1;
		Widget w = minimap(client);
		if (w == null || w.isHidden()) return -1;
		return mouseHeading(client, anchor, mouse, w, clip(client, w));
	}
	private static int mouseHeading(Client client, LocalPoint anchor, Point mouse, Widget w, Shape clip) {
		if (mouse == null || !clip.contains(mouse.getX(), mouse.getY())) return -1;
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
		return ChartPlotterMath.norm((int) Math.round((270 - d) / 360 * 16) * 128);
	}
	static Widget minimap(Client client) {
		if (client.isResized()) {
			if (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1) return client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
			return client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
		}
		return client.getWidget(InterfaceID.Toplevel.MINIMAP);
	}
	private void draw(Graphics2D g, WorldView wv, ChartPlotterProjection.Path p, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) {
			if (p.blocked && p.n == 1 && skip < p.n) drawBlock(g, wv, p, color);
			return;
		}
		int start = skip > 0 ? skip - 1 : 0;
		int mid = Math.min(p.blockedAt, p.n);
		segment(g, wv, p, color, start, mid);
		if (mid < p.n) segment(g, wv, p, config.blockedColor(), Math.max(start, mid - 1), p.n);
	}
	private void segment(Graphics2D g, WorldView wv, ChartPlotterProjection.Path p, Color color, int from, int to) {
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
	private void drawBlock(Graphics2D g, WorldView wv, ChartPlotterProjection.Path p, Color color) {
		Point q = Perspective.localToMinimap(client, new LocalPoint(p.x[0], p.y[0], wv), DIST);
		if (q == null) return;
		int r = 5;
		g.setColor(color);
		g.drawLine(q.getX() - r, q.getY() - r, q.getX() + r, q.getY() + r);
		g.drawLine(q.getX() + r, q.getY() - r, q.getX() - r, q.getY() + r);
	}
	private void drawRoute(Graphics2D g, WorldView wv, ChartPlotterRoute r) {
		if (r == null || r.status != ChartPlotterRoute.OK || r.n < 2) return;
		Stroke old = g.getStroke();
		Stroke dash = new BasicStroke(config.minimapLineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, DASH, 0);
		g.setColor(config.chartColor());
		double speed = ChartPlotterRouteMoves.speedBucket(plugin.speed());
		for (int i = 1; i < r.n; i++) routeLine(g, wv, r.x[i - 1], r.y[i - 1], r.x[i], r.y[i], speed, old, dash);
		g.setStroke(old);
	}
	private void routeLine(Graphics2D g, WorldView wv, int ax, int ay, int bx, int by, double speed, Stroke solid, Stroke dash) {
		Point a = routePoint(wv, ax, ay);
		Point b = routePoint(wv, bx, by);
		if (a == null || b == null) return;
		g.setStroke(routeSolid(ax, ay, bx, by, speed) ? solid : dash);
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private Point routePoint(WorldView wv, int wx, int wy) {
		int lx = (wx - wv.getBaseX()) * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_TILE_SIZE / 2;
		int ly = (wy - wv.getBaseY()) * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_TILE_SIZE / 2;
		return Perspective.localToMinimap(client, new LocalPoint(lx, ly, wv), DIST);
	}
	private static boolean routeSolid(int ax, int ay, int bx, int by, double speed) {return speed <= 0 || ChartPlotterRouteMoves.model(bx - ax, by - ay, speed);}
	private int hoverHeading(WorldView wv, LocalPoint anchor, Widget w, Shape clip) {
		Point m = ChartPlotterOverlay.eligibleMouse(client, plugin);
		if (m == null) return -1;
		if (wv.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return -1;
		return mouseHeading(client, anchor, m, w, clip);
	}
	private Shape clip(Widget minimap) {
		ClipKey k = new ClipKey(client, minimap);
		if (k.same(clipKey)) return cachedClip;
		Shape c = clip(client, minimap);
		clipKey = k;
		cachedClip = c;
		return c;
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
	private static final class ClipKey {
		final int[] v;
		private ClipKey(Client client, Widget minimap) {
			v = new int[5 + ORBS.length * 5];
			int i = 0;
			java.awt.Rectangle b = minimap.getBounds();
			v[i++] = b.x;
			v[i++] = b.y;
			v[i++] = b.width;
			v[i++] = b.height;
			v[i++] = client.isResized() ? 1 : 0;
			for (int id : ORBS) {
				Widget w = client.getWidget(id);
				if (w == null || w.isHidden()) {
					v[i++] = 0;
					i += 4;
					continue;
				}
				b = w.getBounds();
				v[i++] = 1;
				v[i++] = b.x;
				v[i++] = b.y;
				v[i++] = b.width;
				v[i++] = b.height;
			}
		}
		boolean same(ClipKey k) {return k != null && Arrays.equals(v, k.v);}
	}
}
