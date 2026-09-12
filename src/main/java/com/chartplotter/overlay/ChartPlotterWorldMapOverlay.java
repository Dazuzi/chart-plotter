package com.chartplotter.overlay;

import com.chartplotter.ChartPlotterCacheOverlay;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterEtaMode;
import com.chartplotter.ChartPlotterLineMode;
import com.chartplotter.ChartPlotterMapTooltip;
import com.chartplotter.ChartPlotterPlugin;
import com.chartplotter.ChartPlotterWorldMapClick;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRouteMoves;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.route.ChartPlotterTrip;
import com.chartplotter.runtime.ChartPlotterProjection;
import com.chartplotter.runtime.ChartPlotterWorldMap;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.Arrays;

public class ChartPlotterWorldMapOverlay extends Overlay {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final Color STATUS_UNCHARTED = new Color(255, 80, 60, 220);
	private static final Color STATUS_BLOCKED = new Color(170, 170, 170, 220);
	private static final Color STATUS_WARN = new Color(255, 190, 40, 220);
	private static final Color CACHE_EDGE = new Color(0, 210, 120, 150);
	private static final Color TIP_BG = new Color(20, 20, 20, 220);
	private static final Color PREVIEW_OK = new Color(80, 255, 120, 235);
	private static final Color PREVIEW_SNAP = new Color(255, 200, 40, 235);
	private static final Color PREVIEW_BAD = new Color(255, 70, 60, 235);
	private static final Color PREVIEW_PENDING = new Color(190, 190, 190, 235);
	private static final Color REMOVE = new Color(255, 70, 60, 235);
	private static final float[] DASH = {8, 6};
	private static final long TIP_MS = 3000;
	private static final int STOP_HIT_RADIUS = 10;
	private static final Stroke CACHE_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterProjection projection;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterWorldMap map;
	private final ChartPlotterStrokeCache routeStroke = new ChartPlotterStrokeCache(BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, DASH);
	private volatile boolean ctrl;
	private volatile boolean shift;
	private volatile int draggedStop = -1;
	private volatile int draggedX;
	private volatile int draggedY;
	private volatile Point draggedPoint;
	private volatile StopCache stopCache = StopCache.EMPTY;
	private double routeSpeed = Double.NaN;
	private ChartPlotterRouteMoves.Model routeModel;
	private final Path2D.Double projectionLine = new Path2D.Double();
	private final Ellipse2D.Double ellipse = new Ellipse2D.Double();
	private final int[] colorKey = new int[8];
	private final Color[][] colorCache = new Color[colorKey.length][];
	private final String[] stopLabels = new String[ChartPlotterRoutes.MAX_STOPS];
	private int stopLabelStart;
	private final int[] tile = new int[2];
	private int colorNext;
	private boolean previewActive;
	private ChartPlotterTrip tipTrip;
	private int tipStop;
	private int tipFirst;
	private ChartPlotterMapTooltip tipMode;
	private boolean tipSailing;
	private double tipX;
	private double tipY;
	private double tipSpeed;
	private String[] tipLines;
	@Inject
	ChartPlotterWorldMapOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterProjection projection, ChartPlotterCollisionCache collisionCache, ChartPlotterWorldMap map) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.projection = projection;
		this.collisionCache = collisionCache;
		this.map = map;
		setLayer(OverlayLayer.MANUAL);
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}
	@Override
	public Dimension render(Graphics2D g) {
		boolean sailing = plugin.isSailing();
		boolean showChart = config.worldMapChartLine();
		ChartPlotterTrip trip = plugin.trip();
		boolean showRoute = showChart && !trip.empty();
		if (!showRoute) {
			stopCache = StopCache.EMPTY;
			tipTrip = null;
			tipLines = null;
		}
		if (!sailing && !showRoute) {
			if (previewActive) {previewActive = false; plugin.clearCoursePreview();}
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
		if (!showCourse && !showProjected && !showRoute && !cacheOverlay.worldMap && !showPreview) {
			map.clickBlocked();
			return null;
		}
		ChartPlotterWorldMap.State s = map.state();
		if (s == null) {
			stopCache = StopCache.EMPTY;
			if (previewActive) {previewActive = false; plugin.clearCoursePreview();}
			return null;
		}
		Shape clip = map.clip(s);
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setClip(clip);
		try {
			if (sailing && cacheOverlay.worldMap) drawCache(g, s);
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
			if (cur != null) draw(g, s, baseX, baseY, cur, config.lineColor(), skip);
			if (pot != null) draw(g, s, baseX, baseY, pot, config.potentialColor(), 0);
			return null;
		} finally {
			g.setStroke(oldStroke);
			g.setClip(oldClip);
		}
	}
	public int[] tile(Point m) {return map.tile(m);}
	public void clear() {
		stopCache = StopCache.EMPTY;
		tipTrip = null;
		tipLines = null;
		previewActive = false;
		courseMods(false, false);
		clearStopDrag();
		map.clear();
	}
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
	public void tick() {
		if (!previewActive || plugin.isSailing() && config.worldMapChartLine() && (ctrl || shift) && map.state() != null) return;
		previewActive = false;
		plugin.clearCoursePreview();
	}
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
	private void draw(Graphics2D g, ChartPlotterWorldMap.State s, int baseX, int baseY, ChartPlotterProjection.Path p, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) return;
		int start = skip > 0 ? skip - 1 : 0;
		int mid = Math.min(p.blockedAt, p.n);
		Stroke old = g.getStroke();
		g.setStroke(routeStroke.solid(config.worldMapLineWidth()));
		segment(g, s, baseX, baseY, p, color, start, mid);
		if (mid < p.n) segment(g, s, baseX, baseY, p, config.blockedColor(), Math.max(start, mid - 1), p.n);
		g.setStroke(old);
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
	private void drawTrip(Graphics2D g, ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip, boolean tail) {
		int first = config.infoStopProgress() ? trip.stopNumber() : 1;
		if (stopLabelStart != first) {
			stopLabelStart = first;
			Arrays.fill(stopLabels, null);
		}
		Point mouse = hover(clip);
		int moving = draggedStop >= 0 && draggedStop < trip.size() && trip.x(draggedStop) == draggedX && trip.y(draggedStop) == draggedY ? draggedStop : -1;
		Point drag = draggedPoint;
		int[] moved = moving >= 0 && map.tile(drag, s, tile) ? tile : null;
		boolean movedPoint = moving >= 0 && drag != null;
		int movedPX = !movedPoint ? 0 : moved == null ? drag.getX() : map.pointX(s, moved[0] + 0.5);
		int movedPY = !movedPoint ? 0 : moved == null ? drag.getY() : map.pointY(s, moved[1] + 0.5);
		int remove = moving >= 0 ? -1 : cachedStopIndex(mouse);
		ChartPlotterRouteMoves.Model model = routeModel();
		for (int i = 0; i < trip.size(); i++) {
			ChartPlotterRoute r = trip.route(i);
			if (r == null || r.status != ChartPlotterRoute.OK) continue;
			boolean removing = remove >= 0 && (tail ? i >= remove : i == remove || i == remove + 1);
			drawRoutePath(g, s, r, removing ? REMOVE : routeColor(r, i > 0), model);
		}
		if (movedPoint) {
			Stroke stroke = g.getStroke();
			g.setStroke(routeStroke.dashed(config.worldMapLineWidth()));
			g.setColor(moved == null ? PREVIEW_BAD : PREVIEW_SNAP);
			g.drawLine(map.pointX(s, trip.markerX(moving)), map.pointY(s, trip.markerY(moving)), movedPX, movedPY);
			g.setStroke(stroke);
		}
		long now = System.currentTimeMillis();
		for (int i = 0; i < trip.size(); i++) {
			ChartPlotterRoute r = trip.route(i);
			int px = i == moving && movedPoint ? movedPX : map.pointX(s, trip.markerX(i));
			int py = i == moving && movedPoint ? movedPY : map.pointY(s, trip.markerY(i));
			boolean removing = remove >= 0 && (tail ? i >= remove : i == remove);
			Color c = i == moving ? moved == null ? PREVIEW_BAD : PREVIEW_SNAP : removing ? REMOVE : routeColor(r, i > 0);
			marker(g, px, py, c);
			if (trip.size() > 1 || first > 1) label(g, px, py, i, c);
			if (i != remove && i != moving && r != null && r.text() != null && (r.status == ChartPlotterRoute.PENDING || now - r.time < TIP_MS)) tip(g, s.r, px, py, r.text());
		}
		if (moving >= 0 && movedPoint && config.worldMapTooltips().instructions) tip(g, s.r, movedPX, movedPY, moved == null ? "Release to cancel" : "Release to move stop " + (moving + first));
		else if (remove >= 0) {
			tip(g, s.r, map.pointX(s, trip.markerX(remove)), map.pointY(s, trip.markerY(remove)), tripTip(trip, remove));
		}
	}
	String[] tripTip(ChartPlotterTrip trip, int stop) {
		ChartPlotterMapTooltip mode = config.worldMapTooltips();
		if (mode == ChartPlotterMapTooltip.OFF) {tipTrip = null; return tipLines = null;}
		boolean sailing = plugin.isSailing();
		int first = config.infoStopProgress() ? trip.stopNumber() : 1;
		double bx = Double.NaN;
		double by = Double.NaN;
		double speed = 0;
		if (mode.info && sailing) {
			WorldEntity ship = plugin.getShip();
			WorldView top = plugin.top();
			LocalPoint loc = ship == null ? null : ship.getLocalLocation();
			if (top != null && loc != null) {
				bx = top.getBaseX() + loc.getX() / (double) TS;
				by = top.getBaseY() + loc.getY() / (double) TS;
			}
			speed = plugin.baseSpeed();
		}
		if (tipTrip == trip && tipStop == stop && tipFirst == first && tipMode == mode && tipSailing == sailing && Double.compare(tipX, bx) == 0 && Double.compare(tipY, by) == 0 && Double.compare(tipSpeed, speed) == 0) return tipLines;
		tipTrip = trip;
		tipStop = stop;
		tipFirst = first;
		tipMode = mode;
		tipSailing = sailing;
		tipX = bx;
		tipY = by;
		tipSpeed = speed;
		ChartPlotterRoute route = trip.route(stop);
		String status = route == null ? "Charting course" : route.text();
		if (mode.info) for (int i = 0; i <= stop; i++) {
			ChartPlotterRoute leg = trip.route(i);
			if (leg != null && leg.status == ChartPlotterRoute.OK && !leg.recalculating) continue;
			status = (i < stop ? "Stop " + (i + first) + ": " : "") + (leg == null || leg.recalculating ? "Charting course" : leg.text());
			break;
		}
		int n = stop + first;
		boolean tail = stop + 1 < trip.size();
		boolean append = sailing && !tail && plugin.canAppend();
		int info = status != null ? 1 : mode.info ? 2 : 0;
		String[] lines = new String[info + (mode.instructions ? 1 + (info > 0 ? 1 : 0) + (tail || append ? 1 : 0) + (sailing ? 1 : 0) : 0)];
		int i = 0;
		if (status != null) lines[i++] = status;
		else if (mode.info) {
			double distance = trip.distance(bx, by, stop);
			lines[i++] = "Distance: " + (Double.isFinite(distance) ? (long) Math.ceil(distance) + " tiles" : "-");
			String time = ChartPlotterEtaMode.SECONDS.format(speed > 0 ? distance / speed : Double.NaN);
			lines[i++] = "ETA: " + (time.equals("-") ? time : "~" + time);
		}
		if (mode.instructions) {
			if (i > 0) lines[i++] = "";
			lines[i++] = trip.size() == 1 ? "Click: clear destination" : "Click: remove stop " + n;
			if (tail) lines[i++] = "Shift+click: remove stop " + n + " and later";
			else if (append) lines[i++] = "Shift+click elsewhere: add stop";
			if (sailing) lines[i] = trip.size() == 1 ? "Drag: move destination" : "Drag: move stop " + n;
		}
		return tipLines = lines;
	}
	private Color routeColor(ChartPlotterRoute r, boolean future) {
		Color c = r == null ? STATUS_WARN : r.status == ChartPlotterRoute.OK ? config.chartColor() : r.status == ChartPlotterRoute.UNCHARTED ? STATUS_UNCHARTED : r.status == ChartPlotterRoute.BLOCKED ? STATUS_BLOCKED : STATUS_WARN;
		return future ? faded(c) : c;
	}
	private void label(Graphics2D g, int px, int py, int i, Color c) {
		String text = stopLabels[i];
		if (text == null) stopLabels[i] = text = Integer.toString(stopLabelStart + i);
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
		for (int i = 1; i < r.n; i++) routeLine(g, s, r.x[i - 1] + r.offsetX, r.y[i - 1] + r.offsetY, r.x[i] + r.offsetX, r.y[i] + r.offsetY, model, solid, dash, pad);
		for (int i = 1; i < r.departure.length; i++) routeLine(g, s, r.departureX(i - 1), r.departureY(i - 1), r.departureX(i), r.departureY(i), model, solid, dash, pad);
		for (int i = 1; i < r.connection.length; i++) routeLine(g, s, r.connectionX(i - 1), r.connectionY(i - 1), r.connectionX(i), r.connectionY(i), model, solid, dash, pad);
		g.setStroke(old);
	}
	private void routeLine(Graphics2D g, ChartPlotterWorldMap.State s, double ax, double ay, double bx, double by, ChartPlotterRouteMoves.Model model, Stroke solid, Stroke dash, int pad) {
		if (!lineVisible(s, ax, ay, bx, by, pad)) return;
		g.setStroke(ChartPlotterRouteMoves.solid(ax, ay, bx, by, model) ? solid : dash);
		g.drawLine(map.pointX(s, ax), map.pointY(s, ay), map.pointX(s, bx), map.pointY(s, by));
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
	void drawCache(Graphics2D g, ChartPlotterWorldMap.State s) {
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
		drawCacheEdge(g, s, wx, wy, 0, 0, wx, wy + 7, 0, 1);
		if (data.uncached(cx + 1, cy)) drawCacheEdge(g, s, wx + 7, wy, 1, 0, wx + 7, wy + 7, 1, 1);
		drawCacheEdge(g, s, wx, wy, 0, 0, wx + 7, wy, 1, 0);
		if (data.uncached(cx, cy + 1)) drawCacheEdge(g, s, wx, wy + 7, 0, 1, wx + 7, wy + 7, 1, 1);
	}
	private boolean cacheChunkHidden(ChartPlotterWorldMap.State s, int cx, int cy, int minWX, int minWY, int maxWX, int maxWY) {
		int wx = cx << 3;
		int wy = cy << 3;
		return wx > maxWX || wx + 7 < minWX || wy > maxWY || wy + 7 < minWY || !s.data.surfaceContainsPosition(wx + 4, wy + 4);
	}
	private void drawCacheEdge(Graphics2D g, ChartPlotterWorldMap.State s, int ax, int ay, double afx, double afy, int bx, int by, double bfx, double bfy) {
		g.drawLine(map.pointX(s, ax + afx), map.pointY(s, ay + afy), map.pointX(s, bx + bfx), map.pointY(s, by + bfy));
	}
	private void tip(Graphics2D g, Rectangle r, int px, int py, String... lines) {
		if (config.worldMapTooltips() == ChartPlotterMapTooltip.OFF || lines == null || lines.length == 0) return;
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
		if (m == null || cachedStopIndex(m) >= 0 || !map.tile(m, s, tile)) {plugin.clearCoursePreview(); return;}
		int[] t = tile;
		ChartPlotterRoutes.Preview pv = plugin.coursePreview(t[0], t[1], append);
		if (pv.state == ChartPlotterRoutes.PV_NONE) return;
		Color c = pv.state == ChartPlotterRoutes.PV_OK ? PREVIEW_OK : pv.state == ChartPlotterRoutes.PV_BAD ? PREVIEW_BAD : pv.state == ChartPlotterRoutes.PV_PENDING ? PREVIEW_PENDING : PREVIEW_SNAP;
		int dstX = map.pointX(s, pv.x + 0.5);
		int dstY = map.pointY(s, pv.y + 0.5);
		if (pv.x != t[0] || pv.y != t[1]) {
			int cursorX = map.pointX(s, t[0] + 0.5);
			int cursorY = map.pointY(s, t[1] + 0.5);
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
	void cacheStops(ChartPlotterWorldMap.State s, Shape clip, ChartPlotterTrip trip) {
		StopCache old = stopCache;
		if (old.same(s, clip, trip)) return;
		int[] hits = new int[trip.size() * 5];
		int n = 0;
		for (int i = 0; i < trip.size(); i++) {
			int x = map.pointX(s, trip.markerX(i));
			int y = map.pointY(s, trip.markerY(i));
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
			int dx = map.pointX(s, trip.markerX(i)) - m.getX();
			int dy = map.pointY(s, trip.markerY(i)) - m.getY();
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
	private static boolean lineVisible(ChartPlotterWorldMap.State s, double ax, double ay, double bx, double by, int pad) {
		double minX = s.pos.getX() - s.wt / 2.0 - pad;
		double minY = s.pos.getY() - s.ht / 2.0 - pad;
		double maxX = s.pos.getX() + s.wt / 2.0 + pad;
		double maxY = s.pos.getY() + s.ht / 2.0 + pad;
		return Math.max(ax, bx) >= minX && Math.min(ax, bx) <= maxX && Math.max(ay, by) >= minY && Math.min(ay, by) <= maxY;
	}
}
