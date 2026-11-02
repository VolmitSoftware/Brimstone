package com.volmit.brimstone.presentation;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;

public final class BrimstoneTheme {
    public static final String EMBER = "#ff6b6b";
    public static final String IVORY = "#fff1d0";

    private BrimstoneTheme() {
    }

    public static DirectorMiniMenu.Theme theme() {
        return new DirectorMiniMenu.Theme(EMBER, IVORY, EMBER, IVORY,
                IVORY, EMBER, IVORY, "#c7bcb5");
    }
}
