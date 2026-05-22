package com.chartplotter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
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
@Slf4j
public class ChartPlotterOverlay extends Overlay {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	private static final int TURN = 128;
	private static final int EXT = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private int debugTick = -1;
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
		if (!plugin.isSailing()) return null;
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
		int mouse = mouseHeading(client, top, center);
		Path cur = path(top, anchor, from, course);
		Path pot = mouse >= 0 ? path(top, anchor, from, mouse) : null;
		int skip = pot != null ? match(cur, pot) : 0;
		if (config.debugProjection()) debug(ship, anchor, center, from, course, mouse, cur, pot, skip);
		Stroke prev = g.getStroke();
		g.setStroke(new BasicStroke(config.lineWidth()));
		draw(g, top, cur, rx, ry, config.lineColor(), skip, true);
		if (pot != null) draw(g, top, pot, rx, ry, config.potentialColor(), 0, false);
		g.setStroke(prev);
		return null;
	}
	private Path path(WorldView wv, LocalPoint anchor, int from, int target) {
		Tile[][][] tiles = wv.getScene().getExtendedTiles();
		int cap = limit(anchor);
		Path p = new Path(cap);
		p.start = from;
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
	private void draw(Graphics2D g, WorldView wv, Path p, float[] rx, float[] ry, Color color, int skip, boolean firstBox) {
		if (p.n < 2 || skip >= p.n) return;
		float[] z = new float[]{0, 0, 0, 0};
		int[] cx = new int[4];
		int[] cy = new int[4];
		int[] px = new int[4];
		int[] py = new int[4];
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
				if (have && p.o[i] == prev(p, i)) rails(g, px, py, cx, cy);
				if (box(p, i) || firstBox && i == skip) g.drawPolygon(cx, cy, 4);
			}
			copy(cx, cy, px, py);
			have = true;
		}
	}
	private boolean project(WorldView wv, Path p, int i, float[] rx, float[] ry, float[] z, int[] cx, int[] cy) {
		Perspective.modelToCanvas(client, wv, 4, p.x[i], p.y[i], 0, p.o[i], rx, ry, z, cx, cy);
		return cx[0] != Integer.MIN_VALUE && cx[1] != Integer.MIN_VALUE && cx[2] != Integer.MIN_VALUE && cx[3] != Integer.MIN_VALUE;
	}
	private static void rails(Graphics2D g, int[] px, int[] py, int[] cx, int[] cy) {
		g.drawLine((px[0] + px[1]) / 2, (py[0] + py[1]) / 2, (cx[0] + cx[1]) / 2, (cy[0] + cy[1]) / 2);
		g.drawLine((px[2] + px[3]) / 2, (py[2] + py[3]) / 2, (cx[2] + cx[3]) / 2, (cy[2] + cy[3]) / 2);
	}
	private static boolean box(Path p, int i) {return p.o[i] != prev(p, i);}
	private static int prev(Path p, int i) {return i > 0 ? p.o[i - 1] : p.start;}
	private static void copy(int[] sx, int[] sy, int[] dx, int[] dy) {
		for (int i = 0; i < 4; i++) {
			dx[i] = sx[i];
			dy[i] = sy[i];
		}
	}
	private static int match(Path a, Path b) {
		int n = Math.min(a.n, b.n);
		for (int i = 0; i < n; i++) {
			if (a.x[i] != b.x[i] || a.y[i] != b.y[i] || a.o[i] != b.o[i]) return i;
		}
		return n;
	}
	static int mouseHeading(Client client, WorldView wv, LocalPoint anchor) {
		Point center = Perspective.localToCanvas(client, anchor, 0);
		Point mouse = client.getMouseCanvasPosition();
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
	private void debug(WorldEntity ship, LocalPoint anchor, LocalPoint center, int from, int course, int mouse, Path cur, Path pot, int skip) {
		int tick = client.getTickCount();
		if (debugTick == tick) return;
		debugTick = tick;
		log.info("chartplotter tick={} actual={},{} target={},{} orient={} targetOrient={} from={} course={} mouse={} speed={} accel={} reverse={} turn={} curN={} potN={} match={} cur0={} pot0={} curM={} potM={} curNxt={} potNxt={}", tick, x(center), y(center), x(anchor), y(anchor), ship.getOrientation(), ship.getTargetOrientation(), from, course, mouse, plugin.speed(), plugin.accel(), plugin.reversing(), plugin.turnDir(), cur.n, pot != null ? pot.n : -1, skip, step(cur, 0), step(pot, 0), step(cur, skip), step(pot, skip), step(cur, skip + 1), step(pot, skip + 1));
	}
	private static String step(Path p, int i) {
		if (p == null || i < 0 || i >= p.n) return "-";
		return p.x[i] + "," + p.y[i] + "," + p.o[i];
	}
	private static int x(LocalPoint p) {return p != null ? p.getX() : -1;}
	private static int y(LocalPoint p) {return p != null ? p.getY() : -1;}
	private static final class Path {
		private final int[] x;
		private final int[] y;
		private final int[] o;
		private int start;
		private int n;
		private Path(int cap) {
			x = new int[cap];
			y = new int[cap];
			o = new int[cap];
		}
	}
}
