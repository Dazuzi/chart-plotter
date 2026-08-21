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
import java.util.Map;

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
			ChartPlotterWorldMap.State local = s.base(top);
			if (cur != null) draw(g, local, cur, ship.getConfig(), config.lineColor(), skip);
			if (pot != null) draw(g, local, pot, ship.getConfig(), config.potentialColor(), 0);
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
	public void courseMods(boolean ctrl, boolean shift) {
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
	private void draw(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterProjection.Path p, WorldEntityConfig wc, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) {
			if (p.blocked && p.n == 1 && skip < p.n) drawBlock(g, s, p, color);
			return;
		}
		int start = skip > 0 ? skip - 1 : 0;
		int mid = Math.min(p.blockedAt, p.n);
		segment(g, s, p, color, start, mid);
		boxes(g, s, p, wc, color, start, mid);
		if (mid < p.n) {
			segment(g, s, p, config.blockedColor(), Math.max(start, mid - 1), p.n);
			boxes(g, s, p, wc, config.blockedColor(), mid, p.n);
		}
	}
	private void boxes(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterProjection.Path p, WorldEntityConfig wc, Color color, int from, int to) {
		if (!config.sailingSlide()) return;
		float[] rx = ChartPlotterProjection.rectX(wc);
		float[] ry = ChartPlotterProjection.rectY(wc);
		g.setColor(color);
		for (int i = from; i < to; i++) {
			if (!box(p, i)) continue;
			int sx = Math.floorDiv(p.x[i], TS);
			int sy = Math.floorDiv(p.y[i], TS);
			if (!s.data.surfaceContainsPosition(s.baseX + sx, s.baseY + sy)) continue;
			g.draw(box(s, p, rx, ry, i));
		}
	}
	private Path2D.Double box(ChartPlotterWorldMap.State s, ChartPlotterProjection.Path p, float[] rx, float[] ry, int i) {
		Path2D.Double box = new Path2D.Double();
		for (int c = 0; c < 4; c++) {
			int lx = ChartPlotterMath.rotateX(p.x[i], p.o[i], (int) rx[c], (int) ry[c]);
			int ly = ChartPlotterMath.rotateY(p.y[i], p.o[i], (int) rx[c], (int) ry[c]);
			int px = map.mapX(s, lx);
			int py = map.mapY(s, ly);
			if (c == 0) box.moveTo(px, py);
			else box.lineTo(px, py);
		}
		box.closePath();
		return box;
	}
	private void segment(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterProjection.Path p, Color color, int from, int to) {
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		for (int i = from; i < to; i++) {
			int sx = Math.floorDiv(p.x[i], TS);
			int sy = Math.floorDiv(p.y[i], TS);
			if (!s.data.surfaceContainsPosition(s.baseX + sx, s.baseY + sy)) {
				have = false;
				continue;
			}
			int x = map.mapX(s, p.x[i]);
			int y = map.mapY(s, p.y[i]);
			if (have) line.lineTo(x, y);
			else {
				line.moveTo(x, y);
				have = true;
			}
		}
		g.setColor(color);
		g.draw(line);
	}
	private static boolean box(ChartPlotterProjection.Path p, int i) {return p.o[i] != p.prev(i) || p.slid[i];}
	private void drawBlock(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterProjection.Path p, Color color) {
		int sx = Math.floorDiv(p.x[0], TS);
		int sy = Math.floorDiv(p.y[0], TS);
		if (!s.data.surfaceContainsPosition(s.baseX + sx, s.baseY + sy)) return;
		int x = map.mapX(s, p.x[0]);
		int y = map.mapY(s, p.y[0]);
		int r = 5;
		g.setColor(color);
		g.drawLine(x - r, y - r, x + r, y + r);
		g.drawLine(x + r, y - r, x - r, y + r);
	}
	private void drawTrip(Graphics2D g, ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip, boolean tail) {
		Point mouse = hover(clip);
		int moving = draggedStop >= 0 && draggedStop < trip.size() && trip.x(draggedStop) == draggedX && trip.y(draggedStop) == draggedY ? draggedStop : -1;
		Point drag = draggedPoint;
		int[] moved = moving >= 0 && drag != null ? map.tile(drag, s) : null;
		Point movedPoint = moving < 0 || drag == null ? null : moved == null ? drag : map.point(s, moved[0], moved[1], 0.5, 0.5);
		int remove = moving >= 0 ? -1 : stop(mouse, s, clip, trip);
		for (int i = 0; i < trip.size(); i++) {
			ChartPlotterRoute r = trip.route(i);
			if (r == null || r.status != ChartPlotterRoute.OK) continue;
			if (config.sparseRouteDebug() && r.sparseN > 1) drawSparseRoute(g, s, r);
			boolean removing = remove >= 0 && (tail ? i >= remove : i == remove || i == remove + 1);
			drawRoutePath(g, s, r, removing ? REMOVE : routeColor(r, i > 0));
		}
		if (movedPoint != null) {
			Point old = map.point(s, trip.x(moving), trip.y(moving), 0.5, 0.5);
			Stroke stroke = g.getStroke();
			g.setStroke(routeStroke.dashed(config.worldMapLineWidth()));
			g.setColor(moved == null ? PREVIEW_BAD : PREVIEW_SNAP);
			g.drawLine(old.getX(), old.getY(), movedPoint.getX(), movedPoint.getY());
			g.setStroke(stroke);
		}
		long now = System.currentTimeMillis();
		for (int i = 0; i < trip.size(); i++) {
			ChartPlotterRoute r = trip.route(i);
			Point p = i == moving && movedPoint != null ? movedPoint : map.point(s, trip.x(i), trip.y(i), 0.5, 0.5);
			boolean removing = remove >= 0 && (tail ? i >= remove : i == remove);
			Color c = i == moving ? moved == null ? PREVIEW_BAD : PREVIEW_SNAP : removing ? REMOVE : routeColor(r, i > 0);
			marker(g, p, c);
			if (trip.size() > 1) label(g, p, i + 1, c);
			if (r != null && r.text() != null && (r.status == ChartPlotterRoute.PENDING || now - r.time < TIP_MS)) tip(g, s.r, p, r.text());
		}
		if (moving >= 0 && movedPoint != null) tip(g, s.r, movedPoint, moved == null ? "Release to cancel" : "Release to move stop " + (moving + 1));
		else if (remove >= 0) {
			Point p = map.point(s, trip.x(remove), trip.y(remove), 0.5, 0.5);
			tripTip(g, s.r, p, trip, remove);
		}
	}
	private void tripTip(Graphics2D g, Rectangle bounds, Point p, ChartPlotterTrip trip, int stop) {
		ChartPlotterRoute route = trip.route(stop);
		String status = route == null ? "Charting course" : route.text();
		if (!config.worldMapTripHints()) {
			if (status != null) tip(g, bounds, p, status);
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
		tip(g, bounds, p, lines);
	}
	private Color routeColor(ChartPlotterRoute r, boolean future) {
		Color c = r == null ? STATUS_WARN : r.status == ChartPlotterRoute.OK ? config.chartColor() : r.status == ChartPlotterRoute.UNCHARTED ? STATUS_UNCHARTED : r.status == ChartPlotterRoute.BLOCKED ? STATUS_BLOCKED : STATUS_WARN;
		return future ? faded(c) : c;
	}
	private void label(Graphics2D g, Point p, int n, Color c) {
		String text = Integer.toString(n);
		int x = p.getX() + 10;
		int y = p.getY() + g.getFontMetrics().getAscent() / 2;
		g.setColor(new Color(0, 0, 0, c.getAlpha()));
		g.drawString(text, x + 1, y + 1);
		g.setColor(c);
		g.drawString(text, x, y);
	}
	private void drawRoutePath(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterRoute r, Color c) {
		if (r.n < 1) return;
		Stroke old = g.getStroke();
		Stroke solid = routeStroke.solid(config.worldMapLineWidth());
		Stroke dash = routeStroke.dashed(config.worldMapLineWidth());
		g.setColor(c);
		double speed = ChartPlotterRouteMoves.speedBucket(plugin.speed());
		for (int i = 1; i < r.n; i++) routeLine(g, s, r.x[i - 1], r.y[i - 1], r.x[i], r.y[i], speed, solid, dash);
		if (r.x[r.n - 1] != r.tx || r.y[r.n - 1] != r.ty) {
			g.setStroke(dash);
			g.setColor(faded(c));
			Point a = map.point(s, r.x[r.n - 1], r.y[r.n - 1], 0.5, 0.5);
			Point b = map.point(s, r.tx, r.ty, 0.5, 0.5);
			g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
		}
		g.setStroke(old);
	}
	private void routeLine(Graphics2D g, ChartPlotterWorldMap.State s, int ax, int ay, int bx, int by, double speed, Stroke solid, Stroke dash) {
		g.setStroke(ChartPlotterRouteMoves.solid(ax, ay, bx, by, speed) ? solid : dash);
		Point a = map.point(s, ax, ay, 0.5, 0.5);
		Point b = map.point(s, bx, by, 0.5, 0.5);
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private static Color faded(Color c) {return new Color(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha() * 3 / 5);}
	private void drawSparseRoute(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterRoute r) {
		Path2D.Double line = sparsePath(s, r);
		if (line == null) return;
		Stroke old = g.getStroke();
		g.setStroke(sparseStroke(sparseWidth(s, r.sparseBand)));
		g.setColor(SPARSE_GLOW);
		g.draw(line);
		g.setStroke(SPARSE_STROKE);
		g.setColor(SPARSE_LINE);
		g.draw(line);
		for (int i = 0; i < r.sparseN; i++) sparseDot(g, s, r.sparseX[i], r.sparseY[i]);
		g.setStroke(old);
	}
	private Path2D.Double sparsePath(ChartPlotterWorldMap.State s, ChartPlotterRoute r) {
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		boolean any = false;
		for (int i = 0; i < r.sparseN; i++) {
			Point q = map.point(s, r.sparseX[i], r.sparseY[i], 0.5, 0.5);
			if (have) line.lineTo(q.getX(), q.getY());
			else {
				line.moveTo(q.getX(), q.getY());
				have = true;
			}
			any = true;
		}
		return any ? line : null;
	}
	private void sparseDot(Graphics2D g, ChartPlotterWorldMap.State s, int wx, int wy) {
		Point p = map.point(s, wx, wy, 0.5, 0.5);
		g.setColor(SPARSE_RING);
		g.fill(new Ellipse2D.Double(p.getX() - 4, p.getY() - 4, 8, 8));
		g.setColor(SPARSE_DOT);
		g.fill(new Ellipse2D.Double(p.getX() - 3, p.getY() - 3, 6, 6));
		g.setColor(Color.RED);
		g.fill(new Ellipse2D.Double(p.getX() - 1.5, p.getY() - 1.5, 3, 3));
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
		for (Map.Entry<Long, ChartPlotterCollisionData.Chunk> e : data.entries()) {
			if (e.getValue().empty()) continue;
			int cx = (int) (e.getKey() >> 32);
			int cy = (int) (long) e.getKey();
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
		Point a = map.point(s, ax, ay, afx, afy);
		Point b = map.point(s, bx, by, bfx, bfy);
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private void tip(Graphics2D g, Rectangle r, Point p, String... lines) {
		FontMetrics fm = g.getFontMetrics();
		int w = 0;
		for (String line : lines) w = Math.max(w, fm.stringWidth(line));
		w += 10;
		int h = fm.getHeight() * lines.length + 6;
		int x = p.getX() + 12;
		int y = p.getY() - h - 8;
		if (x + w > r.x + r.width) x = p.getX() - w - 12;
		if (y < r.y) y = p.getY() + 12;
		x = Math.max(r.x + 4, Math.min(x, r.x + r.width - w - 4));
		y = Math.max(r.y + 4, Math.min(y, r.y + r.height - h - 4));
		g.setColor(TIP_BG);
		g.fillRect(x, y, w, h);
		g.setColor(Color.WHITE);
		for (int i = 0; i < lines.length; i++) g.drawString(lines[i], x + 5, y + fm.getAscent() + 3 + i * fm.getHeight());
	}
	private void drawCoursePreview(Graphics2D g, ChartPlotterWorldMap.State s, Shape clip, boolean append) {
		Point m = hover(clip);
		if (m == null || stop(m, s, clip, plugin.trip()) >= 0) return;
		int[] t = map.tile(m, s);
		if (t == null) return;
		ChartPlotterRoutes.Preview pv = plugin.coursePreview(t[0], t[1], append);
		if (pv.state == ChartPlotterRoutes.PV_NONE) return;
		Color c = pv.state == ChartPlotterRoutes.PV_OK ? PREVIEW_OK : pv.state == ChartPlotterRoutes.PV_BAD ? PREVIEW_BAD : PREVIEW_SNAP;
		Point dst = map.point(s, pv.x, pv.y, 0.5, 0.5);
		if (pv.x != t[0] || pv.y != t[1]) {
			Point cursor = map.point(s, t[0], t[1], 0.5, 0.5);
			g.setColor(c);
			g.drawLine(cursor.getX(), cursor.getY(), dst.getX(), dst.getY());
			g.fill(new Ellipse2D.Double(cursor.getX() - 2, cursor.getY() - 2, 4, 4));
		}
		marker(g, dst, c);
	}
	private void marker(Graphics2D g, Point p, Color c) {
		g.setColor(c);
		g.fill(new Ellipse2D.Double(p.getX() - 3.5, p.getY() - 3.5, 7, 7));
		g.draw(new Ellipse2D.Double(p.getX() - 7.5, p.getY() - 7.5, 15, 15));
	}
	private void cacheStops(ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip) {
		int[] hits = new int[trip.size() * 5];
		int n = 0;
		for (int i = 0; i < trip.size(); i++) {
			Point p = map.point(s, trip.x(i), trip.y(i), 0.5, 0.5);
			if (!clip.contains(p.getX(), p.getY())) continue;
			hits[n++] = i;
			hits[n++] = trip.x(i);
			hits[n++] = trip.y(i);
			hits[n++] = p.getX();
			hits[n++] = p.getY();
		}
		stopCache = new StopCache(clip, n == hits.length ? hits : Arrays.copyOf(hits, n));
	}
	private int stop(Point m, ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip) {
		if (m == null || !clip.contains(m.getX(), m.getY())) return -1;
		int best = -1;
		int bd = STOP_HIT_RADIUS * STOP_HIT_RADIUS + 1;
		for (int i = trip.size() - 1; i >= 0; i--) {
			Point p = map.point(s, trip.x(i), trip.y(i), 0.5, 0.5);
			int dx = p.getX() - m.getX();
			int dy = p.getY() - m.getY();
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
		double[] p = map.world(m, s);
		double ax = wv.getBaseX() + anchor.getX() / (double) TS;
		double ay = wv.getBaseY() + anchor.getY() / (double) TS;
		double dx = p[0] - ax;
		double dy = p[1] - ay;
		if (dx == 0 && dy == 0) return -1;
		double d = Math.toDegrees(Math.atan2(dy, dx));
		return ChartPlotterMath.norm((int) Math.round((270 - d) / 360 * 16) * 128);
	}
	private static final class StopCache {
		static final StopCache EMPTY = new StopCache(null, new int[0]);
		final Shape clip;
		final int[] hits;
		private StopCache(Shape clip, int[] hits) {
			this.clip = clip;
			this.hits = hits;
		}
	}
}
