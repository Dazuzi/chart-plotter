package com.chartplotter.overlay;
import com.chartplotter.ChartPlotterCacheOverlay;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterLineMode;
import com.chartplotter.ChartPlotterPlugin;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRouteMoves;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.runtime.ChartPlotterProjection;
import com.chartplotter.runtime.ChartPlotterWorldMap;
import com.chartplotter.util.ChartPlotterMath;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
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
	private static final float[] DASH = {8, 6};
	private static final long TIP_MS = 3000;
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterProjection projection;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterWorldMap map;
	private final ChartPlotterNodeEditor editor;
	private volatile boolean ctrl;
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
		boolean edit = config.nodeEditor();
		if (!plugin.isSailing() && !edit) return null;
		ChartPlotterLineMode mode = config.worldMapLineMode();
		boolean showWorldMap = mode.on;
		ChartPlotterCacheOverlay cacheOverlay = config.cacheOverlay();
		if (!showWorldMap && !cacheOverlay.worldMap && !edit) return null;
		ChartPlotterWorldMap.State s = map.state();
		if (s == null) return null;
		Shape clip = map.clip(s);
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setClip(clip);
		try {
			if (cacheOverlay.worldMap) drawCache(g, s);
			if (edit) editor.draw(g, s);
			if (!plugin.isSailing()) return null;
			if (!showWorldMap) return null;
			WorldView top = plugin.top();
			WorldEntity ship = plugin.getShip();
			if (ship == null || top == null) return null;
			LocalPoint anchor = ship.getTargetLocation();
			LocalPoint center = ship.getLocalLocation();
			if (anchor == null) anchor = center;
			if (anchor == null || center == null) return null;
			int from = plugin.heading(ship);
			int course = plugin.course(ship);
			int mouse = ctrl ? -1 : hoverHeading(top, center, s, clip);
			int cap = map.pathCap(top, anchor, s);
			ChartPlotterProjection.Path cur = projection.path(top, ship.getConfig(), anchor, from, course, cap, mode.blocked);
			ChartPlotterProjection.Path pot = null;
			if (mouse >= 0) pot = projection.path(top, ship.getConfig(), anchor, from, mouse, cap, mode.blocked);
			int skip = pot != null ? ChartPlotterProjection.match(cur, pot) : 0;
			g.setStroke(new BasicStroke(config.worldMapLineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			drawRoute(g, s, plugin.route());
			ChartPlotterWorldMap.State local = s.base(top);
			draw(g, local, cur, ship.getConfig(), config.lineColor(), skip);
			if (pot != null) draw(g, local, pot, ship.getConfig(), config.potentialColor(), 0);
			if (ctrl) drawCoursePreview(g, s, clip);
			return null;
		} finally {
			g.setStroke(oldStroke);
			g.setClip(oldClip);
		}
	}
	public int[] tile(Point m) {return map.tile(m);}
	public int[] node(Point m) {return editor.node(m);}
	public void editNode(Point m) {editor.edit(m);}
	public void removeNode(int wx, int wy) {editor.remove(wx, wy);}
	public void placeNode(Point m) {editor.place(m);}
	public boolean movingNode() {return editor.moving();}
	public void nodeAlt(boolean on) {editor.alt(on);}
	public void courseCtrl(boolean on) {ctrl = on;}
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
	private static boolean box(ChartPlotterProjection.Path p, int i) {return p.o[i] != prev(p, i) || p.slid[i];}
	private static int prev(ChartPlotterProjection.Path p, int i) {return i > 0 ? p.o[i - 1] : p.start;}
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
	private void drawRoute(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterRoute r) {
		if (r == null) return;
		Color c = r.status == ChartPlotterRoute.OK ? config.chartColor() : r.status == ChartPlotterRoute.UNCHARTED ? STATUS_UNCHARTED : r.status == ChartPlotterRoute.BLOCKED ? STATUS_BLOCKED : STATUS_WARN;
		Point t = map.point(s, r.tx, r.ty, 0.5, 0.5);
		String text = r.text();
		if (text != null && r.status != ChartPlotterRoute.PENDING && System.currentTimeMillis() - r.time >= TIP_MS) return;
		if (config.sparseRouteDebug() && r.status == ChartPlotterRoute.OK && r.sparseN > 1) drawSparseRoute(g, s, r);
		if (r.status == ChartPlotterRoute.OK) {
			drawRoutePath(g, s, r, c);
		}
		g.setColor(c);
		g.fill(new Ellipse2D.Double(t.getX() - 3.5, t.getY() - 3.5, 7, 7));
		g.draw(new Ellipse2D.Double(t.getX() - 7.5, t.getY() - 7.5, 15, 15));
		if (text != null && System.currentTimeMillis() - r.time < TIP_MS) tip(g, s.r, t, text);
	}
	private void drawRoutePath(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterRoute r, Color c) {
		if (r.n < 1) return;
		Stroke old = g.getStroke();
		Stroke dash = new BasicStroke(config.worldMapLineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, DASH, 0);
		g.setColor(c);
		double speed = ChartPlotterRouteMoves.speedBucket(plugin.speed());
		for (int i = 1; i < r.n; i++) routeLine(g, s, r.x[i - 1], r.y[i - 1], r.x[i], r.y[i], speed, old, dash);
		if (r.x[r.n - 1] != r.tx || r.y[r.n - 1] != r.ty) routeLine(g, s, r.x[r.n - 1], r.y[r.n - 1], r.tx, r.ty, speed, old, dash);
		g.setStroke(old);
	}
	private void routeLine(Graphics2D g, ChartPlotterWorldMap.State s, int ax, int ay, int bx, int by, double speed, Stroke solid, Stroke dash) {
		g.setStroke(routeSolid(ax, ay, bx, by, speed) ? solid : dash);
		Point a = map.point(s, ax, ay, 0.5, 0.5);
		Point b = map.point(s, bx, by, 0.5, 0.5);
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private static boolean routeSolid(int ax, int ay, int bx, int by, double speed) {return speed <= 0 || ChartPlotterRouteMoves.model(bx - ax, by - ay, speed);}
	private void drawSparseRoute(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterRoute r) {
		Path2D.Double line = sparsePath(s, r);
		if (line == null) return;
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(sparseWidth(s, r.sparseBand), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(SPARSE_GLOW);
		g.draw(line);
		g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
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
		g.setColor(TIP_BG);
		g.fillRect(x, y, w, h);
		g.setColor(Color.WHITE);
		g.drawString(s, x + 5, y + fm.getAscent() + 3);
	}
	private void drawCoursePreview(Graphics2D g, ChartPlotterWorldMap.State s, Shape clip) {
		Point m = hover(clip);
		if (m == null) return;
		int[] t = map.tile(m, s);
		if (t == null) return;
		ChartPlotterRoutes.Preview pv = plugin.coursePreview(t[0], t[1]);
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
}
