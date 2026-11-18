package com.rebuildbank;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.game.SpriteOverride;

/** Icons for the bank's Real bank / Temp bank button (custom sprites, negative ids). */
@Getter
@RequiredArgsConstructor
enum ToggleSprites implements SpriteOverride
{
	/** Shown in the real bank view: the plugin's chest, click to switch to the temporary bank. */
	SHOW_TEMP(-19_870_001, "toggle_temp.png"),
	/** Shown in the temporary bank view (on a gold-tinted button): coins, click to switch to the real bank. */
	SHOW_REAL(-19_870_002, "toggle_real.png");

	private final int spriteId;
	private final String fileName;
}
