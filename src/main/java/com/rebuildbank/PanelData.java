package com.rebuildbank;

import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/** Immutable snapshot built on the client thread and rendered on the Swing thread. */
@Value
@Builder
class PanelData
{
	static final PanelData LOGGED_OUT = PanelData.builder().status("Log in to use Temporary Rebuild Bank").build();

	boolean loggedIn;
	boolean active;
	String status;
	String time;
	long bankValue;
	long spent;
	long debt;
	long netValue;
	long gpPerHour;
	GoalType goalType;
	long goalTarget;
	long goalProgress;
	String goalItemName;
	boolean complete;
	long borrowedCount;
	long violations;
	long pkKills;
	long pkAverage;
	@Builder.Default
	List<Row> items = Collections.emptyList();

	@Value
	static class Row
	{
		String name;
		long quantity;
		long value;
	}
}
