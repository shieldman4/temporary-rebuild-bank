package com.rebuildbank;

/** Which existing untradeables are offered for unlocking. */
public enum UntradeableFilter
{
	PERDU("Perdu items"),
	COMBAT("Combat gear"),
	ALL("Everything");

	private final String label;

	UntradeableFilter(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
