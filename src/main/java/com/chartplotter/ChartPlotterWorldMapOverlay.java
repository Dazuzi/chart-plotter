package com.chartplotter;
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
import java.util.Map;
import javax.inject.Inject;
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
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterProjection projection;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterWorldMap map;
	private final ChartPlotterNodeEditor editor;
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
		boolean showWorldMap = config.worldMapEnabled();
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
			int mouse = hoverHeading(top, center, s, clip);
			int cap = map.pathCap(top, anchor, s);
			boolean showExt = config.worldMapShowBlockedExtension();
			ChartPlotterProjection.Path cur = projection.path(top, ship.getConfig(), anchor, from, course, cap, showExt);
			ChartPlotterProjection.Path pot = null;
			if (mouse >= 0) pot = projection.path(top, ship.getConfig(), anchor, from, mouse, cap, showExt);
			int skip = pot != null ? ChartPlotterProjection.match(cur, pot) : 0;
			g.setStroke(new BasicStroke(config.worldMapLineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			drawRoute(g, s, plugin.route());
			ChartPlotterWorldMap.State local = s.base(top);
			draw(g, local, cur, config.lineColor(), skip);
			if (pot != null) draw(g, local, pot, config.potentialColor(), 0);
			return null;
		} finally {
			g.setStroke(oldStroke);
			g.setClip(oldClip);
		}
	}
	int[] tile(Point m) {return map.tile(m);}
	void addNode(Point m) {editor.add(m);}
	void startNodeMove(Point m) {editor.startMove(m);}
	void placeNode(Point m) {editor.place(m);}
	boolean movingNode() {return editor.moving();}
	void nodeAlt(boolean on) {editor.alt(on);}
	private void draw(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterProjection.Path p, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) {
			if (p.blocked && p.n == 1 && skip < p.n) drawBlock(g, s, p, color);
			return;
		}
		int start = skip > 0 ? skip - 1 : 0;
		int mid = Math.min(p.blockedAt, p.n);
		segment(g, s, p, color, start, mid);
		if (mid < p.n) segment(g, s, p, config.blockedColor(), Math.max(start, mid - 1), p.n);
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
		if (r.status == ChartPlotterRoute.OK && r.sparseN > 1) drawSparseRoute(g, s, r);
		if (r.status == ChartPlotterRoute.OK) {
			drawRoutePath(g, s, r.x, r.y, r.n, r.tx, r.ty, c);
		}
		g.setColor(c);
		g.fill(new Ellipse2D.Double(t.getX() - 3.5, t.getY() - 3.5, 7, 7));
		g.draw(new Ellipse2D.Double(t.getX() - 7.5, t.getY() - 7.5, 15, 15));
		String text = r.text();
		if (text != null) tip(g, s.r, t, text);
	}
	private void drawRoutePath(Graphics2D g, ChartPlotterWorldMap.State s, int[] x, int[] y, int n, int tx, int ty, Color c) {
		if (n < 2) return;
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		for (int i = 0; i < n; i++) {
			Point q = map.point(s, x[i], y[i], 0.5, 0.5);
			if (have) line.lineTo(q.getX(), q.getY());
			else {
				line.moveTo(q.getX(), q.getY());
				have = true;
			}
		}
		Point t = map.point(s, tx, ty, 0.5, 0.5);
		if (x[n - 1] != tx || y[n - 1] != ty) line.lineTo(t.getX(), t.getY());
		g.setColor(c);
		g.draw(line);
	}
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
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		g.setColor(CACHE_EDGE);
		for (Map.Entry<Long, ChartPlotterCollisionData.Chunk> e : data.entries()) {
			if (e.getValue().empty()) continue;
			int cx = (int) (e.getKey() >> 32);
			int cy = (int) (long) e.getKey();
			if (!cacheChunkVisible(s, cx, cy)) continue;
			int wx = cx << 3;
			int wy = cy << 3;
			if (data.uncached(cx - 1, cy)) drawCacheEdge(g, s, wx, wy, 0, 0, wx, wy + 7, 0, 1);
			if (data.uncached(cx + 1, cy)) drawCacheEdge(g, s, wx + 7, wy, 1, 0, wx + 7, wy + 7, 1, 1);
			if (data.uncached(cx, cy - 1)) drawCacheEdge(g, s, wx, wy, 0, 0, wx + 7, wy, 1, 0);
			if (data.uncached(cx, cy + 1)) drawCacheEdge(g, s, wx, wy + 7, 0, 1, wx + 7, wy + 7, 1, 1);
		}
		g.setStroke(old);
	}
	private boolean cacheChunkVisible(ChartPlotterWorldMap.State s, int cx, int cy) {
		return s.data.surfaceContainsPosition((cx << 3) + 4, (cy << 3) + 4);
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
	private int hoverHeading(WorldView wv, LocalPoint anchor, ChartPlotterWorldMap.State s, Shape clip) {
		Point m = client.getMouseCanvasPosition();
		if (m == null || client.getCanvas().getMousePosition() == null || client.isMenuOpen() || !clip.contains(m.getX(), m.getY())) return -1;
		if (plugin.suppressPotential(m)) return -1;
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
