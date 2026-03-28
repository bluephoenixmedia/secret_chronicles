package com.iangame;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.iangame.player.Player;
import com.iangame.renderer.GameRenderer;
import com.iangame.world.GameMap;

/**
 * Main LibGDX {@link com.badlogic.gdx.ApplicationListener} implementation.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Bootstrap the player, map, and renderer.</li>
 *   <li>Drive the game loop (input → update → render).</li>
 *   <li>Dispose of GPU resources on exit.</li>
 * </ul>
 *
 * <p><b>Controls</b>
 * <pre>
 *   W / ↑       — move forward
 *   S / ↓       — move backward
 *   A / ←       — strafe left
 *   D / →       — strafe right
 *   ← / →       — rotate left/right
 *   Mouse X     — look left/right (inverted; cursor captured)
 *   Escape      — open/close settings; press again to quit
 * </pre>
 */
public class IanGame extends ApplicationAdapter {

    private static final float SENSITIVITY_MIN     = 0.0005f;
    private static final float SENSITIVITY_MAX     = 0.008f;
    private static final float SENSITIVITY_DEFAULT = 0.0025f;
    private static final float SENSITIVITY_STEP    = 0.0001f;

    private GameMap      map;
    private Player       player;
    private GameRenderer renderer;

    private float  mouseSensitivity = SENSITIVITY_DEFAULT;

    // Settings overlay (visible when cursor is released)
    private Stage  settingsStage;
    private Skin   skin;
    private Label  valueLabel;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void create() {
        map      = new GameMap();
        player   = new Player(2.5, 2.5);
        renderer = new GameRenderer();

        buildSettingsUI();
        Gdx.input.setInputProcessor(settingsStage);
        Gdx.input.setCursorCatched(true);
        Gdx.app.log("IanGame", "Game started — LibGDX " + com.badlogic.gdx.Version.VERSION);
    }

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();

        handleInput(dt);
        renderer.render(player, map);

        if (!Gdx.input.isCursorCatched()) {
            settingsStage.act(dt);
            settingsStage.draw();
        }
    }

    @Override
    public void resize(int width, int height) {
        renderer.resize(width, height);
        settingsStage.getViewport().update(width, height, true);
    }

    @Override
    public void dispose() {
        renderer.dispose();
        settingsStage.dispose();
        skin.dispose();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private void handleInput(float dt) {
        // Escape: release cursor to show settings; press again to quit
        if (Gdx.input.isKeyJustPressed(Keys.ESCAPE)) {
            if (Gdx.input.isCursorCatched()) Gdx.input.setCursorCatched(false);
            else Gdx.app.exit();
        }

        // Forward / backward
        if (Gdx.input.isKeyPressed(Keys.W) || Gdx.input.isKeyPressed(Keys.UP))
            player.move(1.0, dt, map.getGrid());
        if (Gdx.input.isKeyPressed(Keys.S) || Gdx.input.isKeyPressed(Keys.DOWN))
            player.move(-1.0, dt, map.getGrid());

        // Strafe
        if (Gdx.input.isKeyPressed(Keys.A))
            strafe(-1.0, dt);
        if (Gdx.input.isKeyPressed(Keys.D))
            strafe(1.0, dt);

        // Keyboard rotate
        if (Gdx.input.isKeyPressed(Keys.LEFT))
            player.rotate(-1.0, dt);
        if (Gdx.input.isKeyPressed(Keys.RIGHT))
            player.rotate(1.0, dt);

        // Mouse look — inverted X: positive delta (mouse right) turns left
        if (Gdx.input.isCursorCatched()) {
            int mouseDeltaX = Gdx.input.getDeltaX();
            if (mouseDeltaX != 0)
                player.rotateByAngle(mouseDeltaX * mouseSensitivity);
        }
    }

    /**
     * Strafes the player perpendicular to their view direction.
     * The strafe direction is the camera plane vector (already perpendicular to dir).
     */
    private void strafe(double delta, double dt) {
        double speed = player.moveSpeed * delta * dt;
        double newX  = player.x + player.planeX * speed;
        double newY  = player.y + player.planeY * speed;

        int[][] grid = map.getGrid();
        if (grid[(int) player.y][(int) newX] == 0) player.x = newX;
        if (grid[(int) newY][(int) player.x] == 0) player.y = newY;
    }

    // ── Settings UI ───────────────────────────────────────────────────────────

    private void buildSettingsUI() {
        settingsStage = new Stage(new ScreenViewport());
        skin = buildSkin();

        Slider slider = new Slider(SENSITIVITY_MIN, SENSITIVITY_MAX, SENSITIVITY_STEP, false, skin);
        slider.setValue(mouseSensitivity);
        slider.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                mouseSensitivity = slider.getValue();
                valueLabel.setText(String.format("%.4f", mouseSensitivity));
            }
        });

        Label title  = new Label("Mouse Sensitivity", skin);
        valueLabel   = new Label(String.format("%.4f", mouseSensitivity), skin);
        Label hint   = new Label("Press Escape to resume", skin);
        Label closeX = new Label("  X  ", skin);
        closeX.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                Gdx.input.setCursorCatched(true);
            }
        });

        Table table = new Table();
        table.setBackground(new TextureRegionDrawable(skin.get("panel-bg", Texture.class)));
        table.pad(24);
        // Header row: title expands left, X sits right
        table.add(title).expandX().left().padBottom(12);
        table.add(closeX).right().padBottom(12).row();
        // Remaining rows span both columns
        table.add(slider).width(300).padBottom(6).colspan(2).row();
        table.add(valueLabel).padBottom(20).colspan(2).row();
        table.add(hint).colspan(2);
        Table root = new Table();
        root.setFillParent(true);
        root.center();
        root.add(table);

        settingsStage.addActor(root);
    }

    private Skin buildSkin() {
        Skin s = new Skin();

        BitmapFont font = new BitmapFont();
        s.add("default", font);

        // Panel background
        Pixmap bgPix = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        bgPix.setColor(0.05f, 0.05f, 0.05f, 0.85f);
        bgPix.fill();
        s.add("panel-bg", new Texture(bgPix));
        bgPix.dispose();

        // Slider track
        Pixmap trackPix = new Pixmap(1, 6, Pixmap.Format.RGBA8888);
        trackPix.setColor(0.55f, 0.55f, 0.55f, 1f);
        trackPix.fill();
        s.add("slider-track", new Texture(trackPix));
        trackPix.dispose();

        // Slider knob
        Pixmap knobPix = new Pixmap(20, 20, Pixmap.Format.RGBA8888);
        knobPix.setColor(1f, 1f, 1f, 1f);
        knobPix.fillCircle(10, 10, 10);
        s.add("slider-knob", new Texture(knobPix));
        knobPix.dispose();

        Slider.SliderStyle sliderStyle = new Slider.SliderStyle();
        sliderStyle.background = new TextureRegionDrawable(s.get("slider-track", Texture.class));
        sliderStyle.knob       = new TextureRegionDrawable(s.get("slider-knob",  Texture.class));
        s.add("default-horizontal", sliderStyle);

        Label.LabelStyle labelStyle = new Label.LabelStyle();
        labelStyle.font = font;
        s.add("default", labelStyle);

        return s;
    }
}
