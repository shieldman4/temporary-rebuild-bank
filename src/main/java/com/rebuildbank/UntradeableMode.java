package com.rebuildbank;

/** How untradeables that were already in your bank when the run started are treated. */
public enum UntradeableMode
{
	NOT_ALLOWED("Locked"),
	ALLOWED("Free"),
	REPAIR_COST("Perdu price"),
	CUSTOM("Custom price");

	private final String label;

	UntradeableMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
