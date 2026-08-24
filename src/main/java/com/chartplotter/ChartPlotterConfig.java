package com.chartplotter;

import net.runelite.client.config.*;

import java.awt.*;

@SuppressWarnings("SameReturnValue")
@ConfigGroup("chartplotter")
public interface ChartPlotterConfig extends Config {
	Color DEFAULT_LINE_COLOR = new Color(140, 220, 255, 185);
	Color DEFAULT_POTENTIAL_COLOR = new Color(80, 255, 120, 185);
	Color DEFAULT_CHART_COLOR = new Color(255, 101, 255, 185);
	Color DEFAULT_BLOCKED_COLOR = new Color(255, 80, 60, 140);
	@ConfigSection(name = "Colors", description = "Shared overlay colors.", position = 0)
	String colorsSection = "colorsSection";
	@Alpha
	@ConfigItem(keyName = "lineColor", name = "Current color", description = "Line for the boat's active heading or selected course.", section = colorsSection, position = 0)
	default Color lineColor() {return DEFAULT_LINE_COLOR;}
	@Alpha
	@ConfigItem(keyName = "potentialColor", name = "Projected color", description = "Line previewing the heading under the cursor.", section = colorsSection, position = 1)
	default Color potentialColor() {return DEFAULT_POTENTIAL_COLOR;}
	@Alpha
	@ConfigItem(keyName = "chartColor", name = "Charted color", description = "Lines and markers for charted trips.", section = colorsSection, position = 2)
	default Color chartColor() {return DEFAULT_CHART_COLOR;}
	@Alpha
	@ConfigItem(keyName = "blockedColor", name = "Blocked color", description = "Line section after the first blocked tile.", section = colorsSection, position = 3)
	default Color blockedColor() {return DEFAULT_BLOCKED_COLOR;}
	@ConfigSection(name = "World", description = "World overlay settings.", position = 1)
	String worldSection = "worldSection";
	@ConfigItem(keyName = "worldLineMode", name = "Current line", description = "Draw the active heading or selected course; blocked extends past collisions.", section = worldSection, position = 0)
	default ChartPlotterLineMode worldLineMode() {return ChartPlotterLineMode.BLOCKED;}
	@ConfigItem(keyName = "worldProjectedLineMode", name = "Projected line", description = "Draw the cursor heading preview; blocked extends past collisions.", section = worldSection, position = 1)
	default ChartPlotterLineMode worldProjectedLineMode() {return worldLineMode();}
	@ConfigItem(keyName = "worldChartLine", name = "Charted line", description = "Draw the active leg of the charted trip.", section = worldSection, position = 2)
	default boolean worldChartLine() {return worldLineMode().on;}
	@ConfigItem(keyName = "lineWidth", name = "Line width", description = "Stroke width in pixels.", section = worldSection, position = 3)
	@Range(min = 1, max = 10)
	default int worldLineWidth() {return 1;}
	@ConfigSection(name = "Minimap", description = "Minimap overlay settings.", position = 2)
	String minimapSection = "minimapSection";
	@ConfigItem(keyName = "minimapLineMode", name = "Current line", description = "Draw the active heading or selected course; blocked extends past collisions.", section = minimapSection, position = 0)
	default ChartPlotterLineMode minimapLineMode() {return ChartPlotterLineMode.OFF;}
	@ConfigItem(keyName = "minimapProjectedLineMode", name = "Projected line", description = "Draw the cursor heading preview; blocked extends past collisions.", section = minimapSection, position = 1)
	default ChartPlotterLineMode minimapProjectedLineMode() {return minimapLineMode();}
	@ConfigItem(keyName = "minimapChartLine", name = "Charted line", description = "Draw the active leg of the charted trip.", section = minimapSection, position = 2)
	default boolean minimapChartLine() {return minimapLineMode().on;}
	@ConfigItem(keyName = "minimapLineWidth", name = "Line width", description = "Stroke width in pixels.", section = minimapSection, position = 3)
	@Range(min = 1, max = 10)
	default int minimapLineWidth() {return 1;}
	@ConfigSection(name = "World Map", description = "World map overlay settings.", position = 3)
	String worldMapSection = "worldMapSection";
	@ConfigItem(keyName = "worldMapLineMode", name = "Current line", description = "Draw the active heading or selected course; blocked extends past collisions.", section = worldMapSection, position = 0)
	default ChartPlotterLineMode worldMapLineMode() {return ChartPlotterLineMode.ON;}
	@ConfigItem(keyName = "worldMapProjectedLineMode", name = "Projected line", description = "Draw the cursor heading preview; blocked extends past collisions.", section = worldMapSection, position = 1)
	default ChartPlotterLineMode worldMapProjectedLineMode() {return ChartPlotterLineMode.OFF;}
	@ConfigItem(keyName = "worldMapChartLine", name = "Charted line", description = "Draw all charted trip routes and stop markers.", section = worldMapSection, position = 2)
	default boolean worldMapChartLine() {return worldMapLineMode().on;}
	@ConfigItem(keyName = "worldMapLineWidth", name = "Line width", description = "Stroke width in pixels.", section = worldMapSection, position = 3)
	@Range(min = 1, max = 10)
	default int worldMapLineWidth() {return 1;}
	@ConfigItem(keyName = "worldMapCourseClick", name = "Destination click", description = "Use the selected click to replace a trip; hold Shift with it to append, click a stop to remove it, Shift-click one to remove its tail, or drag it to move it.", section = worldMapSection, position = 4)
	default ChartPlotterWorldMapClick worldMapCourseClick() {return ChartPlotterWorldMapClick.CLICK;}
	@ConfigItem(keyName = "worldMapTripHints", name = "Trip control hints", description = "Show trip controls while hovering a destination.", section = worldMapSection, position = 5)
	default boolean worldMapTripHints() {return true;}
	@ConfigSection(name = "Charting", description = "Trip route settings.", position = 4)
	String chartingSection = "chartingSection";
	@ConfigItem(keyName = "routeShape", name = "Route shape", description = "Controls how strongly charting prefers long straight legs over the shortest route.", section = chartingSection, position = 0)
	default ChartPlotterTurnPreference routeShape() {return ChartPlotterTurnPreference.BALANCED;}
	@ConfigItem(keyName = "routeEffort", name = "Pathing effort", description = "Higher effort spends more time refining routes; lower effort returns quicker.", section = chartingSection, position = 1)
	default ChartPlotterRouteEffort routeEffort() {return ChartPlotterRouteEffort.HIGH;}
	@ConfigItem(keyName = "courseTurnEta", name = "Turn ETA", description = "Show time to the next turn in the sailing view.", section = chartingSection, position = 2)
	default ChartPlotterTurnEta courseTurnEta() {return ChartPlotterTurnEta.SECONDS;}
	@ConfigItem(keyName = "courseTurnAlert", name = "Turn alert", description = "Notify when the next turn is under 5 seconds away while unfocused.", section = chartingSection, position = 3)
	default boolean courseTurnAlert() {return true;}
	@ConfigSection(name = "Tweaks", description = "Experimental settings.", position = 5, closedByDefault = true)
	String tweaksSection = "tweaksSection";
	@ConfigItem(keyName = "cacheOverlayMode", name = "Cache overlay", description = "Draw remembered collision-cache coverage.", section = tweaksSection, position = 3)
	default ChartPlotterCacheOverlay cacheOverlay() {return ChartPlotterCacheOverlay.OFF;}
	@ConfigItem(keyName = "nodeEditor", name = "Node editor", description = "Draw and place sparse pathing nodes on the world map.<br>ALT+Click to place or move nodes. Right-click a node to remove it.", section = tweaksSection, position = 5)
	default boolean nodeEditor() {return false;}
	@ConfigItem(keyName = "sparseRouteDebug", name = "Sparse route debug", description = "Draw sparse route nodes and corridors on the world map when charting a course.", section = tweaksSection, position = 6)
	default boolean sparseRouteDebug() {return false;}
	@ConfigItem(keyName = "sailingSlide", name = "Slide model", description = "(Experimental) Course projection: turn gradually from heading and slide along walls.", section = tweaksSection, position = 7)
	default boolean sailingSlide() {return false;}
}
