package com.chartplotter;
import static com.chartplotter.ChartPlotterMath.rotateX;
import static com.chartplotter.ChartPlotterMath.rotateY;
import com.google.inject.Provides;
import java.awt.event.MouseEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.api.gameval.VarbitID;
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
	private static final int ROUTE_PRUNE_RADIUS = 8;
	private static final int ROUTE_FOLLOW_RADIUS = 24;
	private static final int ROUTE_PRUNE = 12;
	private static final int ROUTE_CLEAR_RADIUS = 15;
	private static final int MOTION_HOLD = 2;
	private static final int COURSE_STALL = 2;
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private OverlayManager overlayManager;
	@Inject private ChartPlotterOverlay overlay;
	@Inject private ChartPlotterMinimapOverlay minimapOverlay;
	@Inject private ChartPlotterWorldMapOverlay worldMapOverlay;
	@Inject private MouseManager mouseManager;
	@Inject private ChartPlotterConfig config;
	@Inject private ChartPlotterCollisionCache collisionCache;
	@Inject private ChartPlotterSparseNodes sparseNodes;
	private volatile WorldView top;
	private volatile boolean boarded;
	private double baseSpeed;
	private double accel;
	private double moveMode;
	private double lastMoveMode = 2;
	private double speed;
	private double lastSpeed;
	private int motionHold;
	private int stillTicks;
	private int turnDir;
	private int lastAngle;
	private int lastBaseX = Integer.MIN_VALUE;
	private int lastBaseY = Integer.MIN_VALUE;
	private int lastPlane = Integer.MIN_VALUE;
	private int course = -1;
	private int potentialX;
	private int potentialY;
	private LocalPoint lastLoc;
	private boolean collisionActive;
	private boolean editorCacheActive;
	private boolean mouseRegistered;
	private boolean potentialBlocked;
	private volatile ChartPlotterRoute route;
	private final AtomicInteger routeSeq = new AtomicInteger();
	private volatile boolean routeBusy;
	private volatile long routeRev;
	private ExecutorService routeExec;
	private final MouseAdapter mouse = new MouseAdapter() {
		@Override
		public MouseEvent mousePressed(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			Point m = new Point(e.getX(), e.getY());
			if (e.getButton() != MouseEvent.BUTTON1) return e;
			boolean mod = e.isAltDown() || e.isShiftDown() || e.isControlDown();
			if (config.nodeEditor() && worldMapOverlay.movingNode()) clientThread.invoke(() -> worldMapOverlay.placeNode(m));
			else if (e.isShiftDown() && config.nodeEditor()) clientThread.invoke(() -> worldMapOverlay.startNodeMove(m));
			else if (e.isAltDown() && config.nodeEditor()) clientThread.invoke(() -> worldMapOverlay.addNode(m));
			else if (e.isControlDown()) clientThread.invoke(() -> chartCourse(m));
			if (!mod && !worldMapOverlay.movingNode() && minimapOverlay.overMinimap(m)) clientThread.invoke(() -> setCourse(m));
			return e;
		}
		@Override
		public MouseEvent mouseReleased(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			return e;
		}
		@Override
		public MouseEvent mouseMoved(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			return e;
		}
		@Override
		public MouseEvent mouseDragged(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			return e;
		}
		@Override
		public MouseEvent mouseExited(MouseEvent e) {
			worldMapOverlay.nodeAlt(false);
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
		editorCacheActive = false;
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
		ChartPlotterCacheOverlay cacheOverlay = config.cacheOverlay();
		if (config.worldEnabled() || cacheOverlay.world) overlayManager.add(overlay);
		else overlayManager.remove(overlay);
		if (config.minimapEnabled()) overlayManager.add(minimapOverlay);
		else overlayManager.remove(minimapOverlay);
		if (config.worldMapEnabled() || cacheOverlay.worldMap || config.nodeEditor()) overlayManager.add(worldMapOverlay);
		else overlayManager.remove(worldMapOverlay);
		boolean routeAny = config.worldEnabled() || config.minimapEnabled() || config.worldMapEnabled();
		boolean inputAny = routeAny || config.nodeEditor();
		if (!routeAny) {
			clearRoute();
			stopRouteExec();
		}
		if (inputAny && !mouseRegistered) {
			mouseManager.registerMouseListener(mouse);
			mouseRegistered = true;
		} else if (!inputAny && mouseRegistered) {
			mouseManager.unregisterMouseListener(mouse);
			mouseRegistered = false;
		}
		if (config.nodeEditor() && !editorCacheActive) {
			collisionCache.start();
			editorCacheActive = true;
		} else if (!config.nodeEditor() && editorCacheActive) {
			editorCacheActive = false;
			if (!collisionActive) collisionCache.stop();
		}
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onVarbitChanged(VarbitChanged e) {
		int id = e.getVarbitId();
		if (id == VarbitID.SAILING_BOARDED_BOAT) {
			boarded = e.getValue() == 1;
			if (boarded) syncTop();
		}
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED) baseSpeed = e.getValue() / 128.0;
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION) accel = e.getValue() / 128.0;
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE) {
			lastMoveMode = moveMode;
			moveMode = e.getValue();
		}
		if (!boarded) {
			course = -1;
			potentialBlocked = false;
			clearRoute();
			collision(false, null);
			resetMotion();
		}
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onGameStateChanged(GameStateChanged e) {
		if (e.getGameState() == GameState.LOGGED_IN) {
			sync();
			return;
		}
		top = null;
		boarded = false;
		course = -1;
		potentialBlocked = false;
		clearRoute();
		collision(false, null);
		resetMotion();
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded e) {
		WorldView wv = e.getWorldView();
		if (wv != null && wv.isTopLevel()) {
			top = wv;
			capture(wv);
		}
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e) {
		if (e.getMenuAction() != MenuAction.SET_HEADING && !minimapOverlay.overMinimap(client.getMouseCanvasPosition())) return;
		setCourse(client.getMouseCanvasPosition());
	}
	private void setCourse(Point m) {
		if (!isSailing()) return;
		WorldEntity ship = getShip();
		if (ship == null) return;
		if (top == null || top.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return;
		LocalPoint loc = ship.getLocalLocation();
		if (loc == null) return;
		int h = ChartPlotterOverlay.mouseHeading(client, top, loc, m);
		if (h >= 0) {
			course = h;
			stillTicks = 0;
			blockPotential(m);
		}
	}
	private void chartCourse(Point m) {
		if (!isSailing()) return;
		WorldEntity ship = getShip();
		if (top == null || ship == null) return;
		LocalPoint loc = ship.getTargetLocation();
		if (loc == null) loc = ship.getLocalLocation();
		if (loc == null) return;
		int[] dst = worldMapOverlay.tile(m);
		if (dst == null) return;
		ChartPlotterRoute old = route;
		if (old != null && old.target(dst[0], dst[1], ROUTE_CLEAR_RADIUS)) {
			clearRoute();
			return;
		}
		routeTo(top, ship, loc, dst[0], dst[1], true);
	}
	@SuppressWarnings({"unused", "UnusedParameters"})
	@Subscribe
	public void onGameTick(GameTick e) {
		if (!isSailing()) return;
		WorldEntity ship = getShip();
		if (ship == null) {
			course = -1;
			clearRoute();
			collision(false, null);
			resetMotion();
			return;
		}
		LocalPoint loc = ship.getTargetLocation();
		if (loc == null) loc = ship.getLocalLocation();
		if (loc == null) {
			collision(false, null);
			return;
		}
		boolean scene = top != null && sceneChanged(top);
		boolean skipMotion = false;
		if (scene) {
			course = -1;
			motionHold = MOTION_HOLD;
			lastLoc = loc;
			lastAngle = heading(ship);
			skipMotion = true;
		}
		boolean normal = boarded && top != null && top.getYellowClickAction() == Constants.CLICK_ACTION_SET_HEADING;
		boolean started = collision(normal, top);
		if (scene && normal && !started) capture(top);
		if (top != null) updateRoute(top, ship, loc);
		if (!skipMotion && lastLoc != null) {
			int vx = loc.getX() - lastLoc.getX();
			int vy = loc.getY() - lastLoc.getY();
			int angle = heading(ship);
			turnDir = angleDir(lastAngle, angle, 0);
			lastAngle = angle;
			if (vx == 0 && vy == 0 && motionHold > 0 && lastSpeed > 0) motionHold--;
			else {
				motionHold = 0;
				if (vx == 0 && vy == 0) stillTicks++;
				else stillTicks = 0;
				double s = speed(vx, vy);
				double a = s - lastSpeed;
				if (a < 10) {
					speed = s;
					lastSpeed = speed;
				}
			}
		} else if (!skipMotion) lastAngle = heading(ship);
		lastLoc = loc;
	}
	@SuppressWarnings("unused")
	@Provides
	ChartPlotterConfig provideConfig(ConfigManager cm) {return cm.getConfig(ChartPlotterConfig.class);}
	WorldView top() {return top;}
	WorldEntity getShip() {return getPlayerShip(client.getLocalPlayer(), top);}
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isSailing() {return boarded;}
	ChartPlotterRoute route() {return route;}
	boolean suppressPotential(Point m) {return potentialBlocked && (m == null || m.getX() == potentialX && m.getY() == potentialY);}
	int heading(WorldEntity ship) {return stalled() ? actualHeading(ship) : targetHeading(ship);}
	int course(WorldEntity ship) {return stalled() ? actualHeading(ship) : course >= 0 ? course : targetHeading(ship);}
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
	static double speed(int vx, int vy) {
		double x = vx / 128.0;
		double y = vy / 128.0;
		return Math.round(Math.sqrt(x * x + y * y) / 0.5) * 0.5;
	}
	private static boolean near(int ax, int ay, int bx, int by) {return Math.max(Math.abs(ax - bx), Math.abs(ay - by)) <= ROUTE_CLEAR_RADIUS;}
	private void sync() {
		if (client.getGameState() != GameState.LOGGED_IN) return;
		boarded = client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT) == 1;
		if (boarded) syncTop();
		baseSpeed = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED) / 128.0;
		accel = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION) / 128.0;
		moveMode = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE);
	}
	private void syncTop() {if (top == null) top = client.getTopLevelWorldView();}
	private void reset() {
		collisionActive = false;
		editorCacheActive = false;
		top = null;
		boarded = false;
		course = -1;
		clearRoute();
		baseSpeed = 0;
		accel = 0;
		moveMode = 0;
		lastMoveMode = 2;
		potentialBlocked = false;
		lastBaseX = Integer.MIN_VALUE;
		lastBaseY = Integer.MIN_VALUE;
		lastPlane = Integer.MIN_VALUE;
		resetMotion();
	}
	private boolean collision(boolean active, WorldView top) {
		if (active == collisionActive) return false;
		collisionActive = active;
		if (active) {
			collisionCache.start();
			capture(top);
			return true;
		}
		if (!editorCacheActive) collisionCache.stop();
		return false;
	}
	private void resetMotion() {
		speed = 0;
		lastSpeed = 0;
		motionHold = 0;
		stillTicks = 0;
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
	private void capture(WorldView top) {
		if (!collisionActive) return;
		collisionCache.capture(top);
	}
	private void updateRoute(WorldView top, WorldEntity ship, LocalPoint loc) {
		ChartPlotterRoute r = route;
		if (r == null) return;
		int sx = top.getBaseX() + Math.floorDiv(loc.getX(), TS);
		int sy = top.getBaseY() + Math.floorDiv(loc.getY(), TS);
		if (near(sx, sy, r.tx, r.ty)) {
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
		if (r.status != ChartPlotterRoute.OK && routeRev != collisionCache.rev()) {
			routeTo(top, ship, loc, r.tx, r.ty, false);
			return;
		}
		if (r.status == ChartPlotterRoute.OK) {
			if (speed == 0) return;
			LocalPoint front = routeLoc(top, ship, loc);
			int fx = top.getBaseX() + Math.floorDiv(front.getX(), TS);
			int fy = top.getBaseY() + Math.floorDiv(front.getY(), TS);
			ChartPlotterRoute nr = r.advance(fx, fy, ROUTE_PRUNE_RADIUS, ROUTE_FOLLOW_RADIUS, ROUTE_PRUNE);
			if (nr == r) return;
			if (nr != null) {
				route = nr;
				routeRev = collisionCache.rev();
				return;
			}
			routeTo(top, ship, loc, r.tx, r.ty, false);
			return;
		}
		if (!r.start(sx, sy)) routeTo(top, ship, loc, r.tx, r.ty, false);
	}
	private LocalPoint routeLoc(WorldView top, WorldEntity ship, LocalPoint loc) {
		WorldEntityConfig wc = ship.getConfig();
		if (wc == null) return loc;
		int o = actualHeading(ship);
		int x = wc.getBoundsX();
		int y = Math.round(wc.getBoundsY() - wc.getBoundsHeight() / 2f);
		return new LocalPoint(rotateX(loc.getX(), o, x, y), rotateY(loc.getY(), o, x, y), top);
	}
	private void routeTo(WorldView top, WorldEntity ship, LocalPoint loc, int tx, int ty, boolean pending) {
		int sx = top.getBaseX() + Math.floorDiv(loc.getX(), TS);
		int sy = top.getBaseY() + Math.floorDiv(loc.getY(), TS);
		ChartPlotterRouteEffort effort = config.routeEffort();
		WorldEntityConfig wc = ship.getConfig();
		ChartPlotterTurnPreference shape = config.routeShape();
		int turnBias = shape.bias;
		int weight = effort.weight;
		boolean reverse = reversing();
		int start = speed == 0 ? -1 : heading(ship);
		int seq = routeSeq.incrementAndGet();
		ChartPlotterCollisionData data = collisionData();
		ChartPlotterSparseNodes.Snapshot sparse = sparseNodes.snapshot();
		long rev = data.rev;
		routeBusy = true;
		if (pending) {
			route = ChartPlotterRoute.pending(sx, sy, tx, ty, turnBias, weight).effort(effort);
			routeRev = rev;
		}
		startRouteExec();
		routeExec.execute(() -> {
			BooleanSupplier cancel = () -> seq != routeSeq.get() || Thread.currentThread().isInterrupted();
			ChartPlotterRoute r = ChartPlotterRouteFinder.find(data, wc, start, sx, sy, tx, ty, turnBias, reverse, weight, ROUTE_CLEAR_RADIUS, sparse, effort.corridor, cancel).effort(effort);
			if (seq == routeSeq.get() && !Thread.currentThread().isInterrupted()) {
				route = r;
				routeRev = rev;
			}
			if (seq == routeSeq.get() && !Thread.currentThread().isInterrupted()) routeBusy = false;
		});
	}
	private ChartPlotterCollisionData collisionData() {
		return collisionCache.snapshot();
	}
	private void blockPotential(Point m) {
		if (m == null) return;
		potentialBlocked = true;
		potentialX = m.getX();
		potentialY = m.getY();
	}
	private boolean stalled() {return speed == 0 && stillTicks >= COURSE_STALL;}
	private int actualHeading(WorldEntity ship) {return norm(ship.getOrientation());}
	private int targetHeading(WorldEntity ship) {return norm(ship.getTargetOrientation());}
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
		routeRev = 0;
		routeBusy = false;
	}
}
