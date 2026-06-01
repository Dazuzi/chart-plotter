package com.chartplotter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
@Singleton
final class ChartPlotterNodeEditor {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int LINK_DIST = 128;
	private static final int HIT = 2;
	private static final int DOT = 5;
	private static final Color WEB = new Color(80, 210, 255, 90);
	private static final Color LINK = new Color(255, 210, 70, 210);
	private static final Color LINK_DOT = new Color(255, 210, 70, 230);
	private static final Color BLOCK_LINK = new Color(255, 80, 60, 210);
	private static final Color BLOCK_DOT = new Color(255, 80, 60, 230);
	private static final Color NODE_FILL = new Color(230, 250, 255, 230);
	private static final Color DOT_RING = new Color(10, 20, 25, 210);
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
	@Inject
	private ChartPlotterNodeEditor(Client client, ChartPlotterConfig config, ChartPlotterCollisionCache collisionCache, ChartPlotterSparseNodes sparseNodes, ChartPlotterWorldMap map) {
		this.client = client;
		this.config = config;
		this.collisionCache = collisionCache;
		this.sparseNodes = sparseNodes;
		this.map = map;
	}
	void add(Point m) {
		if (!config.nodeEditor()) return;
		int[] t = map.tile(m);
		if (t == null) return;
		int i = sparseNodes.nodeAt(t[0], t[1], HIT);
		if (i >= 0) {
			sparseNodes.remove(i);
			return;
		}
		if (blocked(collisionCache.snapshot(), t[0], t[1])) return;
		sparseNodes.add(t[0], t[1]);
	}
	void startMove(Point m) {
		if (!config.nodeEditor()) return;
		int[] t = map.tile(m);
		if (t == null) return;
		ChartPlotterSparseNodes.Snapshot nodes = sparseNodes.snapshot();
		int i = nodeAt(nodes, t[0], t[1]);
		if (i < 0) return;
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
		ChartPlotterSparseNodes.Snapshot nodes = sparseNodes.snapshot();
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(WEB);
		for (int i = 0; i < nodes.x.length; i++) {
			for (int j = i + 1; j < nodes.x.length; j++) {
				if (selected(nodes.x[i], nodes.y[i]) || selected(nodes.x[j], nodes.y[j])) continue;
				if (dist(nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j]) > LINK_DIST || !clear(data, nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j])) continue;
				line(g, s, nodes.x[i], nodes.y[i], nodes.x[j], nodes.y[j]);
			}
		}
		if (moving) drawMove(g, s, data, nodes);
		else if (mode()) {
			int[] t = map.tile(client.getMouseCanvasPosition(), s);
			if (t != null) {
				g.setColor(LINK);
				for (int i = 0; i < nodes.x.length; i++) {
					if (dist(t[0], t[1], nodes.x[i], nodes.y[i]) <= LINK_DIST && clear(data, t[0], t[1], nodes.x[i], nodes.y[i])) line(g, s, t[0], t[1], nodes.x[i], nodes.y[i]);
				}
				dot(g, s, t[0], t[1], LINK_DOT, DOT + 2);
			}
		}
		for (int i = 0; i < nodes.x.length; i++) {
			if (!selected(nodes.x[i], nodes.y[i])) dot(g, s, nodes.x[i], nodes.y[i], NODE_FILL, DOT);
		}
		g.setStroke(old);
	}
	boolean moving() {return moving;}
	void alt(boolean on) {alt = on;}
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
		Point a = map.point(s, ax, ay, 0.5, 0.5);
		Point b = map.point(s, bx, by, 0.5, 0.5);
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private void dot(Graphics2D g, ChartPlotterWorldMap.State s, int wx, int wy, Color c, int r) {
		if (!s.data.surfaceContainsPosition(wx, wy)) return;
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
}
