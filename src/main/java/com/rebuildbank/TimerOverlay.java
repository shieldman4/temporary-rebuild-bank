package com.rebuildbank;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** In-game timer. Counts down to the time limit, then flashes slowly. */
class TimerOverlay extends OverlayPanel
{
	private static final Color FLASH_COLOR = new Color(170, 20, 20, 200);
	private static final long FLASH_PERIOD_MS = 1000; // 1s on, 1s off

	private final RebuildBankPlugin plugin;
	private final RebuildBankConfig config;

	@Inject
	TimerOverlay(RebuildBankPlugin plugin, RebuildBankConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		RebuildSession s = plugin.getSession();
		if (!config.showOverlay() || s == null)
		{
			return null;
		}

		long elapsedSecs = s.challengeTicks() * RebuildSession.TICK_MILLIS / 1000;
		long limitSecs = Math.max(0, config.timeLimitMinutes()) * 60L;
		boolean overTime = limitSecs > 0 && elapsedSecs >= limitSecs;
		boolean flashOn = overTime && !s.isComplete() && (System.currentTimeMillis() / FLASH_PERIOD_MS) % 2 == 0;

		panelComponent.setBackgroundColor(flashOn ? FLASH_COLOR : ComponentConstants.STANDARD_BACKGROUND_COLOR);
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Rebuild Bank")
			.color(s.isComplete() ? Color.GREEN : Color.ORANGE)
			.build());

		if (s.baselinePending)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("Open your bank to start").build());
			return super.render(graphics);
		}

		if (limitSecs > 0)
		{
			String left = overTime
				? "+" + RebuildSession.formatSeconds(elapsedSecs - limitSecs)
				: RebuildSession.formatSeconds(limitSecs - elapsedSecs);
			panelComponent.getChildren().add(LineComponent.builder()
				.left(overTime ? "Time's up" : "Time left")
				.right(left)
				.rightColor(overTime ? Color.RED : Color.WHITE)
				.build());
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Elapsed")
			.right(RebuildSession.formatSeconds(elapsedSecs))
			.build());

		if (config.overlayShowStats())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Net value")
				.right(Amounts.shortFormat(plugin.getCachedNetValue()))
				.build());
			panelComponent.getChildren().add(LineComponent.builder()
				.left("GP/hr")
				.right(Amounts.shortFormat(RebuildSession.gpPerHour(plugin.getCachedNetValue(), s.challengeTicks())))
				.build());
			if (s.pkKills > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("PKs")
					.right(Long.toString(s.pkKills))
					.build());
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Avg kill")
					.right(Amounts.shortFormat(s.averageKillValue()))
					.build());
			}
			if (s.hasGoal())
			{
				long pct = Math.max(0, Math.min(100, plugin.getCachedGoalProgress() * 100 / s.goalTarget));
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Goal")
					.right(s.isComplete() ? "Done!" : pct + "%")
					.rightColor(s.isComplete() ? Color.GREEN : Color.WHITE)
					.build());
			}
		}
		return super.render(graphics);
	}
}
