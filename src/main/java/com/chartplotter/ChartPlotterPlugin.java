package com.chartplotter;
import com.google.inject.Provides;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
@PluginDescriptor(
	name = "Chart Plotter",
	description = "A Chart Plotter helper too to assist you with all your sailing chart plotting needs.",
	configName = "chartplotter",
	tags = {"sailing","sail","heading","navigation","chart","plotter"}
)
public class ChartPlotterPlugin extends Plugin {
	private static final int TS = Perspective.LOCAL_TILE_SIZE;
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private OverlayManager overlayManager;
	@Inject private ChartPlotterOverlay overlay;
	@Inject private ChartPlotterMinimapOverlay minimapOverlay;
	@Inject private ChartPlotterWorldMapOverlay worldMapOverlay;
	@Inject private MouseManager mouseManager;
	@Inject private ChartPlotterConfig config;
	@Inject private ChartPlotterCollisionCache collisionCache;
	private boolean boarded;
	private double baseSpeed;
	private double accel;
	private double moveMode;
	private double lastMoveMode = 2;
	private double speed;
	private double lastSpeed;
	private int turnDir;
	private int lastAngle;
	private int lastBaseX = Integer.MIN_VALUE;
	private int lastBaseY = Integer.MIN_VALUE;
	private int lastPlane = Integer.MIN_VALUE;
	private int course = -1;
	private LocalPoint lastLoc;
	private boolean collisionActive;
	private boolean mouseRegistered;
	private volatile ChartPlotterRoute route;
	private final AtomicInteger routeSeq = new AtomicInteger();
	private volatile boolean routeBusy;
	private ExecutorService routeExec;
	private final MouseAdapter mouse = new MouseAdapter() {
		@Override
		public MouseEvent mousePressed(MouseEvent e) {
			Point m = new Point(e.getX(), e.getY());
			if (e.getButton() == MouseEvent.BUTTON1 && e.isControlDown()) clientThread.invoke(() -> chartCourse(m));
			else if (e.getButton() == MouseEvent.BUTTON1 && minimapOverlay.overMinimap(m)) clientThread.invoke(() -> setCourse(m));
			return e;
		}
	};
	@Override
	protected void startUp() {
		apply();
		clientThread.invoke(this::sync);
	}
	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(worldMapOverlay);
		if (mouseRegistered) {
			mouseManager.unregisterMouseListener(mouse);
			mouseRegistered = false;
		}
		collisionActive = false;
		collisionCache.stop();
		stopRouteExec();
		reset();
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		if ("chartplotter".equals(e.getGroup())) apply();
	}
	private void apply() {
		if (config.worldEnabled()) overlayManager.add(overlay);
		else overlayManager.remove(overlay);
		if (config.minimapEnabled()) overlayManager.add(minimapOverlay);
		else overlayManager.remove(minimapOverlay);
		if (config.worldMapEnabled()) overlayManager.add(worldMapOverlay);
		else overlayManager.remove(worldMapOverlay);
		boolean any = config.worldEnabled() || config.minimapEnabled() || config.worldMapEnabled();
		if (!any) {
			clearRoute();
			stopRouteExec();
		}
		if (any && !mouseRegistered) {
			mouseManager.registerMouseListener(mouse);
			mouseRegistered = true;
		} else if (!any && mouseRegistered) {
			mouseManager.unregisterMouseListener(mouse);
			mouseRegistered = false;
		}
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onVarbitChanged(VarbitChanged e) {
		int id = e.getVarbitId();
		if (id == VarbitID.SAILING_BOARDED_BOAT) boarded = e.getValue() == 1;
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED) baseSpeed = e.getValue() / 128.0;
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION) accel = e.getValue() / 128.0;
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE) {
			lastMoveMode = moveMode;
			moveMode = e.getValue();
		}
		if (!boarded) {
			course = -1;
			clearRoute();
			resetMotion();
		}
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e) {
		if (e.getMenuAction() != MenuAction.SET_HEADING && !minimapOverlay.overMinimap(client.getMouseCanvasPosition())) return;
		setCourse(client.getMouseCanvasPosition());
	}
	private void setCourse(Point m) {
		WorldEntity ship = getShip();
		if (ship == null) return;
		WorldView top = client.getTopLevelWorldView();
		if (top == null || top.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return;
		LocalPoint loc = ship.getLocalLocation();
		if (loc == null) return;
		int h = ChartPlotterOverlay.mouseHeading(client, top, loc, m);
		if (h >= 0) course = h;
	}
	private void chartCourse(Point m) {
		if (!isSailing()) return;
		int[] dst = worldMapOverlay.tile(m);
		if (dst == null) return;
		WorldView top = client.getTopLevelWorldView();
		WorldEntity ship = getShip();
		if (top == null || ship == null) return;
		LocalPoint loc = ship.getTargetLocation();
		if (loc == null) loc = ship.getLocalLocation();
		if (loc == null) return;
		ChartPlotterRoute old = route;
		if (old != null && old.target(dst[0], dst[1], config.routeClearRadius())) {
			clearRoute();
			return;
		}
		routeTo(top, ship, loc, dst[0], dst[1], true);
	}
	@SuppressWarnings({"unused", "UnusedParameters"})
	@Subscribe
	public void onGameTick(GameTick e) {
		WorldEntity ship = getShip();
		if (ship == null) {
			course = -1;
			clearRoute();
			collision(false);
			resetMotion();
			return;
		}
		LocalPoint loc = ship.getTargetLocation();
		if (loc == null) loc = ship.getLocalLocation();
		if (loc == null) {
			collision(false);
			return;
		}
		WorldView top = client.getTopLevelWorldView();
		boolean jump = lastLoc != null && Math.max(Math.abs(loc.getX() - lastLoc.getX()), Math.abs(loc.getY() - lastLoc.getY())) > TS * 4;
		boolean reset = top != null && sceneChanged(top) || jump;
		if (reset) {
			course = -1;
			resetMotion();
		}
		boolean active = (config.cacheCollision() || config.cacheOverlay()) && boarded && top != null && top.getYellowClickAction() == Constants.CLICK_ACTION_SET_HEADING;
		collision(active);
		if (active && config.cacheCollision()) collisionCache.capture(top);
		if (top != null) updateRoute(top, ship, loc);
		if (lastLoc != null) {
			int vx = loc.getX() - lastLoc.getX();
			int vy = loc.getY() - lastLoc.getY();
			double s = speed(vx, vy);
			double a = s - lastSpeed;
			turnDir = angleDir(lastAngle, ship.getTargetOrientation(), 0);
			lastAngle = ship.getTargetOrientation();
			if (a < 10) {
				speed = s;
				lastSpeed = speed;
			}
		} else lastAngle = ship.getTargetOrientation();
		lastLoc = loc;
	}
	@SuppressWarnings("unused")
	@Provides
	ChartPlotterConfig provideConfig(ConfigManager cm) {return cm.getConfig(ChartPlotterConfig.class);}
	WorldEntity getShip() {return getPlayerShip(client.getLocalPlayer(), client.getTopLevelWorldView());}
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isSailing() {
		WorldView top = client.getTopLevelWorldView();
		return boarded && top != null && top.getYellowClickAction() == Constants.CLICK_ACTION_SET_HEADING && getShip() != null;
	}
	ChartPlotterRoute route() {return route;}
	int course(WorldEntity ship) {return course >= 0 ? course : norm(ship.getTargetOrientation());}
	double speed() {return speed;}
	double accel() {return accel;}
	int turnDir() {return turnDir;}
	boolean reversing() {return moveMode == 3;}
	double maxSpeed() {
		if (reversing()) return 0.5;
		double cap = 1.0;
		if (moveMode == 2 || moveMode == 4 || moveMode == 0 && lastMoveMode == 4) cap = baseSpeed;
		return cap;
	}
	static WorldEntity getPlayerShip(Player player, WorldView topWorldView) {
		if (player == null || topWorldView == null) return null;
		WorldView pv = player.getWorldView();
		if (pv == null) return null;
		int pid = pv.getId();
		for (WorldEntity we : topWorldView.worldEntities()) {
			WorldView wv = we.getWorldView();
			if (wv != null && wv.getId() == pid) return we;
		}
		return null;
	}
	static double orientationToDegrees(int angle) {return 270 - angle / (2048 / 360.0);}
	static int angleDir(int start, int target, int current) {
		double d = orientationToDegrees(target) - orientationToDegrees(start);
		if (d < -180) return -1;
		if (d > 180) return 1;
		if (d == 180 || d == -180) return current < 0 ? -1 : 1;
		if (d == 0) return 0;
		return -(int) (d / Math.abs(d));
	}
	static int norm(int v) {return ((v % 2048) + 2048) % 2048;}
	static int round(double v) {return (int) (Math.round(Math.abs(v)) * Math.signum(v));}
	static int snap(int v) {return round(v / 32.0) * 32;}
	static double speed(int vx, int vy) {return Math.round(Math.sqrt(Math.pow(vx / 128.0, 2) + Math.pow(vy / 128.0, 2)) / 0.5) * 0.5;}
	private static boolean near(int ax, int ay, int bx, int by, int r) {return Math.max(Math.abs(ax - bx), Math.abs(ay - by)) <= r;}
	private void sync() {
		if (client.getGameState() != GameState.LOGGED_IN) return;
		boarded = client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT) == 1;
		baseSpeed = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED) / 128.0;
		accel = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION) / 128.0;
		moveMode = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE);
	}
	private void reset() {
		collisionActive = false;
		boarded = false;
		course = -1;
		clearRoute();
		baseSpeed = 0;
		accel = 0;
		moveMode = 0;
		lastMoveMode = 2;
		lastBaseX = Integer.MIN_VALUE;
		lastBaseY = Integer.MIN_VALUE;
		lastPlane = Integer.MIN_VALUE;
		resetMotion();
	}
	private void collision(boolean active) {
		if (active == collisionActive) return;
		collisionActive = active;
		if (active) collisionCache.start();
		else collisionCache.stop();
	}
	private void resetMotion() {
		speed = 0;
		lastSpeed = 0;
		turnDir = 0;
		lastLoc = null;
	}
	private boolean sceneChanged(WorldView top) {
		int x = top.getBaseX();
		int y = top.getBaseY();
		int p = top.getPlane();
		boolean changed = lastBaseX != Integer.MIN_VALUE && (lastBaseX != x || lastBaseY != y || lastPlane != p);
		lastBaseX = x;
		lastBaseY = y;
		lastPlane = p;
		return changed;
	}
	private void updateRoute(WorldView top, WorldEntity ship, LocalPoint loc) {
		ChartPlotterRoute r = route;
		if (r == null) return;
		int sx = top.getBaseX() + Math.floorDiv(loc.getX(), TS);
		int sy = top.getBaseY() + Math.floorDiv(loc.getY(), TS);
		if (near(sx, sy, r.tx, r.ty, config.routeClearRadius())) {
			clearRoute();
			return;
		}
		if (routeBusy || r.status == ChartPlotterRoute.PENDING) return;
		int turnBias = config.routeShape().bias;
		ChartPlotterRouteEffort effort = config.routeEffort();
		if (r.turnBias != turnBias || r.effort != effort) {
			routeTo(top, ship, loc, r.tx, r.ty, false);
			return;
		}
		if (r.status == ChartPlotterRoute.OK) {
			ChartPlotterRoute nr = r.advance(sx, sy);
			if (nr == r) return;
			if (nr != null && routeClear(top, ship, nr)) {
				route = nr;
				return;
			}
			routeTo(top, ship, loc, r.tx, r.ty, false);
			return;
		}
		if (!r.start(sx, sy)) routeTo(top, ship, loc, r.tx, r.ty, false);
	}
	private boolean routeClear(WorldView top, WorldEntity ship, ChartPlotterRoute r) {
		if (r.n < 2) return true;
		Map<Long, int[]> data = collisionCache.snapshot(top);
		int start = speed == 0 ? -1 : norm(ship.getTargetOrientation());
		ChartPlotterRouteEffort effort = r.effort == null ? config.routeEffort() : r.effort;
		return ChartPlotterRouteFinder.clear(data, effort.footprint ? ship.getConfig() : null, start, r.sx, r.sy, r.x[1], r.y[1], reversing());
	}
	private void routeTo(WorldView top, WorldEntity ship, LocalPoint loc, int tx, int ty, boolean pending) {
		int sx = top.getBaseX() + Math.floorDiv(loc.getX(), TS);
		int sy = top.getBaseY() + Math.floorDiv(loc.getY(), TS);
		ChartPlotterRouteEffort effort = config.routeEffort();
		WorldEntityConfig wc = effort.footprint ? ship.getConfig() : null;
		ChartPlotterTurnPreference shape = config.routeShape();
		int turnBias = shape.bias;
		int dirs = effort.dirs;
		boolean fast = effort.fast;
		boolean reverse = reversing();
		int start = speed == 0 ? -1 : ChartPlotterPlugin.norm(ship.getTargetOrientation());
		int seq = routeSeq.incrementAndGet();
		Map<Long, int[]> data = collisionCache.snapshot(top);
		long queued = System.nanoTime();
		String context = pending ? routeContext(top, ship, loc, sx, sy, tx, ty, shape, effort, wc, start, turnBias, dirs, fast, reverse, data.size()) : null;
		String previewContext = pending && effort.refine ? routeContext(top, ship, loc, sx, sy, tx, ty, shape, effort, wc, start, turnBias, dirs, true, reverse, data.size()) : null;
		routeBusy = true;
		if (pending) route = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, fast).effort(effort);
		startRouteExec();
		routeExec.execute(() -> {
			ChartPlotterRoute best = null;
			if (effort.refine) {
				ChartPlotterRoute preview = ChartPlotterRouteFinder.find(data, wc, start, sx, sy, tx, ty, turnBias, reverse, true, dirs, () -> seq != routeSeq.get() || Thread.currentThread().isInterrupted()).effort(effort);
				if (seq != routeSeq.get() || Thread.currentThread().isInterrupted()) return;
				if (preview.status == ChartPlotterRoute.OK) {
					route = preview;
					best = preview;
				}
				if (pending) System.out.println(routeDebug(previewContext + " pass=preview", preview, System.nanoTime() - queued));
			}
			ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, wc, start, sx, sy, tx, ty, turnBias, reverse, fast, dirs, () -> seq != routeSeq.get() || Thread.currentThread().isInterrupted()).effort(effort);
			if (seq == routeSeq.get() && !Thread.currentThread().isInterrupted()) {
				routeBusy = false;
				ChartPlotterRoute out = best != null && r.status != ChartPlotterRoute.OK ? best : r;
				route = out;
				if (pending) System.out.println(routeDebug(context + (effort.refine ? " pass=final kept=" + (out == best ? "preview" : "final") : ""), r, System.nanoTime() - queued));
			}
		});
	}
	private String routeContext(WorldView top, WorldEntity ship, LocalPoint loc, int sx, int sy, int tx, int ty, ChartPlotterTurnPreference shape, ChartPlotterRouteEffort effort, WorldEntityConfig wc, int start, int turnBias, int dirs, boolean fast, boolean reverse, int chunks) {
		int lx = Math.floorDiv(loc.getX(), TS);
		int ly = Math.floorDiv(loc.getY(), TS);
		int dx = tx - sx;
		int dy = ty - sy;
		WorldMap wm = client.getWorldMap();
		Point pos = wm == null ? null : wm.getWorldMapPosition();
		String map = wm == null || pos == null ? "none" : wm.getWorldMapZoom() + "," + pos.getX() + "," + pos.getY();
		return "ChartPlotter route selected from=" + sx + "," + sy + " to=" + tx + "," + ty + " delta=" + dx + "," + dy + " cheb=" + Math.max(Math.abs(dx), Math.abs(dy)) + " local=" + lx + "," + ly + " base=" + top.getBaseX() + "," + top.getBaseY() + " plane=" + top.getPlane() + " worldMap=" + map + " targetOrientation=" + ship.getTargetOrientation() + " start=" + start + " speed=" + speed + " accel=" + accel + " maxSpeed=" + maxSpeed() + " reverse=" + reverse + " moveMode=" + moveMode + " lastMoveMode=" + lastMoveMode + " shape=" + shape.name() + " effort=" + effort.name() + " turnBias=" + turnBias + " dirs=" + dirs + " fast=" + fast + " footprint=" + (wc != null) + " bounds=" + bounds(wc) + " cacheChunks=" + chunks + " " + collisionCache.stats();
	}
	private String routeDebug(String context, ChartPlotterRoute r, long elapsed) {
		return context + " result=" + status(r.status) + " waypoints=" + r.n + " route=" + waypoints(r) + " elapsedMs=" + ms(elapsed) + " " + (r.debug == null ? "" : r.debug);
	}
	private static String bounds(WorldEntityConfig wc) {
		if (wc == null) return "none";
		return wc.getId() + "," + wc.getCategory() + "," + wc.getBoundsX() + "," + wc.getBoundsY() + "," + wc.getBoundsWidth() + "," + wc.getBoundsHeight();
	}
	private static String status(int s) {
		if (s == ChartPlotterRoute.OK) return "OK";
		if (s == ChartPlotterRoute.UNCHARTED) return "UNCHARTED";
		if (s == ChartPlotterRoute.BLOCKED) return "BLOCKED";
		if (s == ChartPlotterRoute.NO_ROUTE) return "NO_ROUTE";
		if (s == ChartPlotterRoute.COMPLEX) return "COMPLEX";
		return "PENDING";
	}
	private static String waypoints(ChartPlotterRoute r) {
		if (r.n == 0) return "none";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < r.n; i++) {
			if (i != 0) sb.append(';');
			sb.append(r.x[i]).append(',').append(r.y[i]);
		}
		return sb.toString();
	}
	private static String ms(long ns) {
		long us = (ns + 500) / 1000;
		long a = us / 1000;
		long b = us % 1000;
		return a + "." + (b < 100 ? b < 10 ? "00" : "0" : "") + b;
	}
	private void startRouteExec() {
		if (routeExec != null) return;
		routeExec = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "chart-plotter-route");
			t.setDaemon(true);
			return t;
		});
	}
	private void stopRouteExec() {
		if (routeExec == null) return;
		routeExec.shutdownNow();
		routeExec = null;
	}
	private void clearRoute() {
		routeSeq.incrementAndGet();
		route = null;
		routeBusy = false;
	}
}
