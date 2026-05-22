package com.chartplotter;
import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.util.ColorUtil;
@ConfigGroup("chartplotter")
public interface ChartPlotterConfig extends Config {
	@Alpha
	@ConfigItem(keyName = "lineColor", name = "Course color", description = "Color of the set-course lines.", position = 0)
	default Color lineColor() {return ColorUtil.colorWithAlpha(new Color(140, 220, 255), 90);}
	@Alpha
	@ConfigItem(keyName = "potentialColor", name = "Potential color", description = "Color of the mouse-course lines.", position = 5)
	default Color potentialColor() {return ColorUtil.colorWithAlpha(new Color(255, 170, 40), 185);}
	@ConfigItem(keyName = "lineWidth", name = "Line width", description = "Stroke width in pixels.", position = 20)
	@Range(min = 1, max = 10)
	default int lineWidth() {return 2;}
}
