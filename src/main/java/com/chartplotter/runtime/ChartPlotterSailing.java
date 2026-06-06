package com.chartplotter.runtime;

import com.chartplotter.overlay.ChartPlotterOverlay;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class ChartPlotterSailing {
	private static final int MOTION_HOLD = 2;
	private static final int COURSE_STALL = 2;
	private final Client client;
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
	private volatile WorldEntity shipCache;
	private volatile WorldView shipTop;
	private volatile Player shipPlayer;
	private volatile int shipPid = Integer.MIN_VALUE;
	private volatile int shipTick = Integer.MIN_VALUE;
	private long motionTime;
	private boolean potentialBlocked;
	@Inject
	private ChartPlotterSailing(Client client) {
		this.client = client;
	}
	public void sync() {
		if (client.getGameState() != GameState.LOGGED_IN) return;
		boarded = client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT) == 1;
		if (boarded) syncTop();
		baseSpeed = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED) / 128.0;
		accel = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION) / 128.0;
		moveMode = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE);
	}
	public void varbit(VarbitChanged e) {
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
	}
	public void loaded(WorldView wv) {
		if (wv != null && wv.isTopLevel()) {
			top = wv;
			resetShip();
		}
	}
	public void reset() {
		top = null;
		boarded = false;
		course = -1;
		baseSpeed = 0;
		accel = 0;
		moveMode = 0;
		lastMoveMode = 2;
		potentialBlocked = false;
		lastBaseX = Integer.MIN_VALUE;
		lastBaseY = Integer.MIN_VALUE;
		lastPlane = Integer.MIN_VALUE;
		resetShip();
		resetMotion();
	}
	public void clear() {
		course = -1;
		potentialBlocked = false;
		resetMotion();
	}
	public void scene(WorldEntity ship, LocalPoint loc) {
		resetShip();
		course = -1;
		motionHold = MOTION_HOLD;
		lastLoc = loc;
		lastAngle = heading(ship);
	}
	public void motion(WorldEntity ship, LocalPoint loc, boolean skip) {
		motionTime = System.currentTimeMillis();
		if (!skip && lastLoc != null) {
			int vx = loc.getX() - lastLoc.getX();
			int vy = loc.getY() - lastLoc.getY();
			int angle = heading(ship);
			turnDir = ChartPlotterMath.angleDir(lastAngle, angle, 0);
			lastAngle = angle;
			if (vx == 0 && vy == 0 && motionHold > 0 && lastSpeed > 0) motionHold--;
			else {
				motionHold = 0;
				if (vx == 0 && vy == 0) stillTicks++;
				else stillTicks = 0;
				double s = ChartPlotterMath.speed(vx, vy);
				double a = s - lastSpeed;
				if (a < 10) {
					speed = s;
					lastSpeed = speed;
				}
			}
		} else if (!skip) lastAngle = heading(ship);
		lastLoc = loc;
	}
	public void setCourse(Point m) {
		if (!boarded) return;
		WorldEntity ship = ship();
		if (ship == null || top == null || top.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return;
		LocalPoint loc = ship.getLocalLocation();
		if (loc == null) return;
		int h = ChartPlotterOverlay.mouseHeading(client, top, loc, m);
		if (h < 0) return;
		course = h;
		stillTicks = 0;
		block(m);
	}
	public void tick() {resetShip();}
	public boolean sceneChanged(WorldView wv) {
		int x = wv.getBaseX();
		int y = wv.getBaseY();
		int p = wv.getPlane();
		boolean changed = lastBaseX != Integer.MIN_VALUE && (lastBaseX != x || lastBaseY != y || lastPlane != p);
		lastBaseX = x;
		lastBaseY = y;
		lastPlane = p;
		return changed;
	}
	public WorldView top() {return top;}
	public WorldEntity ship() {
		Player player = client.getLocalPlayer();
		WorldView t = top;
		WorldView pv = player == null ? null : player.getWorldView();
		int pid = pv == null ? Integer.MIN_VALUE : pv.getId();
		int tick = client.getTickCount();
		if (shipTick == tick && shipTop == t && shipPlayer == player && shipPid == pid) return shipCache;
		WorldEntity ship = playerShip(player, t);
		shipTick = tick;
		shipTop = t;
		shipPlayer = player;
		shipPid = pid;
		shipCache = ship;
		return ship;
	}
	public boolean boarded() {return boarded;}
	public boolean suppress(Point m) {return potentialBlocked && (m == null || m.getX() == potentialX && m.getY() == potentialY);}
	public boolean courseLine(WorldView wv) {return speed > 0 || wv != null && wv.getYellowClickAction() == Constants.CLICK_ACTION_SET_HEADING;}
	public LocalPoint anchorLoc(WorldEntity ship) {
		if (ship == null) return null;
		LocalPoint loc = ship.getTargetLocation();
		return loc != null ? loc : ship.getLocalLocation();
	}
	public int heading(WorldEntity ship) {return stalled() ? actualHeading(ship) : targetHeading(ship);}
	public int course(WorldEntity ship) {return stalled() ? actualHeading(ship) : course >= 0 ? course : targetHeading(ship);}
	public double speed() {return speed;}
	public double accel() {return accel;}
	public int moveMode() {return (int) moveMode;}
	public int turnDir() {return turnDir;}
	public boolean reversing() {return moveMode == 3;}
	public double maxSpeed() {
		if (reversing()) return 0.5;
		double cap = 1.0;
		if (moveMode == 2 || moveMode == 4 || moveMode == 0 && lastMoveMode == 4) cap = baseSpeed;
		return cap;
	}
	public long motionTime() {return motionTime;}
	public int actualHeading(WorldEntity ship) {return ChartPlotterMath.norm(ship.getOrientation());}
	private int targetHeading(WorldEntity ship) {return ChartPlotterMath.norm(ship.getTargetOrientation());}
	private boolean stalled() {return speed == 0 && stillTicks >= COURSE_STALL;}
	private void syncTop() {
		if (top != null) return;
		top = client.getTopLevelWorldView();
		resetShip();
	}
	private void resetShip() {
		shipTick = Integer.MIN_VALUE;
		shipTop = null;
		shipPlayer = null;
		shipPid = Integer.MIN_VALUE;
		shipCache = null;
	}
	private void resetMotion() {
		speed = 0;
		lastSpeed = 0;
		motionTime = 0;
		motionHold = 0;
		stillTicks = 0;
		turnDir = 0;
		lastLoc = null;
	}
	private void block(Point m) {
		if (m == null) return;
		potentialBlocked = true;
		potentialX = m.getX();
		potentialY = m.getY();
	}
	public static WorldEntity playerShip(Player player, WorldView top) {
		if (player == null || top == null) return null;
		WorldView pv = player.getWorldView();
		if (pv == null) return null;
		int pid = pv.getId();
		for (WorldEntity we : top.worldEntities()) {
			WorldView wv = we.getWorldView();
			if (wv != null && wv.getId() == pid) return we;
		}
		return null;
	}
}
