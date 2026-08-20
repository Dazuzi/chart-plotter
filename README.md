# Chart Plotter
Collision-aware sailing navigation helper for viewing your current and projected courses and planning ordered trips through one or more destinations.

## Features
- Draw current and projected sailing courses in the world view, minimap, and/or world map.
- Chart ordered trips of up to 32 stops from the world map.
- Show next-turn ETA and alert when unfocused near a turning point.
- Configure overlays, colors, route shape, pathing effort, and alerts.

<img width="881" height="402" alt="chartplotter1" src="https://github.com/user-attachments/assets/7a7f58ce-7735-4879-8d04-4913373f754d" />
<img width="1232" height="741" alt="chartplotter2" src="https://github.com/user-attachments/assets/41e8ae27-dba8-41c5-97e5-414f20d3b950" />

<details>
<summary>Plugin developer API</summary>

Other plugins can post `PluginMessage` events in the `chartplotter` namespace.

- `chart`: Data containing Java `Integer` values `x` and `y` from `WorldPoint.getX()` and `WorldPoint.getY()`, each in `0..16383`. These are standard RuneScape tiles, not Sailing subtiles. Replaces the current trip and is accepted only while aboard a boat and at least one charted-line overlay is enabled.
- `clear`: No data fields; clears the current trip.

Unknown names and malformed or out-of-range coordinates are ignored.

</details>
