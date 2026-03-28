package com.iangame.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.iangame.IanGame;

/**
 * Desktop (Windows/Mac/Linux) entry point using the LWJGL3 back-end.
 *
 * <p>Adjust {@code setWindowedMode} to change the initial window size.
 * The internal render resolution is set separately in
 * {@link com.iangame.renderer.GameRenderer}.
 */
public class DesktopLauncher {

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();

        config.setTitle("IanGame — Raycaster");
        config.setWindowedMode(1280, 720);
        config.setForegroundFPS(60);
        config.setResizable(true);
        config.useVsync(true);

        new Lwjgl3Application(new IanGame(), config);
    }
}
