package com.rebuildbank;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches RuneLite with this plugin loaded: gradlew run */
public class RebuildBankPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RebuildBankPlugin.class);
		RuneLite.main(args);
	}
}
