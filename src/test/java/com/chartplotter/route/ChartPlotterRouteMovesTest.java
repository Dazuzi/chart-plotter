package com.chartplotter.route;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class ChartPlotterRouteMovesTest {
	@Test
	public void cachedModelMatchesDirectClassification() {
		for (double speed = 0; speed <= 5; speed += 0.5) {
			ChartPlotterRouteMoves.Model model = ChartPlotterRouteMoves.model(speed);
			for (int x = -20; x <= 20; x++) {
				for (int y = -20; y <= 20; y++) assertEquals(ChartPlotterRouteMoves.solid(0, 0, x, y, speed), ChartPlotterRouteMoves.solid(0, 0, x, y, model));
			}
		}
	}
}
