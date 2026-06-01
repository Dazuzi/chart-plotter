package com.chartplotter;
import static com.chartplotter.ChartPlotterMath.rotateX;
import static com.chartplotter.ChartPlotterMath.rotateY;
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
	private static final int STEP = 32;
	private static final int MEMO = 16384;
	private static final double FADE = 0.72;
	private final Client client;
	private final ChartPlotterPlugin plugin;
	private final ChartPlotterConfig config;
	private final ChartPlotterCollisionCache collisionCache;
	private final PathSlot[] pathCache = new PathSlot[8];
	private int pathCacheNext;
	private AreaSlot areaCache;
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
		if (!plugin.isSailing()) return null;
		boolean showWorld = config.worldEnabled();
		ChartPlotterCacheOverlay cacheOverlay = config.cacheOverlay();
		if (!showWorld && !cacheOverlay.world) return null;
		WorldView top = plugin.top();
		if (top == null) return null;
		SceneArea area = sceneArea(top);
		if (cacheOverlay.world) drawCache(g, top, area);
		if (!showWorld) return null;
		WorldEntity ship = plugin.getShip();
		if (ship == null) return null;
		LocalPoint anchor = ship.getTargetLocation();
		LocalPoint center = ship.getLocalLocation();
		if (anchor == null) anchor = center;
		if (anchor == null || center == null) return null;
		WorldEntityConfig wc = ship.getConfig();
		float[] rx = rectX(wc);
		float[] ry = rectY(wc);
		int from = plugin.heading(ship);
		int course = plugin.course(ship);
		int mouse = hoverHeading(top, center);
		boolean showExt = config.worldShowBlockedExtension();
		Path cur = path(top, wc, anchor, from, course, area, showExt);
		Path pot = null;
		if (mouse >= 0) pot = path(top, wc, anchor, from, mouse, area, showExt);
		int skip = pot != null ? match(cur, pot) : 0;
		Stroke prev = g.getStroke();
		g.setStroke(new BasicStroke(config.worldLineWidth(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		drawRoute(g, top, plugin.route(), area);
		draw(g, top, cur, rx, ry, config.lineColor(), skip);
		if (pot != null) draw(g, top, pot, rx, ry, config.potentialColor(), 0);
		g.setStroke(prev);
		return null;
	}
	Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, boolean showExt) {
		SceneArea area = sceneArea(wv);
		return path(wv, wc, anchor, from, target, limit(anchor, area), area, showExt);
	}
	private Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, SceneArea area, boolean showExt) {
		return path(wv, wc, anchor, from, target, limit(anchor, area), area, showExt);
	}
	Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, boolean showExt) {
		return path(wv, wc, anchor, from, target, cap, null, showExt);
	}
	private Path path(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, SceneArea area, boolean showExt) {
		PathKey key = key(wv, wc, anchor, from, target, cap, area, showExt);
		for (PathSlot s : pathCache) {
			if (s == null) continue;
			if (s.same(key)) return s.path;
		}
		Path p = pathRaw(wv, wc, anchor, from, target, cap, area, showExt);
		pathCache[pathCacheNext++ & pathCache.length - 1] = new PathSlot(key, p);
		return p;
	}
	private PathKey key(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, SceneArea area, boolean showExt) {
		return new PathKey(wv.getBaseX(), wv.getBaseY(), wv.getPlane(), anchor.getX(), anchor.getY(), from, target, cap, plugin.turnDir(), wid(wc), wcat(wc), wx(wc), wy(wc), ww(wc), wh(wc), Double.doubleToLongBits(plugin.speed()), Double.doubleToLongBits(plugin.accel()), Double.doubleToLongBits(plugin.maxSpeed()), plugin.reversing(), config.stopAtCollision(), showExt, collisionCache.rev(), areaKey(area));
	}
	private Path pathRaw(WorldView wv, WorldEntityConfig wc, LocalPoint anchor, int from, int target, int cap, SceneArea area, boolean showExt) {
		Path p = new Path(cap + 2);
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
		boolean stop = config.stopAtCollision();
		Blocker blocker = stop ? blocker(wv, wc, collisionCache) : null;
		for (int i = 0; i < cap; i++) {
			if (o != target) o = turn(o, target, dir);
			speed += accel;
			double max = Math.max(plugin.maxSpeed(), Math.abs(plugin.speed()));
			speed = plugin.reversing() ? Math.max(-max, speed) : Math.min(max, speed);
			int vx = velocityX(speed, o);
			int vy = velocityY(speed, o);
			if (vx == 0 && vy == 0) break;
			posX += vx;
			posY += vy;
			int lx = anchor.getX() + posX;
			int ly = anchor.getY() + posY;
			if (area != null && area.missing(lx, ly)) break;
			if (!p.blocked) {
				Block b = stop ? block(blocker, p.x[p.n - 1], p.y[p.n - 1], p.o[p.n - 1], lx, ly, o) : null;
				if (b != null) {
					if (b.sx != p.x[p.n - 1] || b.sy != p.y[p.n - 1] || b.so != p.o[p.n - 1]) {
						p.x[p.n] = b.sx;
						p.y[p.n] = b.sy;
						p.o[p.n] = b.so;
						p.n++;
					}
					p.blocked = true;
					p.blockedAt = p.n;
					if (!showExt) break;
				}
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
		Color blockedColor = config.blockedColor();
		int sBA = blockedColor.getAlpha();
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
			boolean inBlock = i >= p.blockedAt;
			Color base = inBlock ? blockedColor : color;
			int a = alpha(inBlock ? sBA : sA, i, p.n);
			if (a > 0) {
				g.setColor(ColorUtil.colorWithAlpha(base, a));
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
	private void drawRoute(Graphics2D g, WorldView wv, ChartPlotterRoute r, SceneArea area) {
		if (r == null || r.status != ChartPlotterRoute.OK || r.n < 2 || area == null) return;
		Path2D.Double line = new Path2D.Double();
		boolean have = false;
		for (int i = 1; i < r.n; i++) have = routeSegment(line, wv, area, r.x[i - 1], r.y[i - 1], r.x[i], r.y[i], have);
		if (have && (r.x[r.n - 1] != r.tx || r.y[r.n - 1] != r.ty)) routeSegment(line, wv, area, r.x[r.n - 1], r.y[r.n - 1], r.tx, r.ty, true);
		g.setColor(config.chartColor());
		g.draw(line);
	}
	private boolean routeSegment(Path2D.Double line, WorldView wv, SceneArea area, int ax, int ay, int bx, int by, boolean have) {
		double x0 = ax + 0.5;
		double y0 = ay + 0.5;
		double dx = bx - ax;
		double dy = by - ay;
		double t0 = 0;
		double t1 = 1;
		double minX = area.minWX();
		double minY = area.minWY();
		double maxX = area.maxWX();
		double maxY = area.maxWY();
		if (dx == 0) {
			if (x0 < minX || x0 > maxX) return false;
		} else {
			double a = (minX - x0) / dx;
			double b = (maxX - x0) / dx;
			if (a > b) {
				double c = a;
				a = b;
				b = c;
			}
			if (a > t0) t0 = a;
			if (b < t1) t1 = b;
			if (t0 > t1) return false;
		}
		if (dy == 0) {
			if (y0 < minY || y0 > maxY) return false;
		} else {
			double a = (minY - y0) / dy;
			double b = (maxY - y0) / dy;
			if (a > b) {
				double c = a;
				a = b;
				b = c;
			}
			if (a > t0) t0 = a;
			if (b < t1) t1 = b;
			if (t0 > t1) return false;
		}
		int n = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) * (t1 - t0));
		if (n < 1) n = 1;
		for (int i = 0; i <= n; i++) {
			double t = t0 + (t1 - t0) * i / n;
			have = routePoint(line, wv, area, x0 + dx * t, y0 + dy * t, have);
		}
		return have;
	}
	private boolean routePoint(Path2D.Double line, WorldView wv, SceneArea area, double wx, double wy, boolean have) {
		Point q = routeCanvas(wv, area, wx, wy);
		if (q == null) return false;
		if (have) line.lineTo(q.getX(), q.getY());
		else {
			line.moveTo(q.getX(), q.getY());
		}
		return true;
	}
	private Point routeCanvas(WorldView wv, SceneArea area, double wx, double wy) {
		int lx = (int) Math.round((wx - wv.getBaseX()) * TS);
		int ly = (int) Math.round((wy - wv.getBaseY()) * TS);
		if (area.missing(lx, ly)) return null;
		return Perspective.localToCanvas(client, new LocalPoint(lx, ly, wv), 0);
	}
	private void drawCache(Graphics2D g, WorldView wv, SceneArea area) {
		if (area == null) return;
		Stroke old = g.getStroke();
		ChartPlotterCollisionData data = collisionCache.snapshot();
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		g.setColor(new Color(0, 210, 120, 150));
		for (int i = 0; i < area.n; i++) {
			int cx = area.cx[i];
			int cy = area.cy[i];
			if (data.uncached(cx, cy)) continue;
			int wx = cx << 3;
			int wy = cy << 3;
			int x0 = Math.max(wx, area.minWX());
			int y0 = Math.max(wy, area.minWY());
			int x1 = Math.min(wx + 8, area.maxWX());
			int y1 = Math.min(wy + 8, area.maxWY());
			if (x0 >= x1 || y0 >= y1) continue;
			if (area.chunk(cx - 1, cy) && data.uncached(cx - 1, cy)) drawCacheEdge(g, wv, x0, y0, x0, y1);
			if (area.chunk(cx + 1, cy) && data.uncached(cx + 1, cy)) drawCacheEdge(g, wv, x1, y0, x1, y1);
			if (area.chunk(cx, cy - 1) && data.uncached(cx, cy - 1)) drawCacheEdge(g, wv, x0, y0, x1, y0);
			if (area.chunk(cx, cy + 1) && data.uncached(cx, cy + 1)) drawCacheEdge(g, wv, x0, y1, x1, y1);
		}
		g.setStroke(old);
	}
	private void drawCacheEdge(Graphics2D g, WorldView wv, int ax, int ay, int bx, int by) {
		Point a = cachePoint(wv, ax, ay);
		Point b = cachePoint(wv, bx, by);
		if (a == null || b == null) return;
		g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
	}
	private Point cachePoint(WorldView wv, int wx, int wy) {
		return Perspective.localToCanvas(client, new LocalPoint((wx - wv.getBaseX()) * TS, (wy - wv.getBaseY()) * TS, wv), 0);
	}
	private boolean missing(WorldView wv, Path p, int i, float[] rx, float[] ry, float[] z, int[] cx, int[] cy) {
		Perspective.modelToCanvas(client, wv, 4, p.x[i], p.y[i], 0, p.o[i], rx, ry, z, cx, cy);
		return cx[0] == Integer.MIN_VALUE || cx[1] == Integer.MIN_VALUE || cx[2] == Integer.MIN_VALUE || cx[3] == Integer.MIN_VALUE;
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
		if (plugin.suppressPotential(m)) return -1;
		int mini = ChartPlotterMinimapOverlay.mouseHeading(client, anchor, m);
		if (mini >= 0) return mini;
		if (outsideViewport(client, m) || headingInactive(client, wv)) return -1;
		return sceneHeading(client, wv, anchor, m);
	}
	static boolean headingInactive(Client client, WorldView wv) {
		if (wv.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return true;
		MenuEntry[] es = client.getMenu().getMenuEntries();
		return es.length == 0 || es[es.length - 1].getType() != MenuAction.SET_HEADING;
	}
	static boolean outsideViewport(Client client, Point m) {
		int x = client.getViewportXOffset();
		int y = client.getViewportYOffset();
		return m.getX() < x || m.getY() < y || m.getX() >= x + client.getViewportWidth() || m.getY() >= y + client.getViewportHeight();
	}
	static int mouseHeading(Client client, WorldView wv, LocalPoint anchor, Point mouse) {
		int mini = ChartPlotterMinimapOverlay.mouseHeading(client, anchor, mouse);
		if (mini >= 0) return mini;
		return sceneHeading(client, wv, anchor, mouse);
	}
	private static int sceneHeading(Client client, WorldView wv, LocalPoint anchor, Point mouse) {
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
	private static int wid(WorldEntityConfig wc) {return wc == null ? 0 : wc.getId();}
	private static int wcat(WorldEntityConfig wc) {return wc == null ? 0 : wc.getCategory();}
	private static int wx(WorldEntityConfig wc) {return wc == null ? 0 : Float.floatToIntBits(wc.getBoundsX());}
	private static int wy(WorldEntityConfig wc) {return wc == null ? 0 : Float.floatToIntBits(wc.getBoundsY());}
	private static int ww(WorldEntityConfig wc) {return wc == null ? 0 : Float.floatToIntBits(wc.getBoundsWidth());}
	private static int wh(WorldEntityConfig wc) {return wc == null ? 0 : Float.floatToIntBits(wc.getBoundsHeight());}
	private static long areaKey(SceneArea a) {
		if (a == null) return 0;
		long h = 1125899906842597L;
		h = h * 31 + a.baseX;
		h = h * 31 + a.baseY;
		h = h * 31 + a.offX;
		h = h * 31 + a.offY;
		h = h * 31 + a.minX;
		h = h * 31 + a.minY;
		h = h * 31 + a.maxX;
		h = h * 31 + a.maxY;
		h = h * 31 + a.n;
		for (boolean b : a.chunks) h = h * 31 + (b ? 1 : 0);
		return h;
	}
	private SceneArea sceneArea(WorldView wv) {
		Tile[][] tiles = tiles(wv);
		AreaSlot s = areaCache;
		if (s != null && s.same(wv, tiles)) return s.area;
		SceneArea area = area(wv, tiles);
		areaCache = new AreaSlot(wv, tiles, area);
		return area;
	}
	private static SceneArea area(WorldView wv, Tile[][] tiles) {
		if (tiles == null) return null;
		int offX = wv.isTopLevel() ? (tiles.length - wv.getSizeX()) / 2 : 0;
		int offY = 0;
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int x = 0; x < tiles.length; x++) {
			Tile[] row = tiles[x];
			if (row == null) continue;
			if (x == 0) offY = wv.isTopLevel() ? (row.length - wv.getSizeY()) / 2 : 0;
			for (int y = 0; y < row.length; y++) {
				if (row[y] == null) continue;
				int sx = x - offX;
				int sy = y - offY;
				if (sx < minX) minX = sx;
				if (sy < minY) minY = sy;
				if (sx + 1 > maxX) maxX = sx + 1;
				if (sy + 1 > maxY) maxY = sy + 1;
			}
		}
		if (minX == Integer.MAX_VALUE) return null;
		int minCX = (wv.getBaseX() + minX) >> 3;
		int minCY = (wv.getBaseY() + minY) >> 3;
		int maxCX = (wv.getBaseX() + maxX - 1) >> 3;
		int maxCY = (wv.getBaseY() + maxY - 1) >> 3;
		int cw = maxCX - minCX + 1;
		int ch = maxCY - minCY + 1;
		boolean[] chunks = new boolean[cw * ch];
		int n = 0;
		for (int x = 0; x < tiles.length; x++) {
			Tile[] row = tiles[x];
			if (row == null) continue;
			for (int y = 0; y < row.length; y++) {
				if (row[y] == null) continue;
				int sx = x - offX;
				int sy = y - offY;
				int cx = ((wv.getBaseX() + sx) >> 3) - minCX;
				int cy = ((wv.getBaseY() + sy) >> 3) - minCY;
				int i = cx * ch + cy;
				if (!chunks[i]) {
					chunks[i] = true;
					n++;
				}
			}
		}
		return new SceneArea(tiles, wv.getBaseX(), wv.getBaseY(), offX, offY, minX, minY, maxX, maxY, minCX, minCY, cw, ch, chunks, n);
	}
	private static Tile[][] tiles(WorldView wv) {
		Tile[][][] all = wv.getScene().getExtendedTiles();
		int plane = wv.getPlane();
		if (all == null || plane < 0 || plane >= all.length) return null;
		return all[plane];
	}
	private static Blocker blocker(WorldView wv, WorldEntityConfig wc, ChartPlotterCollisionCache cache) {
		return new Blocker(wv, cache.snapshot(), footprint(wc), new FlagMemo(MEMO));
	}
	private static Footprint footprint(WorldEntityConfig wc) {
		float[] rx = rectX(wc);
		float[] ry = rectY(wc);
		int minX = min(rx);
		int maxX = max(rx);
		int minY = min(ry);
		int maxY = max(ry);
		int n = 0;
		for (int x = minX;; x = next(x, maxX)) {
			for (int y = minY;; y = next(y, maxY)) {
				if (edge(x, y, minX, maxX, minY, maxY)) n++;
				if (y == maxY) break;
			}
			if (x == maxX) break;
		}
		Footprint p = new Footprint(n);
		for (int x = minX;; x = next(x, maxX)) {
			for (int y = minY;; y = next(y, maxY)) {
				if (edge(x, y, minX, maxX, minY, maxY)) {
					p.x[p.n] = x;
					p.y[p.n] = y;
					p.corner[p.n] = (x == minX || x == maxX) && (y == minY || y == maxY);
					p.n++;
				}
				if (y == maxY) break;
			}
			if (x == maxX) break;
		}
		return p;
	}
	private static Block block(Blocker b, int ax, int ay, int ao, int bx, int by, int bo) {
		return b == null ? null : blockBoundsExact(b, ax, ay, ao, bx, by, bo);
	}
	private static Block blockBoundsExact(Blocker b, int ax, int ay, int ao, int bx, int by, int bo) {
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
			if (!clearBounds(b, px, py, po, qx, qy, bo)) {
				if (hitFootprint(b, px, py, po, qx, qy, bo)) return new Block(px, py, po);
			}
			px = qx;
			py = qy;
			po = bo;
		}
		return null;
	}
	private static boolean clearBounds(Blocker b, int ax, int ay, int ao, int bx, int by, int bo) {
		Footprint fp = b.footprint;
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int i = 0; i < fp.n; i++) {
			if (!fp.corner[i]) continue;
			int x = fp.x[i];
			int y = fp.y[i];
			int px = rotateX(ax, ao, x, y);
			int py = rotateY(ay, ao, x, y);
			int qx = rotateX(bx, bo, x, y);
			int qy = rotateY(by, bo, x, y);
			if (px < minX) minX = px;
			if (qx < minX) minX = qx;
			if (py < minY) minY = py;
			if (qy < minY) minY = qy;
			if (px > maxX) maxX = px;
			if (qx > maxX) maxX = qx;
			if (py > maxY) maxY = py;
			if (qy > maxY) maxY = qy;
		}
		int x0 = Math.floorDiv(minX, TS);
		int y0 = Math.floorDiv(minY, TS);
		int x1 = Math.floorDiv(maxX, TS);
		int y1 = Math.floorDiv(maxY, TS);
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				int f = flag(b, x, y);
				if (f != ChartPlotterCollisionCache.UNKNOWN && blocker(f)) {
					return false;
				}
			}
		}
		return true;
	}
	private static boolean hitFootprint(Blocker b, int ax, int ay, int ao, int bx, int by, int bo) {
		Footprint fp = b.footprint;
		for (int i = 0; i < fp.n; i++) {
			int x = fp.x[i];
			int y = fp.y[i];
			int px = rotateX(ax, ao, x, y);
			int py = rotateY(ay, ao, x, y);
			int qx = rotateX(bx, bo, x, y);
			int qy = rotateY(by, bo, x, y);
			if (hitPath(b, px, py, qx, qy)) return true;
		}
		return false;
	}
	private static boolean hitPath(Blocker b, int ax, int ay, int bx, int by) {
		int dx = bx - ax;
		int dy = by - ay;
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / STEP;
		if (steps < 1) steps = 1;
		for (int i = 1; i <= steps; i++) {
			int lx = ax + dx * i / steps;
			int ly = ay + dy * i / steps;
			int x = Math.floorDiv(lx, TS);
			int y = Math.floorDiv(ly, TS);
			int f = flag(b, x, y);
			if (f == ChartPlotterCollisionCache.UNKNOWN) return false;
			if (blocker(f)) return true;
		}
		return false;
	}
	private static int min(float[] v) {return (int) Math.floor(Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3])));}
	private static int max(float[] v) {return (int) Math.ceil(Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3])));}
	private static int next(int v, int max) {return Math.min(v + STEP, max);}
	private static boolean edge(int x, int y, int minX, int maxX, int minY, int maxY) {return x == minX || x == maxX || y == minY || y == maxY;}
	private static int flag(Blocker b, int x, int y) {
		if (b.memo != null) {
			if (b.memo.full) return flagRaw(b, x, y);
			long k = ((long) x << 32) ^ (y & 0xffffffffL);
			int i = b.memo.slot(k);
			for (int n = 0; n < b.memo.used.length; n++) {
				if (!b.memo.used[i]) {
					int f = flagRaw(b, x, y);
					b.memo.used[i] = true;
					b.memo.key[i] = k;
					b.memo.val[i] = f;
					if (++b.memo.n == b.memo.used.length) b.memo.full = true;
					return f;
				}
				if (b.memo.key[i] == k) {
					return b.memo.val[i];
				}
				i = i + 1 & b.memo.mask;
			}
			b.memo.full = true;
		}
		return flagRaw(b, x, y);
	}
	private static int flagRaw(Blocker b, int x, int y) {
		return flag(b.data, b.wv, x, y);
	}
	private static int flag(ChartPlotterCollisionData data, WorldView wv, int x, int y) {
		int wx = wv.getBaseX() + x;
		int wy = wv.getBaseY() + y;
		return data.flagAt(wx, wy);
	}
	private static boolean blocker(int f) {return (f & ChartPlotterCollisionCache.MOVE) != 0;}
	private static int limit(LocalPoint anchor, SceneArea area) {
		if (area == null) return 512;
		int ax = Math.floorDiv(anchor.getX(), TS);
		int ay = Math.floorDiv(anchor.getY(), TS);
		int edge = Math.max(Math.max(Math.abs(ax - area.minX), Math.abs(area.maxX - ax)), Math.max(Math.abs(ay - area.minY), Math.abs(area.maxY - ay)));
		return edge * 8 + 32;
	}
	private static int turn(int o, int target, int dir) {
		if (dir == 0) return target;
		int d = dir > 0 ? ChartPlotterPlugin.norm(target - o) : ChartPlotterPlugin.norm(o - target);
		if (d <= TURN) return target;
		return ChartPlotterPlugin.norm(o + TURN * dir);
	}
	private static int velocityX(double speed, int o) {return ChartPlotterPlugin.snap(ChartPlotterPlugin.round(-Perspective.SINE[o] * speed / 512.0));}
	private static int velocityY(double speed, int o) {return ChartPlotterPlugin.snap(ChartPlotterPlugin.round(-Perspective.COSINE[o] * speed / 512.0));}
	private static int side(int x1, int y1, int x2, int y2, int x, int y) {return (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1);}
	private static final class AreaSlot {
		final int baseX;
		final int baseY;
		final int plane;
		final int sizeX;
		final int sizeY;
		final boolean top;
		final Tile[][] tiles;
		final SceneArea area;
		private AreaSlot(WorldView wv, Tile[][] tiles, SceneArea area) {
			baseX = wv.getBaseX();
			baseY = wv.getBaseY();
			plane = wv.getPlane();
			sizeX = wv.getSizeX();
			sizeY = wv.getSizeY();
			top = wv.isTopLevel();
			this.tiles = tiles;
			this.area = area;
		}
		boolean same(WorldView wv, Tile[][] tiles) {
			return baseX == wv.getBaseX() && baseY == wv.getBaseY() && plane == wv.getPlane() && sizeX == wv.getSizeX() && sizeY == wv.getSizeY() && top == wv.isTopLevel() && this.tiles == tiles;
		}
	}
	private static final class SceneArea {
		final Tile[][] tiles;
		final int baseX;
		final int baseY;
		final int offX;
		final int offY;
		final int minX;
		final int minY;
		final int maxX;
		final int maxY;
		final int minCX;
		final int minCY;
		final int cw;
		final int ch;
		final boolean[] chunks;
		final int[] cx;
		final int[] cy;
		final int n;
		private SceneArea(Tile[][] tiles, int baseX, int baseY, int offX, int offY, int minX, int minY, int maxX, int maxY, int minCX, int minCY, int cw, int ch, boolean[] chunks, int n) {
			this.tiles = tiles;
			this.baseX = baseX;
			this.baseY = baseY;
			this.offX = offX;
			this.offY = offY;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
			this.minCX = minCX;
			this.minCY = minCY;
			this.cw = cw;
			this.ch = ch;
			this.chunks = chunks;
			this.n = n;
			cx = new int[n];
			cy = new int[n];
			int j = 0;
			for (int x = 0; x < cw; x++) {
				for (int y = 0; y < ch; y++) {
					if (!chunks[x * ch + y]) continue;
					cx[j] = minCX + x;
					cy[j] = minCY + y;
					j++;
				}
			}
		}
		boolean missing(int lx, int ly) {
			int x = Math.floorDiv(lx, TS) + offX;
			int y = Math.floorDiv(ly, TS) + offY;
			return x < 0 || y < 0 || x >= tiles.length || tiles[x] == null || y >= tiles[x].length || tiles[x][y] == null;
		}
		boolean chunk(int x, int y) {
			x -= minCX;
			y -= minCY;
			return x >= 0 && y >= 0 && x < cw && y < ch && chunks[x * ch + y];
		}
		int minWX() {return baseX + minX;}
		int minWY() {return baseY + minY;}
		int maxWX() {return baseX + maxX;}
		int maxWY() {return baseY + maxY;}
	}
	private static final class PathSlot {
		final PathKey key;
		final Path path;
		private PathSlot(PathKey key, Path path) {
			this.key = key;
			this.path = path;
		}
		boolean same(PathKey k) {return key.same(k);}
	}
	private static final class PathKey {
		final int baseX;
		final int baseY;
		final int plane;
		final int ax;
		final int ay;
		final int from;
		final int target;
		final int cap;
		final int turn;
		final int wid;
		final int wcat;
		final int wx;
		final int wy;
		final int ww;
		final int wh;
		final long speed;
		final long accel;
		final long max;
		final long rev;
		final long area;
		final boolean reverse;
		final boolean stop;
		final boolean show;
		private PathKey(int baseX, int baseY, int plane, int ax, int ay, int from, int target, int cap, int turn, int wid, int wcat, int wx, int wy, int ww, int wh, long speed, long accel, long max, boolean reverse, boolean stop, boolean show, long rev, long area) {
			this.baseX = baseX;
			this.baseY = baseY;
			this.plane = plane;
			this.ax = ax;
			this.ay = ay;
			this.from = from;
			this.target = target;
			this.cap = cap;
			this.turn = turn;
			this.wid = wid;
			this.wcat = wcat;
			this.wx = wx;
			this.wy = wy;
			this.ww = ww;
			this.wh = wh;
			this.speed = speed;
			this.accel = accel;
			this.max = max;
			this.reverse = reverse;
			this.stop = stop;
			this.show = show;
			this.rev = rev;
			this.area = area;
		}
		boolean same(PathKey k) {
			return baseX == k.baseX && baseY == k.baseY && plane == k.plane && ax == k.ax && ay == k.ay && from == k.from && target == k.target && cap == k.cap && turn == k.turn && wid == k.wid && wcat == k.wcat && wx == k.wx && wy == k.wy && ww == k.ww && wh == k.wh && speed == k.speed && accel == k.accel && max == k.max && reverse == k.reverse && stop == k.stop && show == k.show && rev == k.rev && area == k.area;
		}
	}
	private static final class Blocker {
		final WorldView wv;
		final ChartPlotterCollisionData data;
		final Footprint footprint;
		final FlagMemo memo;
		private Blocker(WorldView wv, ChartPlotterCollisionData data, Footprint footprint, FlagMemo memo) {
			this.wv = wv;
			this.data = data;
			this.footprint = footprint;
			this.memo = memo;
		}
	}
	private static final class FlagMemo {
		final boolean[] used;
		final long[] key;
		final int[] val;
		final int mask;
		int n;
		boolean full;
		private FlagMemo(int n) {
			used = new boolean[n];
			key = new long[n];
			val = new int[n];
			mask = n - 1;
		}
		int slot(long k) {
			k ^= k >>> 33;
			k *= 0xff51afd7ed558ccdL;
			k ^= k >>> 33;
			return (int) k & mask;
		}
	}
	private static final class Footprint {
		final int[] x;
		final int[] y;
		final boolean[] corner;
		int n;
		private Footprint(int n) {
			x = new int[n];
			y = new int[n];
			corner = new boolean[n];
		}
	}
	static final class Path {
		final int[] x;
		final int[] y;
		final int[] o;
		int start;
		int n;
		boolean blocked;
		int blockedAt = Integer.MAX_VALUE;
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
		private Block(int sx, int sy, int so) {
			this.sx = sx;
			this.sy = sy;
			this.so = so;
		}
	}
}
