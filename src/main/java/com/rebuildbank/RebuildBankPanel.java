package com.rebuildbank;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.ProgressBar;

class RebuildBankPanel extends PluginPanel
{
	private static final int MAX_ROWS = 60;
	private static final Color GOAL_FILL = new Color(0x2E, 0xA0, 0x43);

	private final RebuildBankPlugin plugin;

	private final JLabel status = new JLabel();
	private final JButton startButton = new JButton("Start new temporary bank");
	private final JButton endButton = new JButton("End temporary bank");
	private final JCheckBox viewToggle = new JCheckBox("Show temporary bank in bank");

	private final JComboBox<GoalType> goalType = new JComboBox<>(GoalType.values());
	private final JTextField goalItem = new JTextField();
	private final JTextField goalAmount = new JTextField();
	private final JButton setGoalButton = new JButton("Set goal");
	private final JSpinner timeLimit = new JSpinner(new SpinnerNumberModel(60, 0, 100_000, 5));
	private boolean updatingTimeLimit;
	private final JLabel goalItemLabel = new JLabel("Item name or ID");

	private final JLabel time = value();
	private final JLabel bankValue = value();
	private final JLabel spent = value();
	private final JLabel debt = value();
	private final JLabel netValue = value();
	private final JLabel gpHr = value();
	private final JLabel violations = value();
	private final JPanel pkPanel = new JPanel(new GridLayout(0, 2, 4, 3));
	private final JLabel pkKills = value();
	private final JLabel pkAverage = value();
	private final JLabel pkKillsName = new JLabel("Player kills");
	private final JLabel pkAverageName = new JLabel("Avg kill value");
	private final ProgressBar progress = new ProgressBar();
	private final JLabel goalText = new JLabel();

	private final JPanel itemList = new JPanel();

	RebuildBankPanel(RebuildBankPlugin plugin, boolean showView, int timeLimitMinutes)
	{
		// Not wrapped: the panel fills the whole sidebar so the credits can sit at the very bottom
		super(false);
		this.plugin = plugin;
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel root = new JPanel();
		root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Temporary Rebuild Bank");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		root.add(left(title));
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		root.add(left(status));
		root.add(gap());

		JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 4));
		buttons.add(startButton);
		buttons.add(endButton);
		root.add(left(buttons));
		viewToggle.setSelected(showView);
		root.add(left(viewToggle));
		root.add(gap());

		// Goal
		root.add(left(header("Goal")));
		JPanel goal = new JPanel(new GridLayout(0, 1, 0, 3));
		goal.add(goalType);
		goal.add(goalItemLabel);
		goal.add(goalItem);
		goal.add(new JLabel("Target (e.g. 10m, 500k, 25)"));
		goal.add(goalAmount);
		goal.add(setGoalButton);
		root.add(left(goal));
		root.add(gap());
		goalText.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		root.add(left(goalText));
		progress.setMaximumValue(100);
		progress.setBackground(ColorScheme.DARKER_GRAY_COLOR); // empty part
		progress.setForeground(GOAL_FILL); // filled part
		progress.setPreferredSize(new Dimension(100, 16));
		progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		root.add(left(progress));
		root.add(gap());

		// Challenge time limit, right here for quick changes
		root.add(left(header("Time limit")));
		JPanel limit = new JPanel(new BorderLayout(6, 0));
		JLabel limitLabel = new JLabel("Minutes (0 = no limit)");
		limitLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		limit.add(limitLabel, BorderLayout.CENTER);
		timeLimit.setPreferredSize(new Dimension(80, 24));
		timeLimit.setValue(Math.max(0, timeLimitMinutes));
		limit.add(timeLimit, BorderLayout.EAST);
		root.add(left(limit));
		root.add(gap());

		// Stats
		root.add(left(header("Stats")));
		JPanel stats = new JPanel(new GridLayout(0, 2, 4, 3));
		stat(stats, "In-game time", time);
		stat(stats, "Bank value", bankValue);
		stat(stats, "Paid out", spent);
		stat(stats, "Owed to real bank", debt);
		stat(stats, "Net value", netValue);
		stat(stats, "GP / hour", gpHr);
		stat(stats, "Rule breaks", violations);

		root.add(left(stats));
		pkStat(pkPanel, pkKillsName, pkKills);
		pkStat(pkPanel, pkAverageName, pkAverage);
		pkPanel.setVisible(false);
		root.add(left(pkPanel));
		root.add(gap());

		root.add(left(header("Temporary bank")));
		itemList.setLayout(new GridLayout(0, 1, 0, 2));
		root.add(left(itemList));

		JLabel credits = new JLabel("<html>Credits: <font color='#ffffff'>shieldman4</font></html>");
		credits.setFont(FontManager.getRunescapeSmallFont());
		credits.setForeground(ColorScheme.BRAND_ORANGE);
		credits.setHorizontalAlignment(SwingConstants.CENTER);
		credits.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 0, 0, 0)));

		// Everything above the credits scrolls; the credits stay pinned to the bottom
		JPanel top = new WidthTrackingPanel();
		top.add(root, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(top,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
		add(credits, BorderLayout.SOUTH);

		startButton.addActionListener(e ->
		{
			if (endButton.isEnabled() && JOptionPane.showConfirmDialog(this,
				"Start a new temporary bank? Your current temporary bank progress will be lost.",
				"Temporary Rebuild Bank", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
			{
				return;
			}
			plugin.startSession();
		});
		endButton.addActionListener(e ->
		{
			if (JOptionPane.showConfirmDialog(this,
				"End this temporary bank? Your real bank is untouched; temporary bank progress will be cleared.",
				"Temporary Rebuild Bank", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
			{
				plugin.endSession();
			}
		});
		viewToggle.addActionListener(e -> plugin.setViewEnabled(viewToggle.isSelected()));
		timeLimit.addChangeListener(e ->
		{
			if (!updatingTimeLimit)
			{
				plugin.setTimeLimit((Integer) timeLimit.getValue());
			}
		});
		goalType.addActionListener(e -> updateGoalInputs());
		setGoalButton.addActionListener(e ->
		{
			GoalType type = (GoalType) goalType.getSelectedItem();
			long amount = type == GoalType.NONE ? 0 : Amounts.parse(goalAmount.getText());
			if (type != GoalType.NONE && amount <= 0)
			{
				JOptionPane.showMessageDialog(this, "Enter a target like 10m, 500k or 25.");
				return;
			}
			plugin.setGoal(type, goalItem.getText().trim(), amount);
		});

		updateGoalInputs();
		update(PanelData.LOGGED_OUT);
	}

	void setTimeLimit(int minutes)
	{
		updatingTimeLimit = true;
		timeLimit.setValue(Math.max(0, minutes));
		updatingTimeLimit = false;
	}

	void setViewToggle(boolean on)
	{
		viewToggle.setSelected(on);
	}

	void showMessage(String message)
	{
		JOptionPane.showMessageDialog(this, message, "Temporary Rebuild Bank", JOptionPane.INFORMATION_MESSAGE);
	}

	/** Must be called on the Swing thread. */
	void update(PanelData d)
	{
		status.setText("<html>" + d.getStatus() + "</html>");
		startButton.setEnabled(d.isLoggedIn());
		endButton.setEnabled(d.isActive());
		setGoalButton.setEnabled(d.isActive());

		if (!d.isActive())
		{
			pkPanel.setVisible(false);
			for (JLabel l : new JLabel[]{time, bankValue, spent, debt, netValue, gpHr, violations})
			{
				l.setText("-");
			}
			goalText.setText("");
			progress.setValue(0);
			progress.setCenterLabel("");
			progress.setLeftLabel("");
			progress.setRightLabel("");
			itemList.removeAll();
			revalidate();
			repaint();
			return;
		}

		time.setText(d.getTime());
		bankValue.setText(Amounts.shortFormat(d.getBankValue()));
		spent.setText(Amounts.shortFormat(d.getSpent()));
		debt.setText(d.getDebt() > 0 ? "-" + Amounts.shortFormat(d.getDebt()) : "0");
		debt.setForeground(d.getDebt() > 0 ? ColorScheme.PROGRESS_ERROR_COLOR : Color.WHITE);
		netValue.setText(Amounts.shortFormat(d.getNetValue()));
		gpHr.setText(Amounts.shortFormat(d.getGpPerHour()));
		violations.setText(Amounts.format(d.getViolations()));
		boolean pk = d.getPkKills() > 0;
		pkPanel.setVisible(pk);
		pkKills.setText(Amounts.format(d.getPkKills()));
		pkAverage.setText(Amounts.shortFormat(d.getPkAverage()));
		violations.setForeground(d.getViolations() > 0 ? ColorScheme.PROGRESS_ERROR_COLOR : Color.WHITE);

		if (d.getGoalType() == null || d.getGoalType() == GoalType.NONE || d.getGoalTarget() <= 0)
		{
			goalText.setText("No goal set");
			progress.setValue(0);
			progress.setCenterLabel("");
			progress.setLeftLabel("");
			progress.setRightLabel("");
		}
		else
		{
			String what = d.getGoalType() == GoalType.ITEM ? d.getGoalItemName() : d.getGoalType().toString();
			goalText.setText("<html>" + what + ": " + Amounts.format(d.getGoalTarget())
				+ (d.isComplete() ? " <font color='#00ff00'>(complete!)</font>" : "") + "</html>");
			int pct = (int) Math.max(0, Math.min(100, d.getGoalProgress() * 100 / d.getGoalTarget()));
			progress.setValue(pct);
			progress.setLeftLabel(Amounts.shortFormat(d.getGoalProgress()));
			progress.setRightLabel(Amounts.shortFormat(d.getGoalTarget()));
			progress.setCenterLabel(pct + "%");
		}

		itemList.removeAll();
		if (d.getItems().isEmpty())
		{
			JLabel empty = new JLabel("Empty - deposit something!");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			itemList.add(empty);
		}
		int shown = 0;
		for (PanelData.Row r : d.getItems())
		{
			if (shown++ >= MAX_ROWS)
			{
				JLabel more = new JLabel("... and " + (d.getItems().size() - MAX_ROWS) + " more");
				more.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				itemList.add(more);
				break;
			}
			JPanel row = new JPanel(new BorderLayout());
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.setBorder(new EmptyBorder(3, 5, 3, 5));
			JLabel name = new JLabel(r.getName() + " x " + Amounts.shortFormat(r.getQuantity()));
			name.setForeground(Color.WHITE);
			JLabel val = new JLabel(Amounts.shortFormat(r.getValue()));
			val.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
			row.add(name, BorderLayout.CENTER);
			row.add(val, BorderLayout.EAST);
			itemList.add(row);
		}
		revalidate();
		repaint();
	}

	private void updateGoalInputs()
	{
		GoalType type = (GoalType) goalType.getSelectedItem();
		boolean item = type == GoalType.ITEM;
		goalItem.setEnabled(item);
		goalItemLabel.setEnabled(item);
		goalAmount.setEnabled(type != GoalType.NONE);
	}

	private static JLabel value()
	{
		JLabel l = new JLabel("-", SwingConstants.RIGHT);
		l.setForeground(Color.WHITE);
		return l;
	}

	private static void stat(JPanel p, String name, JLabel v)
	{
		JLabel n = new JLabel(name);
		n.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		p.add(n);
		p.add(v);
	}

	private static void pkStat(JPanel p, JLabel name, JLabel v)
	{
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		p.add(name);
		p.add(v);
	}

	private static JLabel header(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		return l;
	}

	private static JComponent left(JComponent c)
	{
		c.setAlignmentX(LEFT_ALIGNMENT);
		return c;
	}

	/** Scroll content that always matches the sidebar's width, so nothing spills off the right edge. */
	private static class WidthTrackingPanel extends JPanel implements Scrollable
	{
		WidthTrackingPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private static JComponent gap()
	{
		return left((JComponent) Box.createVerticalStrut(10));
	}
}
