package com.chartplotter.overlay;

import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.route.ChartPlotterSparseNodes;
import com.chartplotter.runtime.ChartPlotterWorldMap;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.util.HashMap;
import java.util.Map;

@Singleton
public final class ChartPlotterNodeEditor {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int LINK_DIST = 128;
	private static final int HIT = 2;
	private static final int DOT = 5;
	private static final int VIEW_PAD = 8;
	private static final Color WEB = new Color(80, 210, 255, 90);
	private static final Color LINK = new Color(255, 210, 70, 210);
	private static final Color LINK_DOT = new Color(255, 210, 70, 230);
	private static final Color BLOCK_LINK = new Color(255, 80, 60, 210);
	private static final Color BLOCK_DOT = new Color(255, 80, 60, 230);
	private static final Color NODE_FILL = new Color(230, 250, 255, 230);
	private static final Color DOT_RING = new Color(10, 20, 25, 210);
	private static final Stroke STROKE = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private final Client client;
	private final ChartPlotterConfig config;
	private final ChartPlotterCollisionCache collisionCache;
	private final ChartPlotterSparseNodes sparseNodes;
	private final ChartPlotterWorldMap map;
	private boolean alt;
	private volatile boolean moving;
	private int moveX;
	private int moveY;
	private int moveN;
	private int[] moveLinkX = new int[0];
	private int[] moveLinkY = new int[0];
	private ChartPlotterSparseNodes.Snapshot webNodes;
	private long webDataRev = Long.MIN_VALUE;
	private long webNodeVersion = Long.MIN_VALUE;
	private int webN;
	private int[] webAX = new int[256];
	private int[] webAY = new int[256];
	private int[] webBX = new int[256];
	private int[] webBY = new int[256];
	@Inject
	private ChartPlotterNodeEditor(Client client, ChartPlotterConfig config, ChartPlotterCollisionCache collisionCache, ChartPlotterSparseNodes sparseNodes, ChartPlotterWorldMap map) {
		this.client = client;
		this.config = config;
		this.collisionCache = collisionCache;
		this.sparseNodes = sparseNodes;
		this.map = map;
	}
	void edit(Point m) {
		if (!config.nodeEditor()) return;
		int[] t = map.tile(m);
		if (t == null) return;
		ChartPlotterSparseNodes.Snapshot nodes = sparseNodes.snapshot();
		int i = nodeAt(nodes, t[0], t[1]);
		if (i >= 0) {
			startMove(nodes, i);
			return;
		}
		if (blocked(collisionCache.snapshot(), t[0], t[1])) return;
		sparseNodes.add(t[0], t[1]);
	}
	int[] node(Point m) {
		if (!config.nodeEditor() || moving) return null;
		int[] t = map.tile(m);
		if (t == null) return null;
		ChartPlotterSparseNodes.Snapshot nodes = sparseNodes.snapshot();
		int i = nodeAt(nodes, t[0], t[1]);
		return i < 0 ? null : new int[] {nodes.x[i], nodes.y[i]};
	}
	void remove(int wx, int wy) {
		if (!config.nodeEditor()) return;
		if (selected(wx, wy)) clearMove();
		sparseNodes.remove(wx, wy);
	}
	private void startMove(ChartPlotterSparseNodes.Snapshot nodes, int i) {
		ChartPlotterCollisionData data = collisionCache.snapshot();
		moving = true;
		moveX = nodes.x[i];
		moveY = nodes.y[i];
		moveN = 0;
		ensure(nodes.x.length);
		for (int j = 0; j < nodes.x.length; j++) {
			if (j == i) continue;
			if (dist(nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j]) > LINK_DIST || !clear(data, nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j])) continue;
			moveLinkX[moveN] = nodes.x[j];
			moveLinkY[moveN++] = nodes.y[j];
		}
	}
	void place(Point m) {
		if (!moving) return;
		int[] t = map.tile(m);
		if (t == null || t[0] == moveX && t[1] == moveY) {
			clearMove();
			return;
		}
		ChartPlotterCollisionData data = collisionCache.snapshot();
		ChartPlotterSparseNodes.Snapshot nodes = sparseNodes.snapshot();
		if (!canMove(data, nodes, t[0], t[1])) {
			clearMove();
			return;
		}
		sparseNodes.move(moveX, moveY, t[0], t[1]);
		clearMove();
	}
	void draw(Graphics2D g, ChartPlotterWorldMap.State s) {
		Stroke old = g.getStroke();
		ChartPlotterCollisionData data = collisionCache.snapshot();
		ChartPlotterSparseNodes.Snapshot nodes = web(data);
		g.setStroke(STROKE);
		g.setColor(WEB);
		drawWeb(g, s);
		if (moving) drawMove(g, s, data, nodes);
		else if (mode()) {
			int[] t = map.tile(client.getMouseCanvasPosition(), s);
			if (t != null) {
				g.setColor(LINK);
				int i = nodeAt(nodes, t[0], t[1]);
				if (i >= 0) drawNodeLinks(g, s, data, nodes, i);
				else {
					for (i = 0; i < nodes.x.length; i++) {
						if (dist(t[0], t[1], nodes.x[i], nodes.y[i]) <= LINK_DIST && clear(data, t[0], t[1], nodes.x[i], nodes.y[i])) line(g, s, t[0], t[1], nodes.x[i], nodes.y[i]);
					}
					dot(g, s, t[0], t[1], LINK_DOT, DOT + 2);
				}
			}
		}
		for (int i = 0; i < nodes.x.length; i++) {
			if (!selected(nodes.x[i], nodes.y[i])) dot(g, s, nodes.x[i], nodes.y[i], NODE_FILL, DOT);
		}
		g.setStroke(old);
	}
	private ChartPlotterSparseNodes.Snapshot web(ChartPlotterCollisionData data) {
		long version = sparseNodes.version();
		if (webNodes != null && webDataRev == data.rev && webNodeVersion == version) return webNodes;
		ChartPlotterSparseNodes.Snapshot nodes = sparseNodes.snapshot();
		buildWeb(data, nodes);
		webNodes = nodes;
		webDataRev = data.rev;
		webNodeVersion = nodes.version;
		return nodes;
	}
	private void buildWeb(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes) {
		Map<Long, Bucket> buckets = new HashMap<>();
		for (int i = 0; i < nodes.x.length; i++) {
			int bx = Math.floorDiv(nodes.x[i], LINK_DIST);
			int by = Math.floorDiv(nodes.y[i], LINK_DIST);
			Bucket b = buckets.computeIfAbsent(ChartPlotterCollisionData.key(bx, by), k -> new Bucket());
			b.add(i);
		}
		webN = 0;
		for (int i = 0; i < nodes.x.length; i++) {
			int bx = Math.floorDiv(nodes.x[i], LINK_DIST);
			int by = Math.floorDiv(nodes.y[i], LINK_DIST);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					Bucket b = buckets.get(ChartPlotterCollisionData.key(bx + dx, by + dy));
					if (b == null) continue;
					for (int p = 0; p < b.n; p++) {
						int j = b.v[p];
						if (j <= i) continue;
						if (dist(nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j]) > LINK_DIST || !clear(data, nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j])) continue;
						addWeb(nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j]);
					}
				}
			}
		}
	}
	private void drawWeb(Graphics2D g, ChartPlotterWorldMap.State s) {
		for (int i = 0; i < webN; i++) {
			if (selected(webAX[i], webAY[i]) || selected(webBX[i], webBY[i])) continue;
			line(g, s, webAX[i], webAY[i], webBX[i], webBY[i]);
		}
	}
	private void addWeb(int ax, int ay, int bx, int by) {
		ensureWeb(webN + 1);
		webAX[webN] = ax;
		webAY[webN] = ay;
		webBX[webN] = bx;
		webBY[webN++] = by;
	}
	private void ensureWeb(int c) {
		if (webAX.length >= c) return;
		int n = webAX.length;
		while (n < c) n <<= 1;
		webAX = grow(webAX, n);
		webAY = grow(webAY, n);
		webBX = grow(webBX, n);
		webBY = grow(webBY, n);
	}
	boolean moving() {return moving;}
	void alt(boolean on) {alt = on;}
	private void drawNodeLinks(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int i) {
		for (int j = 0; j < nodes.x.length; j++) {
			if (j == i) continue;
			if (dist(nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j]) <= LINK_DIST && clear(data, nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j])) line(g, s, nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j]);
		}
	}
	private void drawMove(Graphics2D g, ChartPlotterWorldMap.State s, ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes) {
		int[] t = map.tile(client.getMouseCanvasPosition(), s);
		boolean ok = t != null && canMove(data, nodes, t[0], t[1]);
		int wx = ok ? t[0] : moveX;
		int wy = ok ? t[1] : moveY;
		g.setColor(ok ? LINK : BLOCK_LINK);
		for (int i = 0; i < moveN; i++) {
			if (has(nodes, moveLinkX[i], moveLinkY[i])) line(g, s, wx, wy, moveLinkX[i], moveLinkY[i]);
		}
		dot(g, s, wx, wy, ok ? LINK_DOT : BLOCK_DOT, DOT + 2);
	}
	private void line(Graphics2D g, ChartPlotterWorldMap.State s, int ax, int ay, int bx, int by) {
		if (!s.data.surfaceContainsPosition(ax, ay) || !s.data.surfaceContainsPosition(bx, by)) return;
		if (!lineVisible(s, ax, ay, bx, by)) return;
		Point a = map.point(s, ax, ay, 0.5, 0.5);
		Point b = map.point(s, bx, by, 0.5, 0.5);
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private void dot(Graphics2D g, ChartPlotterWorldMap.State s, int wx, int wy, Color c, int r) {
		if (!s.data.surfaceContainsPosition(wx, wy)) return;
		if (!pointVisible(s, wx, wy)) return;
		Point p = map.point(s, wx, wy, 0.5, 0.5);
		g.setColor(DOT_RING);
		g.fill(new Ellipse2D.Double(p.getX() - r / 2.0 - 1, p.getY() - r / 2.0 - 1, r + 2, r + 2));
		g.setColor(c);
		g.fill(new Ellipse2D.Double(p.getX() - r / 2.0, p.getY() - r / 2.0, r, r));
		int cr = Math.max(3, r - 2);
		g.setColor(Color.RED);
		g.fill(new Ellipse2D.Double(p.getX() - cr / 2.0, p.getY() - cr / 2.0, cr, cr));
	}
	private boolean clear(ChartPlotterCollisionData data, int ax, int ay, int bx, int by) {
		int lax = ax * TS + TS / 2;
		int lay = ay * TS + TS / 2;
		int dx = bx * TS + TS / 2 - lax;
		int dy = by * TS + TS / 2 - lay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / 32;
		if (steps < 1) steps = 1;
		int px = Integer.MIN_VALUE;
		int py = Integer.MIN_VALUE;
		for (int i = 0; i <= steps; i++) {
			int x = Math.floorDiv(lax + dx * i / steps, TS);
			int y = Math.floorDiv(lay + dy * i / steps, TS);
			if (x == px && y == py) continue;
			px = x;
			py = y;
			if (data.flagAt(x, y) != ChartPlotterCollisionCache.OPEN) return false;
		}
		return true;
	}
	private boolean canMove(ChartPlotterCollisionData data, ChartPlotterSparseNodes.Snapshot nodes, int wx, int wy) {
		if (blocked(data, wx, wy) || occupied(nodes, wx, wy)) return false;
		for (int i = 0; i < moveN; i++) {
			if (!has(nodes, moveLinkX[i], moveLinkY[i])) continue;
			if (dist(wx, wy, moveLinkX[i], moveLinkY[i]) > LINK_DIST || !clear(data, wx, wy, moveLinkX[i], moveLinkY[i])) return false;
		}
		return true;
	}
	private boolean occupied(ChartPlotterSparseNodes.Snapshot nodes, int wx, int wy) {
		for (int i = 0; i < nodes.x.length; i++) {
			if (selected(nodes.x[i], nodes.y[i])) continue;
			if (dist(wx, wy, nodes.x[i], nodes.y[i]) <= HIT) return true;
		}
		return false;
	}
	private boolean has(ChartPlotterSparseNodes.Snapshot nodes, int wx, int wy) {
		for (int i = 0; i < nodes.x.length; i++) {
			if (nodes.x[i] == wx && nodes.y[i] == wy) return true;
		}
		return false;
	}
	private boolean selected(int wx, int wy) {return moving && wx == moveX && wy == moveY;}
	private void ensure(int c) {
		if (moveLinkX.length >= c) return;
		moveLinkX = new int[c];
		moveLinkY = new int[c];
	}
	private void clearMove() {
		moving = false;
		moveN = 0;
	}
	private boolean mode() {return config.nodeEditor() && alt && !client.isMenuOpen();}
	private static int nodeAt(ChartPlotterSparseNodes.Snapshot nodes, int wx, int wy) {
		for (int i = 0; i < nodes.x.length; i++) {
			if (dist(wx, wy, nodes.x[i], nodes.y[i]) <= HIT) return i;
		}
		return -1;
	}
	private static boolean blocked(ChartPlotterCollisionData data, int wx, int wy) {return data.flagAt(wx, wy) == ChartPlotterCollisionCache.BLOCKED;}
	private static int dist(int ax, int ay, int bx, int by) {return ChartPlotterMath.chebyshev(ax, ay, bx, by);}
	private static boolean pointVisible(ChartPlotterWorldMap.State s, int wx, int wy) {
		double minX = s.pos.getX() - s.wt / 2.0 - VIEW_PAD;
		double minY = s.pos.getY() - s.ht / 2.0 - VIEW_PAD;
		double maxX = s.pos.getX() + s.wt / 2.0 + VIEW_PAD;
		double maxY = s.pos.getY() + s.ht / 2.0 + VIEW_PAD;
		return wx >= minX && wx <= maxX && wy >= minY && wy <= maxY;
	}
	private static boolean lineVisible(ChartPlotterWorldMap.State s, int ax, int ay, int bx, int by) {
		double minX = s.pos.getX() - s.wt / 2.0 - VIEW_PAD;
		double minY = s.pos.getY() - s.ht / 2.0 - VIEW_PAD;
		double maxX = s.pos.getX() + s.wt / 2.0 + VIEW_PAD;
		double maxY = s.pos.getY() + s.ht / 2.0 + VIEW_PAD;
		return Math.max(ax, bx) >= minX && Math.min(ax, bx) <= maxX && Math.max(ay, by) >= minY && Math.min(ay, by) <= maxY;
	}
	private static int[] grow(int[] v, int n) {
		int[] w = new int[n];
		System.arraycopy(v, 0, w, 0, v.length);
		return w;
	}
	private static final class Bucket {
		private int[] v = new int[8];
		private int n;
		private void add(int i) {
			if (n == v.length) v = grow(v, v.length << 1);
			v[n++] = i;
		}
	}
}
