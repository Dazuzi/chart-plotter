# Chart Plotter
Collision-aware sailing navigation helper for viewing your current and projected courses and planning ordered trips through one or more destinations.

## Features
- Draw current and projected sailing courses as solid lines in the world view, minimap, and/or world map. Collision coloring and optional truncation mark predicted contact.
- Speed estimates follow live boat stats and clear-water observations. Recorded peak speeds do not set boat limits.
- Chart ordered trips of up to 32 stops from the world map. Straight route segments and stationary corner checks use the filled hull; they do not guarantee turning clearance at sailing speed. Selected endpoint guides check a point path and need not fit the hull.
- Following a valid route, stopping, turning and changing speed preserve its planned shape. New full-speed routes use the boat's reported base speed; slow and reverse modes retain their nominal speeds. Intermediate compass bearings vary with speed; waypoint segments incompatible with the current speed are dashed. Obstacles, leaving the route, waypoint edits and hull or route-setting changes can trigger replanning.
- Show next-turn ETA and alert when unfocused near a turning point.
- Customize a movable info panel with stop progress, trip, next-stop and next-turn ETAs, and boat speed. Trip and next-stop ETAs use the last 5 ticks of speed.
- Configure overlays, colors, route shape, pathing effort, and alerts.

<img width="881" height="402" alt="chartplotter1" src="https://github.com/user-attachments/assets/7a7f58ce-7735-4879-8d04-4913373f754d" />
<img width="1232" height="741" alt="chartplotter2" src="https://github.com/user-attachments/assets/41e8ae27-dba8-41c5-97e5-414f20d3b950" />

<details>
<summary>Plugin developer API</summary>

Other plugins can post `PluginMessage` events in the `chartplotter` namespace.

- `chart`: Data containing Java `Integer` values `x` and `y` from `WorldPoint.getX()` and `WorldPoint.getY()`, each in `0..16383`. These are standard RuneScape tiles, not Sailing subtiles. Replaces the current trip and is accepted only while aboard a boat and at least one charted-line overlay, panel ETA, or stop counter is enabled.
- `clear`: No data fields; clears the current trip.

Unknown names and malformed or out-of-range coordinates are ignored.

</details>

Build the Java 11 plugin with `./gradlew -q build`. The Plugin Hub uses `build=standard`.
