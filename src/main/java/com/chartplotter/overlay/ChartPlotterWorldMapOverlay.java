package com.chartplotter.overlay;

import com.chartplotter.*;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRouteMoves;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.route.ChartPlotterTrip;
import com.chartplotter.runtime.ChartPlotterProjection;
import com.chartplotter.runtime.ChartPlotterWorldMap;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.Arrays;

public class ChartPlotterWorldMapOverlay extends Overlay {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final Color STATUS_UNCHARTED = new Color(255, 80, 60, 220);
	private static final Color STATUS_BLOCKED = new Color(170, 170, 170, 220);
	private static final Color STATUS_WARN = new Color(255, 190, 40, 220);
	private static final Color SPARSE_GLOW = new Color(255, 0, 200, 90);
	private static final Color SPARSE_LINE = new Color(255, 70, 230, 240);
	private static final Color SPARSE_RING = new Color(20, 20, 20, 190);
	private static final Color SPARSE_DOT = new Color(255, 80, 220, 240);
	private static final Color CACHE_EDGE = new Color(0, 210, 120, 150);
	private static final Color TIP_BG = new Color(20, 20, 20, 220);
	private static final Color PREVIEW_OK = new Color(80, 255, 120, 235);
	private static final Color PREVIEW_SNAP = new Color(255, 200, 40, 235);
	private static final Color PREVIEW_BAD = new Color(255, 70, 60, 235);
	private static final Color REMOVE = new Color(255, 70, 60, 235);
	private static final float[] DASH = {8, 6};
	private static final long TIP_MS = 3000;
	private static final int STOP_HIT_RADIUS = 10;
	private static final Stroke CACHE_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
	private static final Stroke SPARSE_STROKE = new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterProjection projection;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterWorldMap map;
	private final ChartPlotterNodeEditor editor;
	private final ChartPlotterStrokeCache routeStroke = new ChartPlotterStrokeCache(BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, DASH);
	private Stroke sparseStroke;
	private float sparseWidth = Float.NaN;
	private volatile boolean ctrl;
	private volatile boolean shift;
	private volatile int draggedStop = -1;
	private volatile int draggedX;
	private volatile int draggedY;
	private volatile Point draggedPoint;
	private volatile StopCache stopCache = StopCache.EMPTY;
	private double routeSpeed = Double.NaN;
	private ChartPlotterRouteMoves.Model routeModel;
	private final float[] boxX = new float[4];
	private final float[] boxY = new float[4];
	private final Path2D.Double projectionLine = new Path2D.Double();
	private final Path2D.Double boxPath = new Path2D.Double();
	private final Path2D.Double sparsePath = new Path2D.Double();
	private final Ellipse2D.Double ellipse = new Ellipse2D.Double();
	private final int[] colorKey = new int[8];
	private final Color[][] colorCache = new Color[colorKey.length][];
	private final String[] stopLabels = new String[ChartPlotterRoutes.MAX_STOPS];
	private final int[] tile = new int[2];
	private int colorNext;
	private boolean previewActive;
	@Inject
	ChartPlotterWorldMapOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterProjection projection, ChartPlotterCollisionCache collisionCache, ChartPlotterWorldMap map, ChartPlotterNodeEditor editor) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.projection = projection;
		this.collisionCache = collisionCache;
		this.map = map;
		this.editor = editor;
		setLayer(OverlayLayer.MANUAL);
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}
	@Override
	public Dimension render(Graphics2D g) {
		boolean sailing = plugin.isSailing();
		boolean edit = config.nodeEditor();
		boolean showChart = config.worldMapChartLine();
		ChartPlotterTrip trip = plugin.trip();
		boolean showRoute = showChart && !trip.empty();
		if (!showRoute) stopCache = StopCache.EMPTY;
		if (!sailing && !edit && !showRoute) {
			map.clickBlocked();
			return null;
		}
		WorldView top = sailing ? plugin.top() : null;
		boolean active = plugin.courseLine(top);
		ChartPlotterLineMode courseMode = config.worldMapLineMode();
		ChartPlotterLineMode projectedMode = config.worldMapProjectedLineMode();
		boolean showCourse = active && courseMode.on;
		boolean showProjected = active && projectedMode.on;
		boolean courseGesture = config.worldMapCourseClick() == ChartPlotterWorldMapClick.CLICK || ctrl;
		boolean append = sailing && shift && courseGesture;
		boolean showPreview = sailing && showChart && courseGesture && (ctrl || shift);
		if (previewActive != showPreview) {
			previewActive = showPreview;
			if (!showPreview) plugin.clearCoursePreview();
		}
		ChartPlotterCacheOverlay cacheOverlay = config.cacheOverlay();
		if (!showCourse && !showProjected && !showRoute && !cacheOverlay.worldMap && !edit && !showPreview) {
			map.clickBlocked();
			return null;
		}
		ChartPlotterWorldMap.State s = map.state();
		if (s == null) {
			stopCache = StopCache.EMPTY;
			return null;
		}
		Shape clip = map.clip(s);
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setClip(clip);
		try {
			if (sailing && cacheOverlay.worldMap) drawCache(g, s);
			if (edit) editor.draw(g, s);
			if (showRoute || showPreview || showCourse || showProjected) g.setStroke(routeStroke.solid(config.worldMapLineWidth()));
			if (showRoute) {
				cacheStops(s, clip, trip);
				drawTrip(g, s, clip, trip, shift);
			}
			if (!sailing) return null;
			if (showPreview) drawCoursePreview(g, s, clip, append);
			if (!showCourse && !showProjected) return null;
			WorldEntity ship = plugin.getShip();
			if (ship == null || top == null) return null;
			LocalPoint anchor = plugin.anchorLoc(ship);
			LocalPoint center = ship.getLocalLocation();
			if (anchor == null || center == null) return null;
			int from = plugin.heading(ship);
			int course = plugin.course(ship);
			int mouse = showProjected && !showPreview ? hoverHeading(top, center, s, clip) : -1;
			int cap = map.pathCap(top, anchor, s);
			ChartPlotterProjection.Path cur = showCourse ? projection.path(top, ship.getConfig(), anchor, from, course, cap, courseMode.blocked) : null;
			ChartPlotterProjection.Path pot = null;
			if (mouse >= 0) pot = projection.path(top, ship.getConfig(), anchor, from, mouse, cap, projectedMode.blocked);
			int skip = cur != null && pot != null ? ChartPlotterProjection.match(cur, pot) : 0;
			int baseX = top.getBaseX();
			int baseY = top.getBaseY();
			if (cur != null) draw(g, s, baseX, baseY, cur, ship.getConfig(), config.lineColor(), skip);
			if (pot != null) draw(g, s, baseX, baseY, pot, ship.getConfig(), config.potentialColor(), 0);
			return null;
		} finally {
			g.setStroke(oldStroke);
			g.setClip(oldClip);
		}
	}
	public int[] tile(Point m) {return map.tile(m);}
	public int stop(Point m) {
		if (!config.worldMapChartLine()) return -1;
		ChartPlotterWorldMap.State s = map.state();
		return s == null ? -1 : stop(m, s, map.clip(s), plugin.trip());
	}
	public int[] cachedStop(Point m) {
		StopCache cache = stopCache;
		if (m == null || cache.clip == null || !cache.clip.contains(m.getX(), m.getY())) return null;
		int best = -1;
		int bd = STOP_HIT_RADIUS * STOP_HIT_RADIUS + 1;
		for (int i = cache.hits.length - 5; i >= 0; i -= 5) {
			int dx = cache.hits[i + 3] - m.getX();
			int dy = cache.hits[i + 4] - m.getY();
			int d = dx * dx + dy * dy;
			if (d >= bd) continue;
			best = i;
			bd = d;
		}
		return best < 0 ? null : new int[]{cache.hits[best], cache.hits[best + 1], cache.hits[best + 2]};
	}
	public boolean clickBlocked() {return map.clickBlocked();}
	public boolean cachedClickBlocked() {return map.cachedClickBlocked();}
	public int[] node(Point m) {return editor.node(m);}
	public void editNode(Point m) {editor.edit(m);}
	public void removeNode(int wx, int wy) {editor.remove(wx, wy);}
	public void placeNode(Point m) {editor.place(m);}
	public boolean movingNode() {return editor.moving();}
	public void nodeAlt(boolean on) {editor.alt(on);}
	public void clearEditor() {editor.clear();}
	public void courseMods(boolean ctrl, boolean shift) {
		if (this.ctrl == ctrl && this.shift == shift) return;
		this.ctrl = ctrl;
		this.shift = shift;
	}
	public void dragStop(int stop, int x, int y, Point point) {
		draggedPoint = point;
		draggedX = x;
		draggedY = y;
		draggedStop = stop;
	}
	public void clearStopDrag() {
		draggedStop = -1;
		draggedPoint = null;
	}
	private void draw(Graphics2D g, ChartPlotterWorldMap.State s, int baseX, int baseY, ChartPlotterProjection.Path p, WorldEntityConfig wc, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) {
			if (p.blocked && p.n == 1 && skip < p.n) drawBlock(g, s, baseX, baseY, p, color);
			return;
		}
		int start = skip > 0 ? skip - 1 : 0;
		int mid = Math.min(p.blockedAt, p.n);
		segment(g, s, baseX, baseY, p, color, start, mid);
		boxes(g, s, baseX, baseY, p, wc, color, start, mid);
		if (mid < p.n) {
			segment(g, s, baseX, baseY, p, config.blockedColor(), Math.max(start, mid - 1), p.n);
			boxes(g, s, baseX, baseY, p, wc, config.blockedColor(), mid, p.n);
		}
	}
	private void boxes(Graphics2D g, ChartPlotterWorldMap.State s, int baseX, int baseY, ChartPlotterProjection.Path p, WorldEntityConfig wc, Color color, int from, int to) {
		if (!config.sailingSlide()) return;
		ChartPlotterProjection.rect(wc, boxX, boxY);
		double radius = 0;
		for (int i = 0; i < boxX.length; i++) radius = Math.max(radius, Math.hypot(boxX[i], boxY[i]));
		int pad = (int) Math.ceil(radius / TS) + 2;
		g.setColor(color);
		for (int i = from; i < to; i++) {
			if (!box(p, i)) continue;
			int sx = Math.floorDiv(p.x[i], TS);
			int sy = Math.floorDiv(p.y[i], TS);
			if (!pointVisible(s, baseX + sx, baseY + sy, pad)) continue;
			if (!s.data.surfaceContainsPosition(baseX + sx, baseY + sy)) continue;
			g.draw(box(s, baseX, baseY, p, boxX, boxY, i));
		}
	}
	private Path2D.Double box(ChartPlotterWorldMap.State s, int baseX, int baseY, ChartPlotterProjection.Path p, float[] rx, float[] ry, int i) {
		boxPath.reset();
		for (int c = 0; c < 4; c++) {
			int lx = ChartPlotterMath.rotateX(p.x[i], p.o[i], (int) rx[c], (int) ry[c]);
			int ly = ChartPlotterMath.rotateY(p.y[i], p.o[i], (int) rx[c], (int) ry[c]);
			int px = map.mapX(s, baseX, lx);
			int py = map.mapY(s, baseY, ly);
			if (c == 0) boxPath.moveTo(px, py);
			else boxPath.lineTo(px, py);
		}
		boxPath.closePath();
		return boxPath;
	}
	private void segment(Graphics2D g, ChartPlotterWorldMap.State s, int baseX, int baseY, ChartPlotterProjection.Path p, Color color, int from, int to) {
		projectionLine.reset();
		int pad = linePad(s);
		boolean have = false;
		boolean prev = false;
		int plx = 0;
		int ply = 0;
		for (int i = from; i < to; i++) {
			int sx = Math.floorDiv(p.x[i], TS);
			int sy = Math.floorDiv(p.y[i], TS);
			boolean visible = pointVisible(s, baseX + sx, baseY + sy, pad);
			if (!visible && !have) {
				plx = p.x[i];
				ply = p.y[i];
				prev = true;
				continue;
			}
			if (!s.data.surfaceContainsPosition(baseX + sx, baseY + sy)) {
				have = false;
				prev = false;
				continue;
			}
			if (visible) {
				int x = map.mapX(s, baseX, p.x[i]);
				int y = map.mapY(s, baseY, p.y[i]);
				if (have) projectionLine.lineTo(x, y);
				else {
					if (prev && s.data.surfaceContainsPosition(baseX + Math.floorDiv(plx, TS), baseY + Math.floorDiv(ply, TS))) projectionLine.moveTo(map.mapX(s, baseX, plx), map.mapY(s, baseY, ply));
					else projectionLine.moveTo(x, y);
					projectionLine.lineTo(x, y);
					have = true;
				}
			} else {
				projectionLine.lineTo(map.mapX(s, baseX, p.x[i]), map.mapY(s, baseY, p.y[i]));
				have = false;
			}
			plx = p.x[i];
			ply = p.y[i];
			prev = true;
		}
		g.setColor(color);
		g.draw(projectionLine);
	}
	private static boolean box(ChartPlotterProjection.Path p, int i) {return p.o[i] != p.prev(i) || p.slid(i);}
	private void drawBlock(Graphics2D g, ChartPlotterWorldMap.State s, int baseX, int baseY, ChartPlotterProjection.Path p, Color color) {
		int sx = Math.floorDiv(p.x[0], TS);
		int sy = Math.floorDiv(p.y[0], TS);
		if (!pointVisible(s, baseX + sx, baseY + sy, (int) Math.ceil(5 / s.z) + 1)) return;
		if (!s.data.surfaceContainsPosition(baseX + sx, baseY + sy)) return;
		int x = map.mapX(s, baseX, p.x[0]);
		int y = map.mapY(s, baseY, p.y[0]);
		int r = 5;
		g.setColor(color);
		g.drawLine(x - r, y - r, x + r, y + r);
		g.drawLine(x + r, y - r, x - r, y + r);
	}
	private void drawTrip(Graphics2D g, ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip, boolean tail) {
		Point mouse = hover(clip);
		int moving = draggedStop >= 0 && draggedStop < trip.size() && trip.x(draggedStop) == draggedX && trip.y(draggedStop) == draggedY ? draggedStop : -1;
		Point drag = draggedPoint;
		int[] moved = moving >= 0 && map.tile(drag, s, tile) ? tile : null;
		boolean movedPoint = moving >= 0 && drag != null;
		int movedPX = !movedPoint ? 0 : moved == null ? drag.getX() : map.pointX(s, moved[0], 0.5);
		int movedPY = !movedPoint ? 0 : moved == null ? drag.getY() : map.pointY(s, moved[1], 0.5);
		int remove = moving >= 0 ? -1 : cachedStopIndex(mouse);
		ChartPlotterRouteMoves.Model model = routeModel();
		for (int i = 0; i < trip.size(); i++) {
			ChartPlotterRoute r = trip.route(i);
			if (r == null || r.status != ChartPlotterRoute.OK) continue;
			if (config.sparseRouteDebug() && r.sparseN > 1) drawSparseRoute(g, s, r);
			boolean removing = remove >= 0 && (tail ? i >= remove : i == remove || i == remove + 1);
			drawRoutePath(g, s, r, removing ? REMOVE : routeColor(r, i > 0), model);
		}
		if (movedPoint) {
			Stroke stroke = g.getStroke();
			g.setStroke(routeStroke.dashed(config.worldMapLineWidth()));
			g.setColor(moved == null ? PREVIEW_BAD : PREVIEW_SNAP);
			g.drawLine(map.pointX(s, trip.x(moving), 0.5), map.pointY(s, trip.y(moving), 0.5), movedPX, movedPY);
			g.setStroke(stroke);
		}
		long now = System.currentTimeMillis();
		for (int i = 0; i < trip.size(); i++) {
			ChartPlotterRoute r = trip.route(i);
			int px = i == moving && movedPoint ? movedPX : map.pointX(s, trip.x(i), 0.5);
			int py = i == moving && movedPoint ? movedPY : map.pointY(s, trip.y(i), 0.5);
			boolean removing = remove >= 0 && (tail ? i >= remove : i == remove);
			Color c = i == moving ? moved == null ? PREVIEW_BAD : PREVIEW_SNAP : removing ? REMOVE : routeColor(r, i > 0);
			marker(g, px, py, c);
			if (trip.size() > 1) label(g, px, py, i + 1, c);
			if (r != null && r.text() != null && (r.status == ChartPlotterRoute.PENDING || now - r.time < TIP_MS)) tip(g, s.r, px, py, r.text());
		}
		if (moving >= 0 && movedPoint) tip(g, s.r, movedPX, movedPY, moved == null ? "Release to cancel" : "Release to move stop " + (moving + 1));
		else if (remove >= 0) {
			tripTip(g, s.r, map.pointX(s, trip.x(remove), 0.5), map.pointY(s, trip.y(remove), 0.5), trip, remove);
		}
	}
	private void tripTip(Graphics2D g, Rectangle bounds, int x, int y, ChartPlotterTrip trip, int stop) {
		ChartPlotterRoute route = trip.route(stop);
		String status = route == null ? "Charting course" : route.text();
		if (!config.worldMapTripHints()) {
			if (status != null) tip(g, bounds, x, y, status);
			return;
		}
		int n = stop + 1;
		boolean tail = n < trip.size();
		boolean sailing = plugin.isSailing();
		boolean append = sailing && !tail && plugin.canAppend();
		String[] lines = new String[(status == null ? 0 : 1) + 1 + (tail || append ? 1 : 0) + (sailing ? 1 : 0)];
		int i = 0;
		if (status != null) lines[i++] = status;
		lines[i++] = trip.size() == 1 ? "Click: clear destination" : "Click: remove stop " + n;
		if (tail) lines[i++] = "Shift+click: remove stop " + n + " and later";
		else if (append) lines[i++] = "Shift+click elsewhere: add stop";
		if (sailing) lines[i] = trip.size() == 1 ? "Drag: move destination" : "Drag: move stop " + n;
		tip(g, bounds, x, y, lines);
	}
	private Color routeColor(ChartPlotterRoute r, boolean future) {
		Color c = r == null ? STATUS_WARN : r.status == ChartPlotterRoute.OK ? config.chartColor() : r.status == ChartPlotterRoute.UNCHARTED ? STATUS_UNCHARTED : r.status == ChartPlotterRoute.BLOCKED ? STATUS_BLOCKED : STATUS_WARN;
		return future ? faded(c) : c;
	}
	private void label(Graphics2D g, int px, int py, int n, Color c) {
		String text = stopLabels[n - 1];
		if (text == null) stopLabels[n - 1] = text = Integer.toString(n);
		int x = px + 10;
		int y = py + g.getFontMetrics().getAscent() / 2;
		g.setColor(alpha(Color.BLACK, c.getAlpha()));
		g.drawString(text, x + 1, y + 1);
		g.setColor(c);
		g.drawString(text, x, y);
	}
	private void drawRoutePath(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterRoute r, Color c, ChartPlotterRouteMoves.Model model) {
		if (r.n < 1) return;
		Stroke old = g.getStroke();
		Stroke solid = routeStroke.solid(config.worldMapLineWidth());
		Stroke dash = routeStroke.dashed(config.worldMapLineWidth());
		int pad = linePad(s);
		g.setColor(c);
		for (int i = 1; i < r.n; i++) routeLine(g, s, r.x[i - 1], r.y[i - 1], r.x[i], r.y[i], model, solid, dash, pad);
		if (r.x[r.n - 1] != r.tx || r.y[r.n - 1] != r.ty) {
			if (lineVisible(s, r.x[r.n - 1], r.y[r.n - 1], r.tx, r.ty, pad)) {
				g.setStroke(dash);
				g.setColor(faded(c));
				g.drawLine(map.pointX(s, r.x[r.n - 1], 0.5), map.pointY(s, r.y[r.n - 1], 0.5), map.pointX(s, r.tx, 0.5), map.pointY(s, r.ty, 0.5));
			}
		}
		g.setStroke(old);
	}
	private void routeLine(Graphics2D g, ChartPlotterWorldMap.State s, int ax, int ay, int bx, int by, ChartPlotterRouteMoves.Model model, Stroke solid, Stroke dash, int pad) {
		if (!lineVisible(s, ax, ay, bx, by, pad)) return;
		g.setStroke(ChartPlotterRouteMoves.solid(ax, ay, bx, by, model) ? solid : dash);
		g.drawLine(map.pointX(s, ax, 0.5), map.pointY(s, ay, 0.5), map.pointX(s, bx, 0.5), map.pointY(s, by, 0.5));
	}
	private ChartPlotterRouteMoves.Model routeModel() {
		double speed = ChartPlotterRouteMoves.speedBucket(plugin.speed());
		if (Double.doubleToLongBits(speed) != Double.doubleToLongBits(routeSpeed)) {
			routeSpeed = speed;
			routeModel = ChartPlotterRouteMoves.model(speed);
		}
		return routeModel;
	}
	private int linePad(ChartPlotterWorldMap.State s) {return (int) Math.ceil(config.worldMapLineWidth() / (s.z * 2)) + 1;}
	private Color faded(Color color) {return alpha(color, color.getAlpha() * 3 / 5);}
	private Color alpha(Color color, int alpha) {
		int key = color.getRGB() & 0xffffff;
		for (int i = 0; i < colorCache.length; i++) {
			Color[] cache = colorCache[i];
			if (cache == null || colorKey[i] != key) continue;
			Color result = cache[alpha];
			if (result == null) cache[alpha] = result = new Color(key | alpha << 24, true);
			return result;
		}
		int i = colorNext++ & colorCache.length - 1;
		colorKey[i] = key;
		Color[] cache = colorCache[i] = new Color[256];
		return cache[alpha] = new Color(key | alpha << 24, true);
	}
	private void drawSparseRoute(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterRoute r) {
		Path2D.Double line = sparsePath(s, r, r.sparseBand + (int) Math.ceil(2 / s.z) + 1);
		if (line == null) return;
		Stroke old = g.getStroke();
		g.setStroke(sparseStroke(sparseWidth(s, r.sparseBand)));
		g.setColor(SPARSE_GLOW);
		g.draw(line);
		g.setStroke(SPARSE_STROKE);
		g.setColor(SPARSE_LINE);
		g.draw(line);
		int pad = (int) Math.ceil(4 / s.z) + 1;
		for (int i = 0; i < r.sparseN; i++) if (pointVisible(s, r.sparseX[i], r.sparseY[i], pad)) sparseDot(g, s, r.sparseX[i], r.sparseY[i]);
		g.setStroke(old);
	}
	private Path2D.Double sparsePath(ChartPlotterWorldMap.State s, ChartPlotterRoute r, int pad) {
		sparsePath.reset();
		boolean have = false;
		boolean any = false;
		for (int i = 1; i < r.sparseN; i++) {
			if (!lineVisible(s, r.sparseX[i - 1], r.sparseY[i - 1], r.sparseX[i], r.sparseY[i], pad)) {
				have = false;
				continue;
			}
			if (!have) sparsePath.moveTo(map.pointX(s, r.sparseX[i - 1], 0.5), map.pointY(s, r.sparseY[i - 1], 0.5));
			sparsePath.lineTo(map.pointX(s, r.sparseX[i], 0.5), map.pointY(s, r.sparseY[i], 0.5));
			have = true;
			any = true;
		}
		return any ? sparsePath : null;
	}
	private void sparseDot(Graphics2D g, ChartPlotterWorldMap.State s, int wx, int wy) {
		int x = map.pointX(s, wx, 0.5);
		int y = map.pointY(s, wy, 0.5);
		g.setColor(SPARSE_RING);
		ellipse.setFrame(x - 4, y - 4, 8, 8);
		g.fill(ellipse);
		g.setColor(SPARSE_DOT);
		ellipse.setFrame(x - 3, y - 3, 6, 6);
		g.fill(ellipse);
		g.setColor(Color.RED);
		ellipse.setFrame(x - 1.5, y - 1.5, 3, 3);
		g.fill(ellipse);
	}
	private static float sparseWidth(ChartPlotterWorldMap.State s, int band) {
		return Math.max(1, band * 2f * s.z);
	}
	private Stroke sparseStroke(float w) {
		if (w == sparseWidth) return sparseStroke;
		sparseWidth = w;
		sparseStroke = new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		return sparseStroke;
	}
	private void drawCache(Graphics2D g, ChartPlotterWorldMap.State s) {
		Stroke old = g.getStroke();
		ChartPlotterCollisionData data = collisionCache.snapshot();
		int minWX = (int) Math.floor(s.pos.getX() - s.wt / 2.0) - 8;
		int minWY = (int) Math.floor(s.pos.getY() - s.ht / 2.0) - 8;
		int maxWX = (int) Math.ceil(s.pos.getX() + s.wt / 2.0) + 8;
		int maxWY = (int) Math.ceil(s.pos.getY() + s.ht / 2.0) + 8;
		int minCX = Math.floorDiv(minWX, 8);
		int minCY = Math.floorDiv(minWY, 8);
		int maxCX = Math.floorDiv(maxWX, 8);
		int maxCY = Math.floorDiv(maxWY, 8);
		long window = (long) (maxCX - minCX + 1) * (maxCY - minCY + 1);
		g.setStroke(CACHE_STROKE);
		g.setColor(CACHE_EDGE);
		if (window <= data.size()) drawCacheWindow(g, s, data, minCX, minCY, maxCX, maxCY, minWX, minWY, maxWX, maxWY);
		else drawCacheEntries(g, s, data, minWX, minWY, maxWX, maxWY);
		g.setStroke(old);
	}
	private void drawCacheWindow(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterCollisionData data, int minCX, int minCY, int maxCX, int maxCY, int minWX, int minWY, int maxWX, int maxWY) {
		for (int cx = minCX; cx <= maxCX; cx++) {
			for (int cy = minCY; cy <= maxCY; cy++) {
				ChartPlotterCollisionData.Chunk c = data.chunk(cx, cy);
				if (c == null || c.empty() || cacheChunkHidden(s, cx, cy, minWX, minWY, maxWX, maxWY)) continue;
				drawCacheChunk(g, s, data, cx, cy);
			}
		}
	}
	private void drawCacheEntries(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterCollisionData data, int minWX, int minWY, int maxWX, int maxWY) {
		for (int i = 0; i < data.capacity(); i++) {
			ChartPlotterCollisionData.Chunk chunk = data.chunkAt(i);
			if (chunk == null) continue;
			long key = data.keyAt(i);
			int cx = (int) (key >> 32);
			int cy = (int) key;
			if (cacheChunkHidden(s, cx, cy, minWX, minWY, maxWX, maxWY)) continue;
			drawCacheChunk(g, s, data, cx, cy);
		}
	}
	private void drawCacheChunk(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterCollisionData data, int cx, int cy) {
		int wx = cx << 3;
		int wy = cy << 3;
		if (data.uncached(cx - 1, cy)) drawCacheEdge(g, s, wx, wy, 0, 0, wx, wy + 7, 0, 1);
		if (data.uncached(cx + 1, cy)) drawCacheEdge(g, s, wx + 7, wy, 1, 0, wx + 7, wy + 7, 1, 1);
		if (data.uncached(cx, cy - 1)) drawCacheEdge(g, s, wx, wy, 0, 0, wx + 7, wy, 1, 0);
		if (data.uncached(cx, cy + 1)) drawCacheEdge(g, s, wx, wy + 7, 0, 1, wx + 7, wy + 7, 1, 1);
	}
	private boolean cacheChunkHidden(ChartPlotterWorldMap.State s, int cx, int cy, int minWX, int minWY, int maxWX, int maxWY) {
		int wx = cx << 3;
		int wy = cy << 3;
		return wx > maxWX || wx + 7 < minWX || wy > maxWY || wy + 7 < minWY || !s.data.surfaceContainsPosition(wx + 4, wy + 4);
	}
	private void drawCacheEdge(Graphics2D g, ChartPlotterWorldMap.State s, int ax, int ay, double afx, double afy, int bx, int by, double bfx, double bfy) {
		g.drawLine(map.pointX(s, ax, afx), map.pointY(s, ay, afy), map.pointX(s, bx, bfx), map.pointY(s, by, bfy));
	}
	private void tip(Graphics2D g, Rectangle r, int px, int py, String... lines) {
		FontMetrics fm = g.getFontMetrics();
		int w = 0;
		for (String line : lines) w = Math.max(w, fm.stringWidth(line));
		w += 10;
		int h = fm.getHeight() * lines.length + 6;
		int x = px + 12;
		int y = py - h - 8;
		if (x + w > r.x + r.width) x = px - w - 12;
		if (y < r.y) y = py + 12;
		x = Math.max(r.x + 4, Math.min(x, r.x + r.width - w - 4));
		y = Math.max(r.y + 4, Math.min(y, r.y + r.height - h - 4));
		g.setColor(TIP_BG);
		g.fillRect(x, y, w, h);
		g.setColor(Color.WHITE);
		for (int i = 0; i < lines.length; i++) g.drawString(lines[i], x + 5, y + fm.getAscent() + 3 + i * fm.getHeight());
	}
	private void drawCoursePreview(Graphics2D g, ChartPlotterWorldMap.State s, Shape clip, boolean append) {
		Point m = hover(clip);
		if (m == null || cachedStopIndex(m) >= 0) return;
		if (!map.tile(m, s, tile)) return;
		int[] t = tile;
		ChartPlotterRoutes.Preview pv = plugin.coursePreview(t[0], t[1], append);
		if (pv.state == ChartPlotterRoutes.PV_NONE) return;
		Color c = pv.state == ChartPlotterRoutes.PV_OK ? PREVIEW_OK : pv.state == ChartPlotterRoutes.PV_BAD ? PREVIEW_BAD : PREVIEW_SNAP;
		int dstX = map.pointX(s, pv.x, 0.5);
		int dstY = map.pointY(s, pv.y, 0.5);
		if (pv.x != t[0] || pv.y != t[1]) {
			int cursorX = map.pointX(s, t[0], 0.5);
			int cursorY = map.pointY(s, t[1], 0.5);
			g.setColor(c);
			g.drawLine(cursorX, cursorY, dstX, dstY);
			ellipse.setFrame(cursorX - 2, cursorY - 2, 4, 4);
			g.fill(ellipse);
		}
		marker(g, dstX, dstY, c);
	}
	private void marker(Graphics2D g, int x, int y, Color c) {
		g.setColor(c);
		ellipse.setFrame(x - 3.5, y - 3.5, 7, 7);
		g.fill(ellipse);
		ellipse.setFrame(x - 7.5, y - 7.5, 15, 15);
		g.draw(ellipse);
	}
	private void cacheStops(ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip) {
		StopCache old = stopCache;
		if (old.same(s, clip, trip)) return;
		int[] hits = new int[trip.size() * 5];
		int n = 0;
		for (int i = 0; i < trip.size(); i++) {
			int x = map.pointX(s, trip.x(i), 0.5);
			int y = map.pointY(s, trip.y(i), 0.5);
			if (!clip.contains(x, y)) continue;
			hits[n++] = i;
			hits[n++] = trip.x(i);
			hits[n++] = trip.y(i);
			hits[n++] = x;
			hits[n++] = y;
		}
		stopCache = new StopCache(s, clip, trip, n == hits.length ? hits : Arrays.copyOf(hits, n));
	}
	private int cachedStopIndex(Point m) {
		StopCache cache = stopCache;
		if (m == null || cache.clip == null || !cache.clip.contains(m.getX(), m.getY())) return -1;
		int best = -1;
		int bd = STOP_HIT_RADIUS * STOP_HIT_RADIUS + 1;
		for (int i = cache.hits.length - 5; i >= 0; i -= 5) {
			int dx = cache.hits[i + 3] - m.getX();
			int dy = cache.hits[i + 4] - m.getY();
			int d = dx * dx + dy * dy;
			if (d >= bd) continue;
			best = cache.hits[i];
			bd = d;
		}
		return best;
	}
	private int stop(Point m, ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip) {
		if (m == null || !clip.contains(m.getX(), m.getY())) return -1;
		int best = -1;
		int bd = STOP_HIT_RADIUS * STOP_HIT_RADIUS + 1;
		for (int i = trip.size() - 1; i >= 0; i--) {
			int dx = map.pointX(s, trip.x(i), 0.5) - m.getX();
			int dy = map.pointY(s, trip.y(i), 0.5) - m.getY();
			int d = dx * dx + dy * dy;
			if (d >= bd) continue;
			best = i;
			bd = d;
		}
		return best;
	}
	private Point hover(Shape clip) {
		Point m = ChartPlotterOverlay.eligibleMouse(client, plugin);
		return m != null && clip.contains(m.getX(), m.getY()) ? m : null;
	}
	private int hoverHeading(WorldView wv, LocalPoint anchor, ChartPlotterWorldMap.State s, Shape clip) {
		Point m = hover(clip);
		if (m == null) return -1;
		double ax = wv.getBaseX() + anchor.getX() / (double) TS;
		double ay = wv.getBaseY() + anchor.getY() / (double) TS;
		double dx = map.worldX(m, s) - ax;
		double dy = map.worldY(m, s) - ay;
		if (dx == 0 && dy == 0) return -1;
		double d = Math.toDegrees(Math.atan2(dy, dx));
		return ChartPlotterMath.norm((int) Math.round((270 - d) / 360 * 16) * 128);
	}
	private static final class StopCache {
		static final StopCache EMPTY = new StopCache();
		final Shape clip;
		final int[] hits;
		final Object stops;
		final float zoom;
		final int x;
		final int y;
		final int width;
		final int height;
		final int px;
		final int py;
		private StopCache() {
			clip = null;
			hits = new int[0];
			stops = null;
			zoom = 0;
			x = y = width = height = px = py = 0;
		}
		private StopCache(ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip, int[] hits) {
			this.clip = clip;
			this.hits = hits;
			stops = trip.stopKey();
			zoom = s.z;
			x = s.r.x;
			y = s.r.y;
			width = s.r.width;
			height = s.r.height;
			px = s.pos.getX();
			py = s.pos.getY();
		}
		boolean same(ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip) {return this.clip == clip && stops == trip.stopKey() && Float.floatToIntBits(zoom) == Float.floatToIntBits(s.z) && x == s.r.x && y == s.r.y && width == s.r.width && height == s.r.height && px == s.pos.getX() && py == s.pos.getY();}
	}
	private static boolean pointVisible(ChartPlotterWorldMap.State s, int x, int y, int pad) {
		double minX = s.pos.getX() - s.wt / 2.0 - pad;
		double minY = s.pos.getY() - s.ht / 2.0 - pad;
		double maxX = s.pos.getX() + s.wt / 2.0 + pad;
		double maxY = s.pos.getY() + s.ht / 2.0 + pad;
		return x >= minX && x <= maxX && y >= minY && y <= maxY;
	}
	private static boolean lineVisible(ChartPlotterWorldMap.State s, int ax, int ay, int bx, int by, int pad) {
		double minX = s.pos.getX() - s.wt / 2.0 - pad;
		double minY = s.pos.getY() - s.ht / 2.0 - pad;
		double maxX = s.pos.getX() + s.wt / 2.0 + pad;
		double maxY = s.pos.getY() + s.ht / 2.0 + pad;
		return Math.max(ax, bx) >= minX && Math.min(ax, bx) <= maxX && Math.max(ay, by) >= minY && Math.min(ay, by) <= maxY;
	}
}
