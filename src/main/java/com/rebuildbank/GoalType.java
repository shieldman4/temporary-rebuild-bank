package com.rebuildbank;

public enum GoalType
{
	NONE("No goal"),
	NET_VALUE("Temporary bank value (gp)"),
	COINS("Coins in temporary bank"),
	ITEM("Quantity of an item");

	private final String label;

	GoalType(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
