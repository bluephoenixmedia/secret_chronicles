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
import com.iangame.engine.DoorManager;
import com.iangame.engine.RayCaster;
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

    /** Collision radius around each table centre (world units). */
    private static final double TABLE_RADIUS   = 0.42;

    private static final float SENSITIVITY_MIN     = 0.0005f;
    private static final float SENSITIVITY_MAX     = 0.008f;
    private static final float SENSITIVITY_DEFAULT = 0.0025f;
    private static final float SENSITIVITY_STEP    = 0.0001f;

    private GameMap      map;
    private Player       player;
    private GameRenderer renderer;
    private DoorManager  doorManager;

    private float   mouseSensitivity    = SENSITIVITY_DEFAULT;
    private boolean flashlightOn        = false;
    private float   timeSinceStart        = 0f;
    private boolean flashlightEverUsed   = false;
    private int     flashlightOnCount    = 0;
    private boolean doorEverInteracted   = false;
    private boolean thirtySecEventFired  = false;
    private float   eventFiredAt        = -1f;
    private int     flickerRoomIdx      = -1;
    private boolean lightsOutFired      = false;
    private boolean lightsOut           = false;
    private float   lightsOutAt         = -1f;
    /** Battery level 0..1; drains while flashlight is on (~3 minutes total). */
    private float   batteryLevel     = 1.0f;
    private static final float BATTERY_DRAIN = 1f / 180f;

    private boolean infoTextsEnabled = true;

    // Settings overlay (visible when cursor is released)
    private Stage  settingsStage;
    private Skin   skin;
    private Label  valueLabel;
    private Label  infoToggleLabel;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void create() {
        map         = new GameMap();
        player      = new Player(4.5, 4.5);
        renderer    = new GameRenderer();
        doorManager = new DoorManager(map);

        buildSettingsUI();
        Gdx.input.setInputProcessor(settingsStage);
        Gdx.input.setCursorCatched(true);
        Gdx.app.log("IanGame", "Game started — LibGDX " + com.badlogic.gdx.Version.VERSION);
    }

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();
        timeSinceStart += dt;

        handleInput(dt);

        if (flashlightOn) {
            batteryLevel = Math.max(0f, batteryLevel - BATTERY_DRAIN * dt);
            if (batteryLevel == 0f) {
                flashlightOn = false;
                renderer.setFlashlight(false);
            }
        }
        renderer.setBattery(batteryLevel);

        doorManager.update(dt);
        renderer.update(dt);
        renderer.render(player, map, doorManager);

        if (timeSinceStart >= 30f && !thirtySecEventFired) {
            thirtySecEventFired = true;
            eventFiredAt = timeSinceStart;
            flickerRoomIdx = getRoomLightIndex();
            if (flickerRoomIdx >= 0) renderer.setRoomFlickerIntense(flickerRoomIdx, true);
            doorManager.openAll();
            doorManager.setLockOpen(true);
        }

        if (thirtySecEventFired && !lightsOutFired && (timeSinceStart - eventFiredAt) >= 5f) {
            lightsOutFired = true;
            lightsOut      = true;
            lightsOutAt    = timeSinceStart;
            renderer.setLightsOut(true);
        }
        if (lightsOut && (timeSinceStart - lightsOutAt) >= 10f) {
            lightsOut = false;
            renderer.setLightsOut(false);
            if (flickerRoomIdx >= 0) renderer.setRoomFlickerIntense(flickerRoomIdx, false);
            doorManager.setLockOpen(false);
        }

        if (flashlightOn) renderer.drawBatteryMeter(batteryLevel, infoTextsEnabled && flashlightOnCount >= 2);

        if (infoTextsEnabled) {
            java.util.List<String> overlayTexts = new java.util.ArrayList<>();
            String nearLabel = getNearbyObjectLabel();
            if (nearLabel != null)                              overlayTexts.add(nearLabel);
            if (timeSinceStart >= 10f && !flashlightEverUsed)  overlayTexts.add("Press F to toggle the flashlight");
            if (isNearTileType(7) && !doorEverInteracted)      overlayTexts.add("Press E to open doors");
            if (!overlayTexts.isEmpty())
                renderer.drawOverlayTexts(overlayTexts.toArray(new String[0]));
        }

        if (!Gdx.input.isCursorCatched()) {
            settingsStage.act(dt);
            settingsStage.draw();
        }

        renderer.drawFadeOverlay();
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
            movePlayer(1.0, dt);
        if (Gdx.input.isKeyPressed(Keys.S) || Gdx.input.isKeyPressed(Keys.DOWN))
            movePlayer(-1.0, dt);

        // Strafe
        if (Gdx.input.isKeyPressed(Keys.A))
            strafe(-1.0, dt);
        if (Gdx.input.isKeyPressed(Keys.D))
            strafe(1.0, dt);

        // Toggle flashlight
        if (Gdx.input.isKeyJustPressed(Keys.F)) {
            flashlightOn       = !flashlightOn;
            flashlightEverUsed = true;
            if (flashlightOn) flashlightOnCount++;
            renderer.setFlashlight(flashlightOn);
        }

        // Interact with door
        if (Gdx.input.isKeyJustPressed(Keys.E)) {
            doorManager.tryInteract(player.x, player.y, player.dirX, player.dirY);
            doorEverInteracted = true;
        }

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

    /** Moves the player forward (+1) or backward (-1), respecting walls, doors, and tables. */
    private void movePlayer(double delta, double dt) {
        double speed = player.moveSpeed * delta * dt;
        double newX  = player.x + player.dirX * speed;
        double newY  = player.y + player.dirY * speed;
        if (isWalkable((int) newX, (int) player.y) && !tableBlocks(newX, player.y) && !cabinetBlocks(newX, player.y)) player.x = newX;
        if (isWalkable((int) player.x, (int) newY) && !tableBlocks(player.x, newY) && !cabinetBlocks(player.x, newY)) player.y = newY;
    }

    /** Strafes the player perpendicular to their view direction, respecting walls, doors, tables, and cabinets. */
    private void strafe(double delta, double dt) {
        double speed = player.moveSpeed * delta * dt;
        double newX  = player.x + player.planeX * speed;
        double newY  = player.y + player.planeY * speed;
        if (isWalkable((int) newX, (int) player.y) && !tableBlocks(newX, player.y) && !cabinetBlocks(newX, player.y)) player.x = newX;
        if (isWalkable((int) player.x, (int) newY) && !tableBlocks(player.x, newY) && !cabinetBlocks(player.x, newY)) player.y = newY;
    }

    /** Empty tiles and sufficiently-open doors are walkable. */
    private boolean isWalkable(int col, int row) {
        int cell = map.getCell(col, row);
        return cell == 0 || (cell == 7 && doorManager.isPassable(col, row));
    }

    /** Returns the label for the nearest interactable object, or null if none. */
    private String getNearbyObjectLabel() {
        if (isNearTable())          return "This is a table";
        if (isNearCabinet())        return "This is a cabinet";
        if (isNearTileType(7))      return "This is a door";
        if (isNearTileType(8))      return "This is a window";
        return null;
    }

    /**
     * Returns the LIGHTS index (0-8) for the room the player is currently in,
     * or -1 if they are in a corridor or gap.
     */
    private int getRoomLightIndex() {
        double x = player.x, y = player.y;
        int col = (x >= 1 && x < 9)  ? 0 : (x >= 13 && x < 25) ? 1 : (x >= 28 && x < 35) ? 2 : -1;
        int row = (y >= 1 && y < 9)  ? 0 : (y >= 13 && y < 23) ? 1 : (y >= 26 && y < 35) ? 2 : -1;
        if (col < 0 || row < 0) return -1;
        return row * 3 + col;
    }

    /** Returns true if the player is close enough to a table to trigger the info label. */
    private boolean isNearTable() {
        final double TOUCH_RADIUS = TABLE_RADIUS + 0.2;
        double r2 = TOUCH_RADIUS * TOUCH_RADIUS;
        for (float[] t : RayCaster.TABLES) {
            double dx = player.x - t[0], dy = player.y - t[1];
            if (dx * dx + dy * dy < r2) return true;
        }
        return false;
    }

    /** Returns true if the player is within interaction range of any cabinet. */
    private boolean isNearCabinet() {
        final float margin = 0.3f;
        for (float[] c : RayCaster.CABINET_BOXES) {
            if (player.x > c[0] - c[2] - margin && player.x < c[0] + c[2] + margin &&
                player.y > c[1] - c[3] - margin && player.y < c[1] + c[3] + margin) return true;
        }
        return false;
    }

    /** Returns true if any map tile of the given type is within ~0.7 units of the player. */
    private boolean isNearTileType(int type) {
        int x0 = (int)(player.x - 0.7), x1 = (int)(player.x + 0.7);
        int y0 = (int)(player.y - 0.7), y1 = (int)(player.y + 0.7);
        for (int row = y0; row <= y1; row++)
            for (int col = x0; col <= x1; col++)
                if (map.getCell(col, row) == type) return true;
        return false;
    }

    /** Returns true if the given position is within TABLE_RADIUS of any table. */
    private boolean tableBlocks(double x, double y) {
        double r2 = TABLE_RADIUS * TABLE_RADIUS;
        for (float[] t : RayCaster.TABLES) {
            double dx = x - t[0], dy = y - t[1];
            if (dx * dx + dy * dy < r2) return true;
        }
        return false;
    }

    /** Returns true if the given position is inside any cabinet AABB (with a small margin). */
    private boolean cabinetBlocks(double x, double y) {
        final float margin = 0.05f;
        for (float[] c : RayCaster.CABINET_BOXES) {
            if (x > c[0] - c[2] - margin && x < c[0] + c[2] + margin &&
                y > c[1] - c[3] - margin && y < c[1] + c[3] + margin) return true;
        }
        return false;
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

        Label title      = new Label("Mouse Sensitivity", skin);
        valueLabel       = new Label(String.format("%.4f", mouseSensitivity), skin);
        infoToggleLabel  = new Label("Info texts: ON", skin);
        infoToggleLabel.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                infoTextsEnabled = !infoTextsEnabled;
                infoToggleLabel.setText("Info texts: " + (infoTextsEnabled ? "ON" : "OFF"));
            }
        });
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
        table.add(infoToggleLabel).left().colspan(2);
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
