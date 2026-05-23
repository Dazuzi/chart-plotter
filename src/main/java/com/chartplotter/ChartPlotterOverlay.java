package com.chartplotter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
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
	private static final int STEP = 32;
	private static final int DOT = 4;
	private static final int EDGE = 8;
	private static final int VOID = 0xffffff;
	private static final double FADE = 0.72;
	private static final Color OK = ColorUtil.colorWithAlpha(Color.GREEN, 160);
	private static final Color SAFE = ColorUtil.colorWithAlpha(Color.CYAN, 180);
	private static final Color WARN = ColorUtil.colorWithAlpha(Color.YELLOW, 180);
	private static final Color HIT = ColorUtil.colorWithAlpha(Color.RED, 220);
	private static final Color DIR = ColorUtil.colorWithAlpha(Color.MAGENTA, 220);
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterCollisionCache collisionCache;
	private String lastDebug;
	@Inject
	ChartPlotterOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterCollisionCache collisionCache) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.collisionCache = collisionCache;
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
		Path cur = path(top, wc, anchor, from, course);
		Path pot = mouse >= 0 ? path(top, wc, anchor, from, mouse) : null;
		int skip = pot != null ? match(cur, pot) : 0;
		ChartPlotterCollisionDebug debug = config.collisionDebug();
		Stroke prev = g.getStroke();
		if (debug == ChartPlotterCollisionDebug.MASK) mask(g);
		g.setStroke(new BasicStroke(config.worldLineWidth(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		drawRoute(g, top, plugin.route());
		draw(g, top, cur, rx, ry, config.worldLineColor(), skip);
		if (pot != null) draw(g, top, pot, rx, ry, config.worldPotentialColor(), 0);
		if (debug != ChartPlotterCollisionDebug.OFF) drawDebug(g, top, wc, center, ship.getOrientation(), cur, pot);
		if (config.collisionLog()) logDebug(top, wc, anchor, from, course, mouse, cur, pot);
		g.setStroke(prev);
		return null;
	}
	Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target) {
		return path(wv, wc, anchor, from, target, limit(anchor), true);
	}
	Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap) {
		return path(wv, wc, anchor, from, target, cap, false);
	}
	private Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, boolean checkLoaded) {
		Tile[][][] tiles = wv.getScene().getExtendedTiles();
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
		if (speed == 0) o = target;
		if (plugin.reversing()) {
			speed *= -1;
			accel *= -1;
		}
		from = o;
		p.start = from;
		p.o[0] = from;
		int dir = ChartPlotterPlugin.angleDir(from, target, plugin.turnDir());
		ChartPlotterCollisionCache cache = config.cacheCollision() ? collisionCache : null;
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
			if (checkLoaded && !loaded(tiles, wv.getPlane(), lx, ly)) break;
			Block b = config.stopAtCollision() ? block(wv, wc, cache, p.x[p.n - 1], p.y[p.n - 1], p.o[p.n - 1], lx, ly, o) : null;
			if (b != null) {
				if (b.sx != p.x[p.n - 1] || b.sy != p.y[p.n - 1] || b.so != p.o[p.n - 1]) {
					p.x[p.n] = b.sx;
					p.y[p.n] = b.sy;
					p.o[p.n] = b.so;
					p.n++;
				}
				p.blocked = true;
				p.bx = b.bx;
				p.by = b.by;
				p.bo = b.bo;
				p.hx = b.h.x;
				p.hy = b.h.y;
				p.htx = b.h.tx;
				p.hty = b.h.ty;
				p.hf = b.h.f;
				p.hk = b.h.k;
				break;
			}
			p.x[p.n] = lx;
			p.y[p.n] = ly;
			p.o[p.n] = o;
			p.n++;
		}
		return p;
	}
	private void draw(Graphics2D g, WorldView wv, Path p, float[] rx, float[] ry, Color color, int skip) {
		if (p.n < 2 || skip >= p.n) {
			if (p.blocked && p.n == 1 && skip < p.n) drawBlock(g, wv, p, rx, ry, color);
			return;
		}
		float[] z = new float[]{0, 0, 0, 0};
		int[] cx = new int[4];
		int[] cy = new int[4];
		int[] px = new int[4];
		int[] py = new int[4];
		Path2D.Double s = new Path2D.Double();
		boolean have = false;
		int sA = color.getAlpha();
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
			int a = alpha(sA, i, p.n);
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
	private void drawBlock(Graphics2D g, WorldView wv, Path p, float[] rx, float[] ry, Color color) {
		float[] z = new float[]{0, 0, 0, 0};
		int[] cx = new int[4];
		int[] cy = new int[4];
		if (missing(wv, p, 0, rx, ry, z, cx, cy)) return;
		Path2D.Double s = new Path2D.Double();
		box(s, cx, cy, false);
		s.moveTo(cx[0], cy[0]);
		s.lineTo(cx[2], cy[2]);
		s.moveTo(cx[1], cy[1]);
		s.lineTo(cx[3], cy[3]);
		g.setColor(color);
		g.draw(s);
	}
	private void drawRoute(Graphics2D g, WorldView wv, ChartPlotterRoute r) {
		if (r == null || r.status != ChartPlotterRoute.OK || r.n < 2) return;
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		for (int i = 0; i < r.n; i++) {
			int lx = (r.x[i] - wv.getBaseX()) * TS + TS / 2;
			int ly = (r.y[i] - wv.getBaseY()) * TS + TS / 2;
			Point q = Perspective.localToCanvas(client, new LocalPoint(lx, ly, wv), 0);
			if (q == null) {
				have = false;
				continue;
			}
			if (have) line.lineTo(q.getX(), q.getY());
			else {
				line.moveTo(q.getX(), q.getY());
				have = true;
			}
		}
		g.setColor(config.worldChartColor());
		g.draw(line);
	}
	private boolean missing(WorldView wv, Path p, int i, float[] rx, float[] ry, float[] z, int[] cx, int[] cy) {
		Perspective.modelToCanvas(client, wv, 4, p.x[i], p.y[i], 0, p.o[i], rx, ry, z, cx, cy);
		return cx[0] == Integer.MIN_VALUE || cx[1] == Integer.MIN_VALUE || cx[2] == Integer.MIN_VALUE || cx[3] == Integer.MIN_VALUE;
	}
	private void mask(Graphics2D g) {
		g.setColor(Color.BLACK);
		g.fillRect(client.getViewportXOffset(), client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight());
	}
	private void drawDebug(Graphics2D g, WorldView wv, WorldEntityConfig wc, LocalPoint loc, int o, Path cur, Path pot) {
		CollisionData[] maps = wv.getCollisionMaps();
		int plane = wv.getPlane();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) return;
		int[][] flags = maps[plane].getFlags();
		ChartPlotterCollisionCache cache = config.cacheCollision() ? collisionCache : null;
		drawFootprint(g, wv, wc, cache, loc.getX(), loc.getY(), ChartPlotterPlugin.norm(o), flags, Integer.MIN_VALUE, 0, 0, OK);
		drawStop(g, wv, wc, cache, cur, flags);
		if (pot != null) drawStop(g, wv, wc, cache, pot, flags);
		drawDebugText(g, loc, cur, pot);
	}
	private void drawStop(Graphics2D g, WorldView wv, WorldEntityConfig wc, ChartPlotterCollisionCache cache, Path p, int[][] flags) {
		if (!p.blocked || p.n < 1) return;
		drawFootprint(g, wv, wc, cache, p.x[p.n - 1], p.y[p.n - 1], p.o[p.n - 1], flags, Integer.MIN_VALUE, 0, 0, SAFE);
		drawFootprint(g, wv, wc, cache, p.bx, p.by, p.bo, flags, p.x[p.n - 1], p.y[p.n - 1], p.o[p.n - 1], WARN);
	}
	private void drawFootprint(Graphics2D g, WorldView wv, WorldEntityConfig wc, ChartPlotterCollisionCache cache, int x, int y, int o, int[][] flags, int px, int py, int po, Color ok) {
		float[] rx = rectX(wc);
		float[] ry = rectY(wc);
		boolean moving = px != Integer.MIN_VALUE;
		int minX = min(rx);
		int maxX = max(rx);
		int minY = min(ry);
		int maxY = max(ry);
		for (int ix = minX;; ix = next(ix, maxX)) {
			for (int iy = minY;; iy = next(iy, maxY)) {
				if (edge(ix, iy, minX, maxX, minY, maxY)) {
					int qx = rotateX(x, o, ix, iy);
					int qy = rotateY(y, o, ix, iy);
					Hit hit = moving ? hitPath(cache, wv, flags, rotateX(px, po, ix, iy), rotateY(py, po, ix, iy), qx, qy) : hitFull(cache, wv, flags, qx, qy);
					Point q = Perspective.localToCanvas(client, new LocalPoint(qx, qy, wv), 0);
					if (q != null) {
						g.setColor(hit == null ? ok : hit.k == 1 ? HIT : DIR);
						g.fillOval(q.getX() - DOT / 2, q.getY() - DOT / 2, DOT, DOT);
					}
				}
				if (iy == maxY) break;
			}
			if (ix == maxX) break;
		}
	}
	private void drawDebugText(Graphics2D g, LocalPoint loc, Path cur, Path pot) {
		int x = client.getViewportXOffset() + 8;
		int y = client.getViewportYOffset() + 16;
		g.setColor(Color.WHITE);
		y = drawDebugText(g, loc, cur, "cur", x, y);
		if (pot != null) drawDebugText(g, loc, pot, "pot", x, y);
	}
	private int drawDebugText(Graphics2D g, LocalPoint loc, Path p, String name, int x, int y) {
		if (!p.blocked) return y;
		int d = Math.max(Math.abs(p.hx - loc.getX()), Math.abs(p.hy - loc.getY())) / TS;
		g.drawString(name + " " + kind(p.hk) + " tile=" + p.htx + "," + p.hty + " dist=" + d + " flag=0x" + Integer.toHexString(p.hf), x, y);
		return y + 14;
	}
	private void logDebug(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int course, int mouse, Path cur, Path pot) {
		String s = blockLog(wv, wc, anchor, from, course, mouse, cur, "cur");
		if (s == null && pot != null) s = blockLog(wv, wc, anchor, from, course, mouse, pot, "pot");
		if (s == null || s.equals(lastDebug)) return;
		lastDebug = s;
		System.out.println(s);
	}
	private String blockLog(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int course, int mouse, Path p, String name) {
		if (!p.blocked) return null;
		int wx = wv.getBaseX() + p.htx;
		int wy = wv.getBaseY() + p.hty;
		int region = (wx >> 6 << 8) | wy >> 6;
		int chunkX = wx >> 3;
		int chunkY = wy >> 3;
		int d = Math.max(Math.abs(p.hx - anchor.getX()), Math.abs(p.hy - anchor.getY())) / TS;
		CollisionData[] maps = wv.getCollisionMaps();
		int[][] flags = maps != null && wv.getPlane() >= 0 && wv.getPlane() < maps.length && maps[wv.getPlane()] != null ? maps[wv.getPlane()].getFlags() : null;
		return "ChartPlotter collision " + name + " kind=" + kind(p.hk) + " dist=" + d + " flag=0x" + Integer.toHexString(p.hf) + " bits=" + bits(p.hf) + " scene=" + p.htx + "," + p.hty + " world=" + wx + "," + wy + " chunk=" + chunkX + "," + chunkY + " region=" + region + " plane=" + wv.getPlane() + " base=" + wv.getBaseX() + "," + wv.getBaseY() + " localHit=" + p.hx + "," + p.hy + " safe=" + p.x[p.n - 1] + "," + p.y[p.n - 1] + "," + p.o[p.n - 1] + " block=" + p.bx + "," + p.by + "," + p.bo + " anchor=" + anchor.getX() + "," + anchor.getY() + " from=" + from + " course=" + course + " mouse=" + mouse + " speed=" + plugin.speed() + " accel=" + plugin.accel() + " reverse=" + plugin.reversing() + " max=" + plugin.maxSpeed() + " edge=" + EDGE + " bounds=" + bounds(wc) + " regions=" + java.util.Arrays.toString(wv.getMapRegions()) + " " + collisionCache.stats() + " grid=" + grid(flags, p.htx, p.hty);
	}
	private static String bounds(WorldEntityConfig wc) {
		if (wc == null) return "null";
		return wc.getBoundsX() + "," + wc.getBoundsY() + "," + wc.getBoundsWidth() + "," + wc.getBoundsHeight();
	}
	private static String grid(int[][] flags, int x, int y) {
		if (flags == null) return "null";
		StringBuilder s = new StringBuilder();
		for (int yy = y + 1; yy >= y - 1; yy--) {
			if (yy < y + 1) s.append("/");
			for (int xx = x - 1; xx <= x + 1; xx++) {
				if (xx > x - 1) s.append(",");
				s.append(inside(flags, xx, yy) ? Integer.toHexString(flags[xx][yy]) : "out");
			}
		}
		return s.toString();
	}
	private static String bits(int f) {
		StringBuilder s = new StringBuilder();
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_FULL, "full");
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_NORTH, "n");
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_EAST, "e");
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, "s");
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_WEST, "w");
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST, "ne");
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST, "se");
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST, "sw");
		bit(s, f, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST, "nw");
		return s.length() == 0 ? "none" : s.toString();
	}
	private static void bit(StringBuilder s, int f, int b, String n) {
		if ((f & b) == 0) return;
		if (s.length() > 0) s.append("|");
		s.append(n);
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
	private static int alpha(int a, int i, int n) {
		double f = (i + 1) / (double) n;
		if (f <= FADE) return a;
		double t = (f - FADE) / (1 - FADE);
		return (int) (a * (1 - t));
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
	private static Block block(WorldView wv, WorldEntityConfig wc, ChartPlotterCollisionCache cache, int ax, int ay, int ao, int bx, int by, int bo) {
		CollisionData[] maps = wv.getCollisionMaps();
		int plane = wv.getPlane();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) return null;
		int[][] flags = maps[plane].getFlags();
		int dx = bx - ax;
		int dy = by - ay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / STEP;
		if (steps < 1) steps = 1;
		int px = ax;
		int py = ay;
		int po = ao;
		for (int i = 1; i <= steps; i++) {
			int qx = ax + dx * i / steps;
			int qy = ay + dy * i / steps;
			Hit h = hitFootprint(cache, wv, flags, wc, px, py, po, qx, qy, bo);
			if (h != null) return new Block(px, py, po, qx, qy, bo, h);
			px = qx;
			py = qy;
			po = bo;
		}
		return null;
	}
	private static Hit hitFootprint(ChartPlotterCollisionCache cache, WorldView wv, int[][] flags, WorldEntityConfig wc, int ax, int ay, int ao, int bx, int by, int bo) {
		float[] rx = rectX(wc);
		float[] ry = rectY(wc);
		int minX = min(rx);
		int maxX = max(rx);
		int minY = min(ry);
		int maxY = max(ry);
		for (int x = minX;; x = next(x, maxX)) {
			for (int y = minY;; y = next(y, maxY)) {
				if (edge(x, y, minX, maxX, minY, maxY)) {
					int px = rotateX(ax, ao, x, y);
					int py = rotateY(ay, ao, x, y);
					int qx = rotateX(bx, bo, x, y);
					int qy = rotateY(by, bo, x, y);
					Hit h = hitPath(cache, wv, flags, px, py, qx, qy);
					if (h != null) return h;
				}
				if (y == maxY) break;
			}
			if (x == maxX) break;
		}
		return null;
	}
	private static Hit hitPath(ChartPlotterCollisionCache cache, WorldView wv, int[][] flags, int ax, int ay, int bx, int by) {
		int dx = bx - ax;
		int dy = by - ay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / STEP;
		if (steps < 1) steps = 1;
		int px = Math.floorDiv(ax, TS);
		int py = Math.floorDiv(ay, TS);
		for (int i = 1; i <= steps; i++) {
			int lx = ax + dx * i / steps;
			int ly = ay + dy * i / steps;
			int x = Math.floorDiv(lx, TS);
			int y = Math.floorDiv(ly, TS);
			int f = flag(cache, wv, flags, x, y);
			if (f == ChartPlotterCollisionCache.UNKNOWN) return null;
			Hit h = hitTile(f, lx, ly, x, y, x - px, y - py);
			if (h != null) return h;
			px = x;
			py = y;
		}
		return null;
	}
	private static int rotateX(int cx, int o, int x, int y) {return cx + (int) (((long) Perspective.COSINE[o] * x + (long) Perspective.SINE[o] * y) >> 16);}
	private static int rotateY(int cy, int o, int x, int y) {return cy + (int) (((long) Perspective.COSINE[o] * y - (long) Perspective.SINE[o] * x) >> 16);}
	private static int min(float[] v) {return (int) Math.floor(Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3])));}
	private static int max(float[] v) {return (int) Math.ceil(Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3])));}
	private static int next(int v, int max) {return Math.min(v + STEP, max);}
	private static boolean edge(int x, int y, int minX, int maxX, int minY, int maxY) {return x == minX || x == maxX || y == minY || y == maxY;}
	private static Hit hitFull(ChartPlotterCollisionCache cache, WorldView wv, int[][] flags, int lx, int ly) {
		int x = Math.floorDiv(lx, TS);
		int y = Math.floorDiv(ly, TS);
		int f = flag(cache, wv, flags, x, y);
		if (f == ChartPlotterCollisionCache.UNKNOWN) return null;
		return (f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0 ? new Hit(lx, ly, x, y, f, 1) : null;
	}
	private static Hit hitTile(int f, int lx, int ly, int x, int y, int dx, int dy) {
		if ((f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return new Hit(lx, ly, x, y, f, 1);
		dx = Integer.signum(dx);
		dy = Integer.signum(dy);
		int mask = 0;
		if (dx < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		if (dx > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		if (dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		if (dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		if (dx < 0 && dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
		if (dx < 0 && dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
		if (dx > 0 && dy < 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		if (dx > 0 && dy > 0) mask |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
		return (f & mask) != 0 ? new Hit(lx, ly, x, y, f, 2) : null;
	}
	private static String kind(int k) {return k == 1 ? "full" : "dir";}
	private static int flag(ChartPlotterCollisionCache cache, WorldView wv, int[][] flags, int x, int y) {
		if (safe(flags, x, y)) {
			int f = flags[x][y];
			if (f != VOID) return f;
		}
		return cache == null ? ChartPlotterCollisionCache.UNKNOWN : cache.flag(wv, x, y);
	}
	private static boolean safe(int[][] flags, int x, int y) {return inside(flags, x, y) && x >= EDGE && y >= EDGE && x < flags.length - EDGE && y < flags[x].length - EDGE;}
	private static boolean inside(int[][] flags, int x, int y) {return x >= 0 && y >= 0 && x < flags.length && y < flags[x].length;}
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
		boolean blocked;
		int bx;
		int by;
		int bo;
		int hx;
		int hy;
		int htx;
		int hty;
		int hf;
		int hk;
		private Path(int cap) {
			x = new int[cap];
			y = new int[cap];
			o = new int[cap];
		}
	}
	private static final class Block {
		final int sx;
		final int sy;
		final int so;
		final int bx;
		final int by;
		final int bo;
		final Hit h;
		private Block(int sx, int sy, int so, int bx, int by, int bo, Hit h) {
			this.sx = sx;
			this.sy = sy;
			this.so = so;
			this.bx = bx;
			this.by = by;
			this.bo = bo;
			this.h = h;
		}
	}
	private static final class Hit {
		final int x;
		final int y;
		final int tx;
		final int ty;
		final int f;
		final int k;
		private Hit(int x, int y, int tx, int ty, int f, int k) {
			this.x = x;
			this.y = y;
			this.tx = tx;
			this.ty = ty;
			this.f = f;
			this.k = k;
		}
	}
}
