package com.chartplotter.overlay;

import com.chartplotter.*;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRouteMoves;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.runtime.ChartPlotterProjection;
import com.chartplotter.runtime.ChartPlotterScene;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.util.ColorUtil;

import javax.inject.Inject;
import java.awt.*;
import java.awt.geom.Path2D;

public class ChartPlotterOverlay extends Overlay {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int TURN = 128;
	private static final double FADE = 0.72;
	private static final double TICK = 0.6;
	private static final float[] DASH = {8, 6};
	private static final Stroke CACHE_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterScene scene;
	private final ChartPlotterProjection projection;
	private final ChartPlotterStrokeCache routeStroke = new ChartPlotterStrokeCache(BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, DASH);
	private final float[] z = new float[4];
	private final int[] cx = new int[4];
	private final int[] cy = new int[4];
	private final int[] px = new int[4];
	private final int[] py = new int[4];
	private final Path2D.Double path = new Path2D.Double();
	private final Path2D.Double line = new Path2D.Double();
	private int etaX = Integer.MIN_VALUE;
	private int etaY = Integer.MIN_VALUE;
	private int etaSeconds = -1;
	private boolean etaEnd;
	private long etaTick = Long.MIN_VALUE;
	@Inject
	ChartPlotterOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterCollisionCache collisionCache, ChartPlotterScene scene, ChartPlotterProjection projection) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.collisionCache = collisionCache;
		this.scene = scene;
		this.projection = projection;
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}
	@Override
	public Dimension render(Graphics2D g) {
		if (!plugin.isSailing()) return null;
		WorldView top = plugin.top();
		boolean active = plugin.courseLine(top);
		ChartPlotterLineMode courseMode = config.worldLineMode();
		ChartPlotterLineMode projectedMode = config.worldProjectedLineMode();
		boolean showCourse = active && courseMode.on;
		boolean showProjected = active && projectedMode.on;
		boolean showChart = config.worldChartLine();
		boolean showWorld = showCourse || showProjected || showChart;
		ChartPlotterTurnEta turnEta = config.courseTurnEta();
		boolean showTurn = turnEta != ChartPlotterTurnEta.OFF;
		ChartPlotterCacheOverlay cacheOverlay = config.cacheOverlay();
		if (!showWorld && !cacheOverlay.world && !showTurn) return null;
		if (top == null) return null;
		ChartPlotterScene.Area area = scene.cached(top);
		if (cacheOverlay.world) drawCache(g, top, area);
		if (!showWorld && !showTurn) return null;
		WorldEntity ship = plugin.getShip();
		if (ship == null) return null;
		LocalPoint anchor = plugin.anchorLoc(ship);
		LocalPoint center = ship.getLocalLocation();
		if (anchor == null || center == null) return null;
		if (showWorld) {
			Stroke prev = g.getStroke();
			g.setStroke(routeStroke.solid(config.worldLineWidth()));
			if (showChart) drawRoute(g, top, plugin.route(), area);
			if (showCourse || showProjected) {
				WorldEntityConfig wc = ship.getConfig();
				float[] rx = ChartPlotterProjection.rectX(wc);
				float[] ry = ChartPlotterProjection.rectY(wc);
				int from = plugin.heading(ship);
				int course = plugin.course(ship);
				int mouse = showProjected ? hoverHeading(top, center) : -1;
				ChartPlotterProjection.Path cur = showCourse ? projection.path(top, wc, anchor, from, course, courseMode.blocked) : null;
				ChartPlotterProjection.Path pot = mouse >= 0 ? projection.path(top, wc, anchor, from, mouse, projectedMode.blocked) : null;
				int skip = cur != null && pot != null ? ChartPlotterProjection.match(cur, pot) : 0;
				if (cur != null) draw(g, top, cur, rx, ry, config.lineColor(), skip);
				if (pot != null) draw(g, top, pot, rx, ry, config.potentialColor(), 0);
			}
			g.setStroke(prev);
		}
		if (showTurn) drawNextTurn(g, top, area, center, turnEta);
		return null;
	}
	private void draw(Graphics2D g, WorldView wv, ChartPlotterProjection.Path p, float[] rx, float[] ry, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) {
			if (p.blocked && p.n == 1 && skip < p.n) drawBlock(g, wv, p, rx, ry, color);
			return;
		}
		boolean have = false;
		int sA = color.getAlpha();
		Color blockedColor = config.blockedColor();
		int sBA = blockedColor.getAlpha();
		for (int i = 0; i < p.n; i++) {
			if (missing(wv, p, i, rx, ry, z, cx, cy)) {
				have = false;
				continue;
			}
			if (i < skip) {
				copy(cx, cy, px, py);
				have = true;
				continue;
			}
			boolean inBlock = i >= p.blockedAt;
			Color base = inBlock ? blockedColor : color;
			int a = alpha(inBlock ? sBA : sA, i, p.n);
			boolean slide = p.slid[i];
			if (a > 0) {
				g.setColor(ColorUtil.colorWithAlpha(base, a));
				path.reset();
				boolean d = false;
				if (have && p.o[i] == p.prev(i)) {
					rails(path, px, py, cx, cy, p.reverse);
					d = true;
				}
				if (box(p, i) || slide || i == 0) {
					box(path, cx, cy, open(p, i) && !slide, p.reverse);
					d = true;
				}
				if (d) g.draw(path);
			}
			copy(cx, cy, px, py);
			have = true;
		}
	}
	private void drawBlock(Graphics2D g, WorldView wv, ChartPlotterProjection.Path p, float[] rx, float[] ry, Color color) {
		if (missing(wv, p, 0, rx, ry, z, cx, cy)) return;
		path.reset();
		box(path, cx, cy, false, false);
		path.moveTo(cx[0], cy[0]);
		path.lineTo(cx[2], cy[2]);
		path.moveTo(cx[1], cy[1]);
		path.lineTo(cx[3], cy[3]);
		g.setColor(color);
		g.draw(path);
	}
	private void drawNextTurn(Graphics2D g, WorldView wv, ChartPlotterScene.Area area, LocalPoint center, ChartPlotterTurnEta mode) {
		if (area == null) return;
		double bx = wv.getBaseX() + center.getX() / (double) TS;
		double by = wv.getBaseY() + center.getY() / (double) TS;
		double speed = plugin.reversing() ? -plugin.speed() : plugin.speed();
		ChartPlotterRoutes.Turn turn = ChartPlotterRoutes.turn(plugin.route(), bx, by, speed, plugin.accel(), plugin.maxSpeed(), plugin.motionTime());
		if (!turn.valid) {
			resetEta();
			return;
		}
		Point at = edge(wv, area, center.getX(), center.getY(), (turn.x - wv.getBaseX()) * TS + TS / 2, (turn.y - wv.getBaseY()) * TS + TS / 2);
		if (at == null) return;
		String p = turn.end ? "Destination" : "Turn";
		String s;
		if (turn.ticks < 0) {
			resetEta();
			s = p + " ahead";
		} else if (mode == ChartPlotterTurnEta.TICKS) {
			resetEta();
			s = p + " in " + turn.ticks + "t";
		} else s = p + " in " + seconds(turn) + "s";
		Color c = config.chartColor();
		g.setColor(c);
		g.fillOval(at.getX() - 3, at.getY() - 3, 6, 6);
		TextComponent t = new TextComponent();
		t.setText(s);
		t.setColor(c);
		t.setPosition(new java.awt.Point(at.getX() - g.getFontMetrics().stringWidth(s) / 2, at.getY() - 8));
		t.render(g);
	}
	private int seconds(ChartPlotterRoutes.Turn turn) {
		if (turn.x != etaX || turn.y != etaY || turn.end != etaEnd || turn.updated != etaTick) {
			etaX = turn.x;
			etaY = turn.y;
			etaSeconds = Math.max(0, (int) Math.ceil(turn.ticks * TICK));
			etaEnd = turn.end;
			etaTick = turn.updated;
		}
		return etaSeconds;
	}
	private void resetEta() {
		etaX = Integer.MIN_VALUE;
		etaY = Integer.MIN_VALUE;
		etaSeconds = -1;
		etaEnd = false;
		etaTick = Long.MIN_VALUE;
	}
	private Point edge(WorldView wv, ChartPlotterScene.Area area, int sx, int sy, int ex, int ey) {
		int ax = ex;
		int ay = ey;
		if (area.missing(ex, ey)) {
			double lo = 0;
			double hi = 1;
			for (int i = 0; i < 16; i++) {
				double mid = (lo + hi) / 2;
				if (area.missing((int) (sx + (ex - sx) * mid), (int) (sy + (ey - sy) * mid))) hi = mid;
				else lo = mid;
			}
			ax = (int) (sx + (ex - sx) * lo);
			ay = (int) (sy + (ey - sy) * lo);
		}
		return Perspective.localToCanvas(client, new LocalPoint(ax, ay, wv), 0);
	}
	private void drawRoute(Graphics2D g, WorldView wv, ChartPlotterRoute r, ChartPlotterScene.Area area) {
		if (r == null || r.status != ChartPlotterRoute.OK || r.n < 2 || area == null) return;
		Stroke old = g.getStroke();
		Stroke solid = routeStroke.solid(config.worldLineWidth());
		Stroke dash = routeStroke.dashed(config.worldLineWidth());
		g.setColor(config.chartColor());
		double speed = ChartPlotterRouteMoves.speedBucket(plugin.speed());
		for (int i = 1; i < r.n; i++) {
			line.reset();
			routeSegment(line, wv, area, r.x[i - 1], r.y[i - 1], r.x[i], r.y[i]);
			g.setStroke(ChartPlotterRouteMoves.solid(r.x[i - 1], r.y[i - 1], r.x[i], r.y[i], speed) ? solid : dash);
			g.draw(line);
		}
		g.setStroke(old);
	}
	private void routeSegment(Path2D.Double line, WorldView wv, ChartPlotterScene.Area area, int ax, int ay, int bx, int by) {
		double x0 = ax + 0.5;
		double y0 = ay + 0.5;
		double dx = bx - ax;
		double dy = by - ay;
		double t0 = 0;
		double t1 = 1;
		double minX = area.minWX();
		double minY = area.minWY();
		double maxX = area.maxWX();
		double maxY = area.maxWY();
		if (dx == 0) {
			if (x0 < minX || x0 > maxX) return;
		} else {
			double a = (minX - x0) / dx;
			double b = (maxX - x0) / dx;
			if (a > b) {
				double c = a;
				a = b;
				b = c;
			}
			if (a > t0) t0 = a;
			if (b < t1) t1 = b;
			if (t0 > t1) return;
		}
		if (dy == 0) {
			if (y0 < minY || y0 > maxY) return;
		} else {
			double a = (minY - y0) / dy;
			double b = (maxY - y0) / dy;
			if (a > b) {
				double c = a;
				a = b;
				b = c;
			}
			if (a > t0) t0 = a;
			if (b < t1) t1 = b;
			if (t0 > t1) return;
		}
		int n = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) * (t1 - t0));
		if (n < 1) n = 1;
		boolean have = false;
		for (int i = 0; i <= n; i++) {
			double t = t0 + (t1 - t0) * i / n;
			have = routePoint(line, wv, area, x0 + dx * t, y0 + dy * t, have);
		}
	}
	private boolean routePoint(Path2D.Double line, WorldView wv, ChartPlotterScene.Area area, double wx, double wy, boolean have) {
		Point q = routeCanvas(wv, area, wx, wy);
		if (q == null) return false;
		if (have) line.lineTo(q.getX(), q.getY());
		else {
			line.moveTo(q.getX(), q.getY());
		}
		return true;
	}
	private Point routeCanvas(WorldView wv, ChartPlotterScene.Area area, double wx, double wy) {
		int lx = (int) Math.round((wx - wv.getBaseX()) * TS);
		int ly = (int) Math.round((wy - wv.getBaseY()) * TS);
		if (area.missing(lx, ly)) return null;
		return Perspective.localToCanvas(client, new LocalPoint(lx, ly, wv), 0);
	}
	private void drawCache(Graphics2D g, WorldView wv, ChartPlotterScene.Area area) {
		if (area == null) return;
		Stroke old = g.getStroke();
		ChartPlotterCollisionData data = collisionCache.snapshot();
		g.setStroke(CACHE_STROKE);
		g.setColor(new Color(0, 210, 120, 150));
		for (int i = 0; i < area.n; i++) {
			int cx = area.cx[i];
			int cy = area.cy[i];
			if (data.uncached(cx, cy)) continue;
			int wx = cx << 3;
			int wy = cy << 3;
			int x0 = Math.max(wx, area.minWX());
			int y0 = Math.max(wy, area.minWY());
			int x1 = Math.min(wx + 8, area.maxWX());
			int y1 = Math.min(wy + 8, area.maxWY());
			if (x0 >= x1 || y0 >= y1) continue;
			if (area.chunk(cx - 1, cy) && data.uncached(cx - 1, cy)) drawCacheEdge(g, wv, x0, y0, x0, y1);
			if (area.chunk(cx + 1, cy) && data.uncached(cx + 1, cy)) drawCacheEdge(g, wv, x1, y0, x1, y1);
			if (area.chunk(cx, cy - 1) && data.uncached(cx, cy - 1)) drawCacheEdge(g, wv, x0, y0, x1, y0);
			if (area.chunk(cx, cy + 1) && data.uncached(cx, cy + 1)) drawCacheEdge(g, wv, x0, y1, x1, y1);
		}
		g.setStroke(old);
	}
	private void drawCacheEdge(Graphics2D g, WorldView wv, int ax, int ay, int bx, int by) {
		Point a = cachePoint(wv, ax, ay);
		Point b = cachePoint(wv, bx, by);
		if (a == null || b == null) return;
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private Point cachePoint(WorldView wv, int wx, int wy) {
		return Perspective.localToCanvas(client, new LocalPoint((wx - wv.getBaseX()) * TS, (wy - wv.getBaseY()) * TS, wv), 0);
	}
	private boolean missing(WorldView wv, ChartPlotterProjection.Path p, int i, float[] rx, float[] ry, float[] z, int[] cx, int[] cy) {
		Perspective.modelToCanvas(client, wv, 4, p.x[i], p.y[i], 0, p.o[i], rx, ry, z, cx, cy);
		return cx[0] == Integer.MIN_VALUE || cx[1] == Integer.MIN_VALUE || cx[2] == Integer.MIN_VALUE || cx[3] == Integer.MIN_VALUE;
	}
	private static void rails(Path2D p, int[] px, int[] py, int[] cx, int[] cy, boolean reverse) {
		int a = reverse ? 1 : 0;
		int b = reverse ? 2 : 3;
		p.moveTo(px[a], py[a]);
		p.lineTo(cx[a], cy[a]);
		p.moveTo(px[b], py[b]);
		p.lineTo(cx[b], cy[b]);
	}
	private static void box(Path2D p, int[] x, int[] y, boolean open, boolean reverse) {
		if (open && reverse) {
			p.moveTo(x[0], y[0]);
			p.lineTo(x[1], y[1]);
			p.moveTo(x[2], y[2]);
			p.lineTo(x[3], y[3]);
			p.lineTo(x[0], y[0]);
			return;
		}
		p.moveTo(x[0], y[0]);
		p.lineTo(x[1], y[1]);
		p.lineTo(x[2], y[2]);
		p.lineTo(x[3], y[3]);
		if (!open) p.lineTo(x[0], y[0]);
	}
	private static boolean box(ChartPlotterProjection.Path p, int i) {return p.o[i] != p.prev(i);}
	private static boolean open(ChartPlotterProjection.Path p, int i) {return i + 1 < p.n && !p.slid[i + 1] && p.o[i + 1] == p.o[i];}
	private static void copy(int[] sx, int[] sy, int[] dx, int[] dy) {
		for (int i = 0; i < 4; i++) {
			dx[i] = sx[i];
			dy[i] = sy[i];
		}
	}
	private static int alpha(int a, int i, int n) {
		double f = (i + 1) / (double) n;
		if (f <= FADE) return a;
		double t = (f - FADE) / (1 - FADE);
		return (int) (a * (1 - t));
	}
	private int hoverHeading(WorldView wv, LocalPoint anchor) {
		Point m = eligibleMouse(client, plugin);
		if (m == null) return -1;
		if (outsideViewport(client, m) || headingInactive(client, wv)) return -1;
		return sceneHeading(client, wv, anchor, m);
	}
	public static Point eligibleMouse(Client client, ChartPlotterPlugin plugin) {
		Point m = client.getMouseCanvasPosition();
		if (m == null || client.getCanvas().getMousePosition() == null || client.isMenuOpen()) return null;
		return plugin.suppressPotential(m) ? null : m;
	}
	public static boolean headingInactive(Client client, WorldView wv) {
		if (wv.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return true;
		MenuEntry[] es = client.getMenu().getMenuEntries();
		return es.length == 0 || es[es.length - 1].getType() != MenuAction.SET_HEADING;
	}
	public static boolean outsideViewport(Client client, Point m) {
		int x = client.getViewportXOffset();
		int y = client.getViewportYOffset();
		return m.getX() < x || m.getY() < y || m.getX() >= x + client.getViewportWidth() || m.getY() >= y + client.getViewportHeight();
	}
	public static int mouseHeading(Client client, WorldView wv, LocalPoint anchor, Point mouse) {
		int mini = ChartPlotterMinimapOverlay.mouseHeading(client, anchor, mouse);
		if (mini >= 0) return mini;
		return sceneHeading(client, wv, anchor, mouse);
	}
	private static int sceneHeading(Client client, WorldView wv, LocalPoint anchor, Point mouse) {
		Point center = Perspective.localToCanvas(client, anchor, 0);
		if (center == null || mouse == null) return -1;
		int n = 16;
		int[] lx = new int[n];
		int[] ly = new int[n];
		float[] x = new float[]{0, 0};
		float[] y = new float[]{1000, -1000};
		float[] z = new float[]{0, 0};
		int[] cx = new int[2];
		int[] cy = new int[2];
		for (int i = 0; i < n; i++) {
			cx[0] = 0;
			cx[1] = 0;
			cy[0] = 0;
			cy[1] = 0;
			Perspective.modelToCanvas(client, wv, 2, anchor.getX(), anchor.getY(), 0, 64 + TURN * i, x, y, z, cx, cy);
			if (cx[1] == Integer.MIN_VALUE) return -1;
			lx[i] = cx[1];
			ly[i] = cy[1];
		}
		for (int i = 0; i < n - 1; i++) {
			int a = side(center.getX(), center.getY(), lx[i], ly[i], mouse.getX(), mouse.getY());
			int b = side(center.getX(), center.getY(), lx[i + 1], ly[i + 1], mouse.getX(), mouse.getY());
			if (a >= 0 && b < 0) return TURN + i * TURN;
		}
		return 0;
	}
	private static int side(int x1, int y1, int x2, int y2, int x, int y) {return (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1);}
}
