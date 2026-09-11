package com.chartplotter.runtime;

import com.chartplotter.collision.ChartPlotterCollisionData;
import com.chartplotter.collision.ChartPlotterHull;
import com.chartplotter.overlay.ChartPlotterOverlay;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

@Singleton
public final class ChartPlotterSailing {
	private static final int MOTION_HOLD = 2;
	private static final int COURSE_STALL = 2;
	private final Client client;
	private volatile WorldView top;
	volatile boolean boarded;
	private double baseSpeed;
	private double accel;
	private int speedCap;
	private int boost;
	private int windCharges;
	private int windEnabled;
	private int windPaused;
	private int startOnHeading;
	private long inputs;
	private Observation observation;
	private ChartPlotterHull forecastHull;
	private double estimate = Double.NaN;
	volatile Forecast forecast = Forecast.UNKNOWN;
	private int moveMode;
	private int lastMoveMode = 2;
	private double speed;
	private double lastSpeed;
	private volatile SpeedAverage speedAverage;
	private int motionHold;
	private int courseTicks;
	private int turnDir;
	private int lastAngle;
	private int lastBaseX = Integer.MIN_VALUE;
	private int lastBaseY = Integer.MIN_VALUE;
	private int lastPlane = Integer.MIN_VALUE;
	private int course = -1;
	private final AtomicInteger commandVersion = new AtomicInteger();
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
	@SuppressWarnings("SameParameterValue")
	ChartPlotterSailing(Client client) {
		this.client = client;
	}
	public void sync() {
		if (client.getGameState() != GameState.LOGGED_IN) return;
		boarded = client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT) == 1;
		if (boarded) syncTop();
		varbit(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED, client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED));
		varbit(VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION, client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION));
		varbit(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE, client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE));
		varbit(VarbitID.SAILING_SIDEPANEL_BOAT_SPEEDCAP, client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_SPEEDCAP));
		varbit(VarbitID.SAILING_BOAT_SPEEDBOOST_DURATION, client.getVarbitValue(VarbitID.SAILING_BOAT_SPEEDBOOST_DURATION));
		varbit(VarbitID.SAILING_SIDEPANEL_BOAT_WIND_CHARGES, client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_WIND_CHARGES));
		varbit(VarbitID.SAILING_SIDEPANEL_BOAT_WIND_CATCHER_ENABLED, client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_WIND_CATCHER_ENABLED));
		varbit(VarbitID.SAILING_BOAT_WINDS_TIMER_PAUSED, client.getVarbitValue(VarbitID.SAILING_BOAT_WINDS_TIMER_PAUSED));
		varbit(VarbitID.SAILING_START_BOAT_WHEN_SETTING_HEADING, client.getVarbitValue(VarbitID.SAILING_START_BOAT_WHEN_SETTING_HEADING));
	}
	public void varbit(VarbitChanged e) {
		varbit(e.getVarbitId(), e.getValue());
	}
	private void varbit(int id, int value) {
		if (id == VarbitID.SAILING_BOARDED_BOAT) {
			boarded = value == 1;
			if (boarded) syncTop();
			return;
		}
		if (id == VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED) {if (baseSpeed == value / 128.0) return; baseSpeed = value / 128.0;}
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION) {if (accel == value / 128.0) return; accel = value / 128.0;}
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE) {if (moveMode == value) return; lastMoveMode = moveMode; moveMode = value;}
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_SPEEDCAP) {if (speedCap == value) return; speedCap = value;}
		else if (id == VarbitID.SAILING_BOAT_SPEEDBOOST_DURATION) {if (boost == value) return; boost = value;}
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_WIND_CHARGES) {if (windCharges == value) return; windCharges = value;}
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_WIND_CATCHER_ENABLED) {if (windEnabled == value) return; windEnabled = value;}
		else if (id == VarbitID.SAILING_BOAT_WINDS_TIMER_PAUSED) {if (windPaused == value) return; windPaused = value;}
		else if (id == VarbitID.SAILING_START_BOAT_WHEN_SETTING_HEADING) {if (startOnHeading == value) return; startOnHeading = value;}
		else return;
		inputs++;
		forecast = Forecast.UNKNOWN;
	}
	public void loaded(WorldView wv) {
		if (wv != null && wv.isTopLevel()) {
			top = wv;
			resetShip();
		}
	}
	public void reset() {
		commandVersion.incrementAndGet();
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
		commandVersion.incrementAndGet();
		course = -1;
		potentialBlocked = false;
		resetMotion();
	}
	public void scene(WorldEntity ship, LocalPoint loc) {
		observation = null;
		estimate = Double.NaN;
		resetShip();
		motionHold = MOTION_HOLD;
		lastLoc = loc;
		lastAngle = targetHeading(ship);
	}
	public void motion(WorldEntity ship, LocalPoint loc, boolean skip) {
		motionTime = System.currentTimeMillis();
		if (!skip && lastLoc != null) {
			int vx = loc.getX() - lastLoc.getX();
			int vy = loc.getY() - lastLoc.getY();
			int angle = targetHeading(ship);
			turnDir = ChartPlotterMath.angleDir(lastAngle, angle, 0);
			if (course >= 0) {
				courseTicks = angle == lastAngle ? courseTicks + 1 : 0;
				if (angle == course || courseTicks >= COURSE_STALL) course = -1;
			}
			lastAngle = angle;
			if (vx == 0 && vy == 0 && motionHold > 0 && lastSpeed > 0) motionHold--;
			else {
				motionHold = 0;
				double s = ChartPlotterMath.speed(vx, vy);
				double a = s - lastSpeed;
				if (a < 10) sample(s);
			}
		} else if (!skip) lastAngle = targetHeading(ship);
		lastLoc = loc;
	}
	public void average(boolean enabled) {
		if (enabled == (speedAverage == null)) speedAverage = enabled ? new SpeedAverage() : null;
	}
	void sample(double value) {
		speed = value;
		lastSpeed = value;
		SpeedAverage average = speedAverage;
		if (average != null) average.add(value);
	}
	public void setCourse(Point m, int version) {
		if (!boarded || version != commandVersion.get()) return;
		WorldEntity ship = ship();
		if (ship == null || top == null || top.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return;
		LocalPoint loc = ship.getLocalLocation();
		if (loc == null) return;
		int h = ChartPlotterOverlay.mouseHeading(client, top, loc, m);
		if (h < 0) return;
		setCourse(h, m);
	}
	public int commandVersion() {return commandVersion.get();}
	public void command(int heading, Point point) {
		if (heading < 0) return;
		commandVersion.incrementAndGet();
		setCourse(heading, point);
	}
	public static int commandHeading(MenuAction action, int selector, int worldView, boolean consumed, boolean aboard, int topView, int clickAction) {
		return action == MenuAction.SET_HEADING && selector >= 0 && selector < 16 && !consumed && aboard && worldView == topView && clickAction == Constants.CLICK_ACTION_SET_HEADING ? selector * 128 : -1;
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
		return ship == null ? null : ship.getTargetLocation();
	}
	public int heading(WorldEntity ship) {return targetHeading(ship);}
	public int course(WorldEntity ship) {return course >= 0 ? course : targetHeading(ship);}
	public double speed() {return speed;}
	public double baseSpeed() {return baseSpeed;}
	public double averageSpeed() {
		SpeedAverage average = speedAverage;
		return average == null ? 0 : average.value;
	}
	public double accel() {return accel;}
	public boolean reversing() {return moveMode == 3;}
	public double maxSpeed() {
		if (reversing()) return 0.5;
		double cap = 1.0;
		if (moveMode == 2 || moveMode == 4 || moveMode == 0 && lastMoveMode == 4) cap = speedCap > 0 ? speedCap / 128.0 : baseSpeed;
		return cap;
	}
	public double routeSpeed() {
		int mode = moveMode == 0 ? lastMoveMode : moveMode;
		if (mode == 3) return 0.5;
		if (mode == 2 || mode == 4) return baseSpeed > 0 ? baseSpeed : speedCap > 0 ? speedCap / 128.0 : 1;
		return 1;
	}
	public long motionTime() {return motionTime;}
	boolean unobserved(int x, int y, int heading) {return observation == null || observation.x != x || observation.y != y || observation.heading != heading;}
	Forecast preview(boolean uncertain) {
		Forecast motion = forecast;
		if (motion.maximum > 0) return uncertain ? new Forecast(motion.speed, motion.acceleration, motion.maximum, motion.reverse, motion.turn, 0, motion.starts) : motion;
		double maximum = Math.max(speed, maxSpeed());
		return new Forecast(speed > 0 || moveMode == 0 || moveMode == 1 ? speed : maximum, speed > 0 ? accel : 0, maximum, reversing(), turnDir, 0, false);
	}
	private int targetHeading(WorldEntity ship) {return ChartPlotterMath.norm(ship.getTargetOrientation());}
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
		resetForecast();
		SpeedAverage average = speedAverage;
		if (average != null) average.clear();
		speed = 0;
		lastSpeed = 0;
		motionTime = 0;
		motionHold = 0;
		courseTicks = 0;
		turnDir = 0;
		lastLoc = null;
	}
	void setCourse(int heading, Point m) {
		course = heading;
		courseTicks = 0;
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
	private void resetForecast() {
		observation = null;
		forecastHull = null;
		estimate = Double.NaN;
		forecast = Forecast.UNKNOWN;
	}
	public void observe(int tick, ChartPlotterCollisionData data, WorldEntityConfig config, int x, int y, int heading) {
		Observation previous = observation;
		observation = new Observation(tick, x, y, heading, inputs, data);
		Forecast prior = forecast;
		forecast = Forecast.UNKNOWN;
		double old = estimate;
		estimate = Double.NaN;
		if (config == null) {forecastHull = null; return;}
		if (forecastHull == null || !forecastHull.matches(config)) {forecastHull = new ChartPlotterHull(config); return;}
		if (previous == null || data.scene != previous.data.scene) {
			if (prior.horizon > 1 && prior.speed == prior.maximum && (prior.maximum == maxSpeed() || prior.maximum == baseSpeed) && boost == 0) forecast = prior;
			return;
		}
		if (tick != previous.tick + 1) return;
		if (forecastHull.sweep(previous.data, previous.x / 128.0, previous.y / 128.0, previous.heading, x / 128.0, y / 128.0, heading) != ChartPlotterCollisionData.OPEN || data != previous.data && forecastHull.sweep(data, previous.x / 128.0, previous.y / 128.0, previous.heading, x / 128.0, y / 128.0, heading) != ChartPlotterCollisionData.OPEN) return;
		int dx = x - previous.x;
		int dy = y - previous.y;
		double value = ChartPlotterMath.speed(dx, dy);
		double signed = reversing() ? -value : value;
		if (ChartPlotterMath.velocityX(signed, heading) != dx || ChartPlotterMath.velocityY(signed, heading) != dy) return;
		estimate = value;
		if (inputs != previous.inputs || Math.abs((heading - previous.heading + 1024 & 2047) - 1024) > 128) return;
		double maximum = maxSpeed();
		if (!Double.isFinite(old)) {if (estimate != maximum && (prior.horizon < 2 || prior.speed != estimate)) return; old = estimate;}
		if (moveMode < 0 || moveMode > 4 || accel < 0 || maximum < 0 || estimate == 0 && moveMode != 0 || moveMode == 0 && estimate != 0) return;
		int horizon = boost > 0 || estimate > maximum || speedCap > 0 && estimate > speedCap / 128.0 ? 0 : 512;
		double acceleration = 0;
		if (estimate < maximum && estimate != 0) {
			double target = moveMode == 2 && old < baseSpeed ? Math.min(maximum, baseSpeed) : maximum;
			if (estimate > old && estimate == Math.min(target, old + accel)) acceleration = accel;
			else if (estimate == old) {maximum = estimate; horizon = boost == 0 && estimate == baseSpeed ? 512 : 0;}
			else return;
		} else if (estimate != old && estimate != Math.min(maximum, old + accel)) return;
		if (acceleration > 0 && moveMode == 2 && baseSpeed < maximum && estimate <= baseSpeed) horizon = Math.min(horizon, (int) ((baseSpeed - estimate) / acceleration));
		if (moveMode == 0) maximum = 0;
		forecast = new Forecast(estimate, acceleration, Math.max(estimate, maximum), reversing(), ChartPlotterMath.angleDir(previous.heading, heading, turnDir), horizon, startOnHeading != 0 && moveMode == 0);
	}
	static Step step(double speed, double acceleration, double maximum, int from, int target, int direction, boolean reverse) {
		int dir = ChartPlotterMath.angleDir(from, target, direction);
		int distance = dir > 0 ? ChartPlotterMath.norm(target - from) : ChartPlotterMath.norm(from - target);
		int heading = distance <= 128 ? target : ChartPlotterMath.norm(from + 128 * dir);
		double next = Math.max(0, Math.min(maximum, speed + acceleration));
		return new Step(heading, ChartPlotterMath.velocityX(reverse ? -next : next, heading), ChartPlotterMath.velocityY(reverse ? -next : next, heading), next);
	}
	static final class Step {
		final int heading;
		final int x;
		final int y;
		final double speed;
		Step(int heading, int x, int y, double speed) {this.heading = heading; this.x = x; this.y = y; this.speed = speed;}
	}
	static final class Forecast {
		static final Forecast UNKNOWN = new Forecast(0, 0, 0, false, 0, 0, false);
		final double speed;
		final double acceleration;
		final double maximum;
		final boolean reverse;
		final int turn;
		final int horizon;
		final boolean starts;
		Forecast(double speed, double acceleration, double maximum, boolean reverse, int turn, int horizon, boolean starts) {
			this.speed = speed;
			this.acceleration = acceleration;
			this.maximum = maximum;
			this.reverse = reverse;
			this.turn = turn;
			this.horizon = horizon;
			this.starts = starts;
		}
		boolean same(Forecast other) {return speed == other.speed && acceleration == other.acceleration && maximum == other.maximum && reverse == other.reverse && turn == other.turn && horizon == other.horizon && starts == other.starts;}
	}
	private static final class Observation {
		final int tick;
		final int x;
		final int y;
		final int heading;
		final long inputs;
		final ChartPlotterCollisionData data;
		Observation(int tick, int x, int y, int heading, long inputs, ChartPlotterCollisionData data) {this.tick = tick; this.x = x; this.y = y; this.heading = heading; this.inputs = inputs; this.data = data;}
	}
	private static final class SpeedAverage {
		private final double[] samples = new double[5];
		private double total;
		private int count;
		private int index;
		private volatile double value;
		void add(double speed) {
			if (count == samples.length) total -= samples[index];
			else count++;
			total += speed;
			samples[index] = speed;
			index = (index + 1) % samples.length;
			value = total / count;
		}
		void clear() {
			total = 0;
			count = 0;
			index = 0;
			value = 0;
		}
	}
}
