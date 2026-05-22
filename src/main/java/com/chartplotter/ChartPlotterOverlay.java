package com.chartplotter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ColorUtil;
public class ChartPlotterOverlay extends Overlay {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int TURN = 128;
	private static final int EXT = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	@Inject
	ChartPlotterOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}
	@Override
	public Dimension render(Graphics2D g) {
		if (!plugin.isSailing() || !config.worldEnabled()) return null;
		WorldView top = client.getTopLevelWorldView();
		WorldEntity ship = plugin.getShip();
		if (ship == null || top == null) return null;
		LocalPoint anchor = ship.getTargetLocation();
		LocalPoint center = ship.getLocalLocation();
		if (anchor == null) anchor = center;
		if (anchor == null || center == null) return null;
		WorldEntityConfig wc = ship.getConfig();
		float[] rx = rectX(wc);
		float[] ry = rectY(wc);
		int from = ChartPlotterPlugin.norm(ship.getTargetOrientation());
		int course = plugin.course(ship);
		int mouse = hoverHeading(top, center);
		Path cur = path(top, anchor, from, course);
		Path pot = mouse >= 0 ? path(top, anchor, from, mouse) : null;
		int skip = pot != null ? match(cur, pot) : 0;
		Stroke prev = g.getStroke();
		g.setStroke(new BasicStroke(config.worldLineWidth(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		draw(g, top, cur, rx, ry, config.worldLineColor(), skip);
		if (pot != null) draw(g, top, pot, rx, ry, config.worldPotentialColor(), 0);
		g.setStroke(prev);
		return null;
	}
	Path path(WorldView wv, LocalPoint anchor, int from, int target) {
		Tile[][][] tiles = wv.getScene().getExtendedTiles();
		int cap = limit(anchor);
		Path p = new Path(cap + 1);
		p.start = from;
		p.x[p.n] = anchor.getX();
		p.y[p.n] = anchor.getY();
		p.o[p.n] = from;
		p.n++;
		int posX = 0;
		int posY = 0;
		int o = from;
		double speed = plugin.speed();
		double accel = plugin.accel();
		if (plugin.reversing()) {
			speed *= -1;
			accel *= -1;
		}
		int dir = ChartPlotterPlugin.angleDir(from, target, plugin.turnDir());
		for (int i = 0; i < cap; i++) {
			if (o != target) o = ChartPlotterPlugin.norm(o + TURN * dir);
			speed += accel;
			double max = Math.max(plugin.maxSpeed(), Math.abs(plugin.speed()));
			speed = plugin.reversing() ? Math.max(-max, speed) : Math.min(max, speed);
			Point v = velocity(speed, ChartPlotterPlugin.orientationToDegrees(o));
			if (v.getX() == 0 && v.getY() == 0) break;
			posX += v.getX();
			posY += v.getY();
			int lx = anchor.getX() + posX;
			int ly = anchor.getY() + posY;
			if (!loaded(tiles, wv.getPlane(), lx, ly)) break;
			p.x[p.n] = lx;
			p.y[p.n] = ly;
			p.o[p.n] = o;
			p.n++;
		}
		return p;
	}
	private void draw(Graphics2D g, WorldView wv, Path p, float[] rx, float[] ry, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) return;
		float[] z = new float[]{0, 0, 0, 0};
		int[] cx = new int[4];
		int[] cy = new int[4];
		int[] px = new int[4];
		int[] py = new int[4];
		Path2D.Double s = new Path2D.Double();
		boolean have = false;
		int sA = color.getAlpha();
		for (int i = 0; i < p.n; i++) {
			if (!project(wv, p, i, rx, ry, z, cx, cy)) {
				have = false;
				continue;
			}
			if (i < skip) {
				copy(cx, cy, px, py);
				have = true;
				continue;
			}
			double f = (i + 1) / (double) p.n;
			int a = (int) (sA * (1 - f));
			if (a > 0) {
				g.setColor(ColorUtil.colorWithAlpha(color, a));
				s.reset();
				boolean d = false;
				if (have && p.o[i] == prev(p, i)) {
					rails(s, px, py, cx, cy);
					d = true;
				}
				if (box(p, i) || i == 0) {
					box(s, cx, cy, open(p, i));
					d = true;
				}
				if (d) g.draw(s);
			}
			copy(cx, cy, px, py);
			have = true;
		}
	}
	private boolean project(WorldView wv, Path p, int i, float[] rx, float[] ry, float[] z, int[] cx, int[] cy) {
		Perspective.modelToCanvas(client, wv, 4, p.x[i], p.y[i], 0, p.o[i], rx, ry, z, cx, cy);
		return cx[0] != Integer.MIN_VALUE && cx[1] != Integer.MIN_VALUE && cx[2] != Integer.MIN_VALUE && cx[3] != Integer.MIN_VALUE;
	}
	private static void rails(Path2D p, int[] px, int[] py, int[] cx, int[] cy) {
		p.moveTo(px[0], py[0]);
		p.lineTo(cx[0], cy[0]);
		p.moveTo(px[3], py[3]);
		p.lineTo(cx[3], cy[3]);
	}
	private static void box(Path2D p, int[] x, int[] y, boolean open) {
		p.moveTo(x[0], y[0]);
		p.lineTo(x[1], y[1]);
		p.lineTo(x[2], y[2]);
		p.lineTo(x[3], y[3]);
		if (!open) p.lineTo(x[0], y[0]);
	}
	private static boolean box(Path p, int i) {return p.o[i] != prev(p, i);}
	private static boolean open(Path p, int i) {return i + 1 < p.n && p.o[i + 1] == p.o[i];}
	private static int prev(Path p, int i) {return i > 0 ? p.o[i - 1] : p.start;}
	private static void copy(int[] sx, int[] sy, int[] dx, int[] dy) {
		for (int i = 0; i < 4; i++) {
			dx[i] = sx[i];
			dy[i] = sy[i];
		}
	}
	static int match(Path a, Path b) {
		int n = Math.min(a.n, b.n);
		for (int i = 0; i < n; i++) {
			if (a.x[i] != b.x[i] || a.y[i] != b.y[i] || a.o[i] != b.o[i]) return i;
		}
		return n;
	}
	private int hoverHeading(WorldView wv, LocalPoint anchor) {
		Point m = client.getMouseCanvasPosition();
		if (m == null || client.getCanvas().getMousePosition() == null || client.isMenuOpen()) return -1;
		int mini = ChartPlotterMinimapOverlay.mouseHeading(client, anchor, m);
		if (mini >= 0) return mini;
		if (!viewport(m) || !activeHeading(wv)) return -1;
		return mouseHeading(client, wv, anchor);
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
	static int mouseHeading(Client client, WorldView wv, LocalPoint anchor) {
		Point mouse = client.getMouseCanvasPosition();
		return mouseHeading(client, wv, anchor, mouse);
	}
	static int mouseHeading(Client client, WorldView wv, LocalPoint anchor, Point mouse) {
		int mini = ChartPlotterMinimapOverlay.mouseHeading(client, anchor, mouse);
		if (mini >= 0) return mini;
		Point center = Perspective.localToCanvas(client, anchor, 0);
		if (center == null || mouse == null) return -1;
		int n = 16;
		int[] lx = new int[n];
		int[] ly = new int[n];
		float[] x = new float[]{0, 0};
		float[] y = new float[]{1000, -1000};
		float[] z = new float[]{0, 0};
		for (int i = 0; i < n; i++) {
			int[] cx = new int[2];
			int[] cy = new int[2];
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
	private static float[] rectX(WorldEntityConfig wc) {
		float ox = wc != null ? wc.getBoundsX() : 0;
		float hw = wc != null ? wc.getBoundsWidth() / 2f : TS;
		return new float[]{ox + hw, ox + hw, ox - hw, ox - hw};
	}
	private static float[] rectY(WorldEntityConfig wc) {
		float oy = wc != null ? wc.getBoundsY() : 0;
		float hh = wc != null ? wc.getBoundsHeight() / 2f : TS;
		return new float[]{oy - hh, oy + hh, oy + hh, oy - hh};
	}
	private static boolean loaded(Tile[][][] tiles, int plane, int lx, int ly) {
		int tx = Math.floorDiv(lx, TS) + EXT;
		int ty = Math.floorDiv(ly, TS) + EXT;
		return plane >= 0 && plane < tiles.length && tx >= 0 && ty >= 0 && tx < tiles[plane].length && ty < tiles[plane][tx].length && tiles[plane][tx][ty] != null;
	}
	private static int limit(LocalPoint anchor) {
		int ax = Math.floorDiv(anchor.getX(), TS) + EXT;
		int ay = Math.floorDiv(anchor.getY(), TS) + EXT;
		int edge = Math.max(Math.max(ax, Constants.EXTENDED_SCENE_SIZE - ax), Math.max(ay, Constants.EXTENDED_SCENE_SIZE - ay));
		return edge * 8 + 32;
	}
	private static Point velocity(double speed, double angle) {
		double dx = Math.cos(Math.toRadians(angle)) * speed * 128;
		double dy = Math.sin(Math.toRadians(angle)) * speed * 128;
		return new Point(ChartPlotterPlugin.snap(ChartPlotterPlugin.round(dx)), ChartPlotterPlugin.snap(ChartPlotterPlugin.round(dy)));
	}
	private static int side(int x1, int y1, int x2, int y2, int x, int y) {return (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1);}
	static final class Path {
		final int[] x;
		final int[] y;
		final int[] o;
		int start;
		int n;
		private Path(int cap) {
			x = new int[cap];
			y = new int[cap];
			o = new int[cap];
		}
	}
}
