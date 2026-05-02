package com.iangame;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
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
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.iangame.engine.DoorManager;
import com.iangame.player.Player;
import com.iangame.renderer.GameRenderer;
import com.iangame.world.GameMap;
import com.iangame.world.MapGenerator;

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

    private float[][] tables;
    private float[][] cabinetBoxes;
    private int[][]   roomsData;

    private float   mouseSensitivity    = SENSITIVITY_DEFAULT;
    private boolean flashlightOn        = false;
    private float   timeSinceStart        = 0f;
    private boolean flashlightEverUsed   = false;
    private int     flashlightOnCount    = 0;
    private boolean doorEverInteracted   = false;
    private boolean warningTriggered     = false;
    private boolean thirtySecEventFired  = false;
    private float   eventFiredAt        = -1f;
    private float   nextCycleAt         = 30f;
    private boolean isFirstCycle        = true;
    private float   staticLevel         = 0f;
    private float   firstMovedAt        = -1f;
    private boolean gameOver            = false;
    private double  lastPlayerX, lastPlayerY;
    private int     flickerRoomIdx      = -1;
    private boolean lightsOutFired          = false;
    private boolean lightsOut               = false;
    private float   lightsOutAt             = -1f;
    private float   lightsRestoredAt        = -1f;
    private boolean postLightsWarningFired  = false;
    private boolean pytyvoFired             = false;
    private float   pytyvoFiredAt           = -1f;
    private int     lastRoomIdx             = -2;  // -2 = uninitialized
    private float   redTintLevel            = 0f;
    private float[][] bloodSpots            = new float[0][2];
    private float   lockedDoorMsgAt        = -1f;
    /** {doorCol, doorRow, endCol, endRow} for each exterior hallway. */
    private int[][] hallwayEndpoints = new int[0][];
/** Per-hallway scheduled close time (-1 = not yet entered). */
    private float[] hallwayCloseTimes = new float[0];
    /** Indices of hallways whose entry has already been detected. */
    private java.util.Set<Integer> hallwayEntered = new java.util.HashSet<>();
    /** True after the player has entered at least one hallway (shadow skipped on first entry). */
    private boolean hallwayEnteredOnce = false;
    /** When true the player cannot move (freeze-scare event). */
    private boolean playerFrozen          = false;
    private float   playerFreezeTimer     = 0f;
    /** Duration to freeze when the player enters the hallway of a slow-shadow event; 0 = none pending. */
    private float   pendingFreezeOnSight  = 0f;
    /** Hallway index associated with a pending freeze, or -1 if none. */
    private int     pendingFreezeHallwayIdx = -1;
    /** Static overlay intensity driven by shadow proximity (0 = none, 1 = full). */
    private float      shadowProximityStatic   = 0f;
    /** Live positions of lights-out ambient shadows; mutated each frame so the renderer tracks them. */
    private double[][] lightsOutShadowPositions = new double[0][];
    private static final float AMBIENT_SHADOW_SPEED = 4.5f;
    /** Countdown after a shadow touches the player; glitches screen then respawns. */
    private float      shadowTouchTimer        = 0f;
    private java.util.Set<Integer> cabinetKeyIndices = new java.util.HashSet<>();
    private java.util.Set<Integer> lootedCabinets    = new java.util.HashSet<>();
    /** Index of the cabinet whose inventory is currently open, or -1 if none. */
    private int     openCabinetIndex       = -1;

    private Music   bgMusic;
    private int     keysHeld               = 0;
    private boolean oKeyUsed               = false;
    /** Battery level 0..1; drains while flashlight is on (~3 minutes total). */
    private float   batteryLevel     = 1.0f;
    private static final float BATTERY_DRAIN = 1f / 180f;

    private boolean infoTextsEnabled = true;

    // Title-screen demo scene
    private GameMap     demoMap;
    private Player      demoPlayer;
    private DoorManager demoDoorManager;

    // UI stages
    private Stage   titleStage;
    private Stage   settingsStage;
    private Stage   gameOverStage;
    private boolean onTitleScreen = true;
    private Skin    skin;
    private Label  valueLabel;
    private Label  infoToggleLabel;
    private Label  minimapToggleLabel;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void create() {
        renderer = new GameRenderer();

        bgMusic = loadMusic("sounds/music/SCSOUNDTRCK1.mp3");
        if (bgMusic != null) { bgMusic.setLooping(true); bgMusic.setVolume(0.6f); }

        buildSettingsUI();
        buildGameOverUI();
        buildTitleUI();
        initDemoScene();
        Gdx.input.setInputProcessor(titleStage);
        Gdx.input.setCursorCatched(false);
        Gdx.app.log("IanGame", "Started — LibGDX " + com.badlogic.gdx.Version.VERSION);
    }

    private void startGame() {
        MapGenerator.GeneratedMap gen = MapGenerator.generate(new java.util.Random());
        map           = new GameMap(gen.grid);
        player        = new Player(gen.spawnX, gen.spawnY);
        doorManager   = new DoorManager(map);
        tables        = gen.tables;
        cabinetBoxes  = gen.cabinetBoxes;
        roomsData     = gen.rooms;
        renderer.setWorldObjects(gen.lights, gen.tables, gen.cabinetBoxes);
        doorManager.setLockedDoors(gen.lockedDoorKeys);
        renderer.setLockedDoors(gen.lockedDoorKeys);
        cabinetKeyIndices = gen.cabinetKeyIndices;
        map.outerMargin   = gen.outerMargin;
        hallwayEndpoints  = gen.hallwayEndpoints;
        hallwayCloseTimes = new float[hallwayEndpoints.length];
        hallwayEntered    = new java.util.HashSet<>();
        java.util.Arrays.fill(hallwayCloseTimes, -1f);
        lastPlayerX = player.x;
        lastPlayerY = player.y;
        onTitleScreen = false;
        if (bgMusic != null) bgMusic.play();
        Gdx.input.setInputProcessor(settingsStage);
        Gdx.input.setCursorCatched(true);
    }

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();

        if (onTitleScreen) {
            demoPlayer.rotate(0.08, dt);
            renderer.update(dt);
            boolean minimapWas = renderer.isShowMinimap();
            renderer.setShowMinimap(false);
            renderer.render(demoPlayer, demoMap, demoDoorManager);
            renderer.setShowMinimap(minimapWas);
            titleStage.act(dt);
            titleStage.draw();
            if (Gdx.input.getInputProcessor() == settingsStage) {
                settingsStage.act(dt);
                settingsStage.draw();
            }
            return;
        }

        timeSinceStart += dt;

        // Red cabinet click — activate pytyvo, begin fading static, spawn blood
        if (Gdx.input.isButtonJustPressed(Buttons.LEFT) && isNearRedCabinet()) {
            pytyvoFired   = true;
            pytyvoFiredAt = timeSinceStart;
            firstMovedAt  = -1f;   // cancel any pending game-over countdown
            playRingTone();
            bloodSpots = generateBloodSpots();
            renderer.setBloodSpots(bloodSpots);
        }

        // Cabinet inventory — left click to take key while open
        if (openCabinetIndex >= 0) {
            // Close if player walked away
            if (getNearCabinetIndex() != openCabinetIndex) {
                openCabinetIndex = -1;
            } else if (Gdx.input.isButtonJustPressed(Buttons.LEFT)) {
                if (cabinetKeyIndices.contains(openCabinetIndex) && !lootedCabinets.contains(openCabinetIndex)) {
                    keysHeld++;
                    lootedCabinets.add(openCabinetIndex);
                }
                openCabinetIndex = -1;
            }
        }

        // Fade static to 0 while pytyvo is active
        if (pytyvoFired && staticLevel > 0f)
            staticLevel = Math.max(0f, staticLevel - dt);

        // Dismiss game over once static has fully faded
        if (gameOver && pytyvoFired && staticLevel == 0f) {
            gameOver = false;
            Gdx.input.setCursorCatched(true);
            Gdx.input.setInputProcessor(settingsStage);
        }

        if (gameOver) {
            renderer.render(player, map, doorManager);
            if (staticLevel > 0f) {
                if (!pytyvoFired) renderer.drawRedTint(staticLevel * 0.45f);
                renderer.drawStaticOverlay(staticLevel, pytyvoFired);
            }
            gameOverStage.act(dt);
            gameOverStage.draw();
            return;
        }

        // Capture position before input to detect movement this frame
        double prevX = player.x, prevY = player.y;

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
        checkHallwayEntry(dt);
        // Freeze when the player physically steps into the hallway of a slow-shadow event
        if (pendingFreezeOnSight > 0f && pendingFreezeHallwayIdx >= 0) {
            int[] ep = hallwayEndpoints[pendingFreezeHallwayIdx];
            double dirX = ep[2] - ep[0], dirY = ep[3] - ep[1];
            double len  = Math.hypot(dirX, dirY);
            double proj = ((player.x - (ep[0] + 0.5)) * (dirX / len)
                         + (player.y - (ep[1] + 0.5)) * (dirY / len));
            if (proj > 0.5) {
                playerFrozen            = true;
                playerFreezeTimer       = pendingFreezeOnSight;
                pendingFreezeOnSight    = 0f;
                pendingFreezeHallwayIdx = -1;
            }
        }
        if (playerFrozen) {
            playerFreezeTimer -= dt;
            if (playerFreezeTimer <= 0f) playerFrozen = false;
        }
        renderer.update(dt);
        renderer.render(player, map, doorManager);

        // Pytyvo red tint — accumulates each time the player enters a new room,
        // then slowly fades away over time.
        if (pytyvoFired) {
            int curRoom = getRoomLightIndex();
            if (curRoom >= 0 && curRoom != lastRoomIdx)
                redTintLevel = Math.min(0.55f, redTintLevel + 0.07f);
            lastRoomIdx = curRoom;
        }
        if (redTintLevel > 0f) {
            redTintLevel = Math.max(0f, redTintLevel - 0.03f * dt);
            renderer.drawRedTint(redTintLevel);
        }

        // Static / game-over effect — only from second cycle onward, blocked once pytyvo is used
        boolean playerMoved = Math.abs(player.x - prevX) > 0.001 || Math.abs(player.y - prevY) > 0.001;
        if (lightsOut && !isFirstCycle && !pytyvoFired && playerMoved) {
            staticLevel = 1f;
            if (firstMovedAt < 0f) firstMovedAt = timeSinceStart;
        }
        if (firstMovedAt >= 0f && !pytyvoFired && (timeSinceStart - firstMovedAt) >= 2f && !gameOver) {
            gameOver = true;
            Gdx.input.setCursorCatched(false);
            Gdx.input.setInputProcessor(gameOverStage);
        }

        if (timeSinceStart >= 25f && !warningTriggered) {
            warningTriggered = true;
            playRingTone();
        }

        if (!thirtySecEventFired && timeSinceStart >= nextCycleAt) {
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
            if (bgMusic != null) bgMusic.pause();
            spawnLightsOutAmbientShadows();
        }
        if (!gameOver && lightsOut && (timeSinceStart - lightsOutAt) >= 10f) {
            lightsOut = false;
            lightsRestoredAt = timeSinceStart;
            renderer.setLightsOut(false);
            lightsOutShadowPositions = new double[0][];
            renderer.setAmbientShadows(lightsOutShadowPositions);
            if (bgMusic != null) bgMusic.play();
            if (flickerRoomIdx >= 0) renderer.setRoomFlickerIntense(flickerRoomIdx, false);
            doorManager.setLockOpen(false);
            thirtySecEventFired = false;
            lightsOutFired = false;
            nextCycleAt = timeSinceStart + 30f;
            isFirstCycle = false;
            staticLevel   = 0f;
            firstMovedAt  = -1f;   // reset so next cycle starts fresh
            pytyvoFired   = false;
        }
        if (lightsRestoredAt >= 0 && !postLightsWarningFired && (timeSinceStart - lightsRestoredAt) >= 2f) {
            postLightsWarningFired = true;
            playRingTone();
        }

        if (flashlightOn) renderer.drawBatteryMeter(batteryLevel, infoTextsEnabled && flashlightOnCount >= 2);

        // Collect all overlay texts into one list so they stack without overlap.
        // "This door is locked." always shows (not gated by infoTextsEnabled).
        java.util.List<String> overlayTexts = new java.util.ArrayList<>();
        if (lockedDoorMsgAt >= 0f && (timeSinceStart - lockedDoorMsgAt) < 3f)
            overlayTexts.add("This door is locked.");
        if (infoTextsEnabled) {
            if (isFirstCycle && timeSinceStart >= 25f && !thirtySecEventFired)  overlayTexts.add("**Only use flashlight when needed.**");
            if (postLightsWarningFired && (timeSinceStart - lightsRestoredAt) < 7f) overlayTexts.add("**When the lights are out, don't move.**");
            if (pytyvoFired && (timeSinceStart - pytyvoFiredAt) < 5f) overlayTexts.add("**????? effect**");
            String nearLabel = getNearbyObjectLabel();
            if (nearLabel != null)                              overlayTexts.add(nearLabel);
            if (timeSinceStart >= 10f && !flashlightEverUsed)  overlayTexts.add("Press F to toggle the flashlight");
            if (isNearTileType(7) && !doorEverInteracted)      overlayTexts.add("Press E to open doors");
        }
        if (!overlayTexts.isEmpty())
            renderer.drawOverlayTexts(overlayTexts.toArray(new String[0]));

        if (openCabinetIndex >= 0) {
            boolean hasKey = cabinetKeyIndices.contains(openCabinetIndex)
                          && !lootedCabinets.contains(openCabinetIndex);
            renderer.drawCabinetUI(hasKey);
        }

        if (!Gdx.input.isCursorCatched()) {
            settingsStage.act(dt);
            settingsStage.draw();
        }

        if (staticLevel > 0f) {
            if (!pytyvoFired) renderer.drawRedTint(staticLevel * 0.45f);
            renderer.drawStaticOverlay(staticLevel, pytyvoFired);
        }

        // Glitch sequence after shadow touch — freeze player, blast static, then respawn
        if (shadowTouchTimer > 0f) {
            shadowTouchTimer -= dt;
            renderer.drawStaticOverlay(0.6f + (float)(Math.random() * 0.4f), false);
            renderer.drawFadeOverlay();
            if (shadowTouchTimer <= 0f) restartGame();
            return;
        }

        // Move lights-out ambient shadows toward the player
        for (double[] s : lightsOutShadowPositions) {
            double adx = player.x - s[0], ady = player.y - s[1];
            double adist = Math.sqrt(adx * adx + ady * ady);
            if (adist < 0.9) { triggerShadowTouch(); return; }
            double spd = AMBIENT_SHADOW_SPEED * dt;
            s[0] += (adx / adist) * spd;
            s[1] += (ady / adist) * spd;
        }

        // Shadow proximity — static builds as any shadow closes in; touch triggers glitch
        double closestShadowDist = Double.MAX_VALUE;
        if (renderer.isShadowFigureActive()) {
            double dx = renderer.getShadowX() - player.x;
            double dy = renderer.getShadowY() - player.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 0.9) { triggerShadowTouch(); return; }
            closestShadowDist = Math.min(closestShadowDist, dist);
        }
        for (double[] s : lightsOutShadowPositions) {
            double dx = s[0] - player.x, dy = s[1] - player.y;
            closestShadowDist = Math.min(closestShadowDist, Math.sqrt(dx * dx + dy * dy));
        }
        if (closestShadowDist < Double.MAX_VALUE) {
            final double STATIC_START = 6.0;
            shadowProximityStatic = (float) Math.max(0.0, Math.min(1.0, 1.0 - closestShadowDist / STATIC_START));
        } else {
            shadowProximityStatic = Math.max(0f, shadowProximityStatic - dt * 2f);
        }
        if (shadowProximityStatic > 0f)
            renderer.drawStaticOverlay(shadowProximityStatic, false);

        renderer.drawFadeOverlay();
    }

    /** Called when any shadow reaches the player — starts the glitch-then-respawn sequence. */
    private void triggerShadowTouch() {
        playerFrozen     = true;
        shadowTouchTimer = 1.2f;
    }

    @Override
    public void resize(int width, int height) {
        renderer.resize(width, height);
        titleStage.getViewport().update(width, height, true);
        settingsStage.getViewport().update(width, height, true);
        gameOverStage.getViewport().update(width, height, true);
    }

    @Override
    public void dispose() {
        if (bgMusic != null) { bgMusic.stop(); bgMusic.dispose(); }
        renderer.dispose();
        titleStage.dispose();
        settingsStage.dispose();
        gameOverStage.dispose();
        skin.dispose();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private void handleInput(float dt) {
        // Escape: release cursor to show settings; press again to quit
        if (Gdx.input.isKeyJustPressed(Keys.ESCAPE)) {
            if (Gdx.input.isCursorCatched()) Gdx.input.setCursorCatched(false);
            else Gdx.app.exit();
        }

        // All movement and look are blocked during a freeze scare.
        if (playerFrozen) return;

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

        // Obtain a key — only works once per run
        if (Gdx.input.isKeyJustPressed(Keys.O) && !oKeyUsed) {
            oKeyUsed = true;
            keysHeld++;
        }

        // Toggle flashlight
        if (Gdx.input.isKeyJustPressed(Keys.F)) {
            flashlightOn       = !flashlightOn;
            flashlightEverUsed = true;
            if (flashlightOn) flashlightOnCount++;
            renderer.setFlashlight(flashlightOn);
        }

        // Interact with door or cabinet
        if (Gdx.input.isKeyJustPressed(Keys.E)) {
            int[] lockedDoor = doorManager.getLockedDoorInRange(player.x, player.y, player.dirX, player.dirY);
            int nearCab = getNearCabinetIndex();
            if (isNearTileType(9)) {
                // Emergency exit — always locked
                lockedDoorMsgAt = timeSinceStart;
            } else if (lockedDoor != null) {
                if (keysHeld > 0) {
                    // Consume one key and unlock the door
                    keysHeld--;
                    doorManager.unlockDoor(lockedDoor[0], lockedDoor[1]);
                    renderer.setLockedDoors(doorManager.getLockedDoorKeys());
                } else {
                    lockedDoorMsgAt = timeSinceStart;
                }
            } else if (nearCab >= 0) {
                // Toggle cabinet inventory open/closed
                openCabinetIndex = (openCabinetIndex == nearCab) ? -1 : nearCab;
            } else {
                // Scan for the door about to be opened.
                int[] hdoor = findHallwayDoorInFront();
                if (hdoor != null) {
                    int hi = hdoor[2];
                    if (!hallwayEntered.contains(hi)) {
                        hallwayEntered.add(hi);
                        hallwayCloseTimes[hi] = 2.0f;
                        if (!hallwayEnteredOnce) {
                            hallwayEnteredOnce = true;
                        } else {
                            double roll = Math.random();
                            if (roll < 0.15) {
                                spawnSlowHallwayShadow(hi);  // freeze + very slow shadow (15%)
                            } else if (roll < 0.75) {
                                spawnHallwayShadow(hi);      // slow shadow (60%)
                            }
                        }
                    }
                }
                doorManager.tryInteract(player.x, player.y, player.dirX, player.dirY);
                doorEverInteracted = true;
            }
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

    /**
     * Locates a music file by trying several paths relative to the working
     * directory (handles both "run from assets/" and "run from project root").
     * Returns null and logs a warning if the file cannot be found.
     */
    private com.badlogic.gdx.audio.Music loadMusic(String relativePath) {
        String[] candidates = {
            relativePath,                   // when workingDir = assets/
            "assets/" + relativePath,       // when workingDir = project root
            "../assets/" + relativePath     // when workingDir = desktop/
        };
        for (String candidate : candidates) {
            java.io.File f = new java.io.File(candidate);
            if (!f.isAbsolute())
                f = new java.io.File(System.getProperty("user.dir"), candidate);
            if (f.exists()) {
                return Gdx.audio.newMusic(Gdx.files.absolute(f.getAbsolutePath()));
            }
        }
        Gdx.app.error("IanGame", "Music file not found: " + relativePath + " (tried from " + System.getProperty("user.dir") + ")");
        return null;
    }

    /** Returns the label for the nearest interactable object, or null if none. */
    private String getNearbyObjectLabel() {
        if (isNearTable()) return "This is a table";
        if (isNearRedCabinet()) return "Left click to enable ?????? effect";
        int nearCab = getNearCabinetIndex();
        if (nearCab >= 0 && openCabinetIndex != nearCab) {
            if (cabinetKeyIndices.contains(nearCab) && !lootedCabinets.contains(nearCab))
                return "This cabinet contains a key  [E]";
            return "This is a cabinet  [E]";
        }
        if (isNearTileType(7)) return "This is a door";
        return null;
    }

    /** Generates and plays a short ring tone on a background thread. */
    private void playRingTone() {
        Thread t = new Thread(() -> {
            final int   SAMPLE_RATE = 44100;
            final float DURATION    = 0.3f;
            final float FREQUENCY   = 1200f;
            int   numSamples = (int)(SAMPLE_RATE * DURATION);
            short[] samples  = new short[numSamples];
            for (int i = 0; i < numSamples; i++) {
                float time     = (float) i / SAMPLE_RATE;
                float envelope = 1.0f - (time / DURATION);   // linear decay
                samples[i] = (short)(envelope * 32767 * Math.sin(2 * Math.PI * FREQUENCY * time));
            }
            com.badlogic.gdx.audio.AudioDevice device = Gdx.audio.newAudioDevice(SAMPLE_RATE, true);
            device.writeSamples(samples, 0, samples.length);
            device.dispose();
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Returns the room light index for the room the player is currently in,
     * or -1 if they are in a corridor or gap.
     */
    private int getRoomLightIndex() {
        for (int i = 0; i < roomsData.length; i++) {
            int cs = roomsData[i][0], rs = roomsData[i][1];
            int w  = roomsData[i][2], h  = roomsData[i][3];
            if (player.x >= cs && player.x < cs + w &&
                player.y >= rs && player.y < rs + h)
                return i;
        }
        return -1;
    }

    /** Returns true if the player is close enough to a table to trigger the info label. */
    private boolean isNearTable() {
        final double TOUCH_RADIUS = TABLE_RADIUS + 0.2;
        double r2 = TOUCH_RADIUS * TOUCH_RADIUS;
        for (float[] t : tables) {
            double dx = player.x - t[0], dy = player.y - t[1];
            if (dx * dx + dy * dy < r2) return true;
        }
        return false;
    }

    /** Generates 3–5 blood splatter positions inside every room except the player's current room. */
    private float[][] generateBloodSpots() {
        int playerRoom = getRoomLightIndex();
        java.util.List<float[]> spots = new java.util.ArrayList<>();
        java.util.Random rng = new java.util.Random();
        for (int i = 0; i < roomsData.length; i++) {
            if (i == playerRoom) continue;
            int cs = roomsData[i][0], rs = roomsData[i][1];
            int w  = roomsData[i][2], h  = roomsData[i][3];
            int count = 3 + rng.nextInt(3);
            for (int j = 0; j < count; j++) {
                float x = cs + 1f + rng.nextFloat() * (w - 2f);
                float y = rs + 1f + rng.nextFloat() * (h - 2f);
                spots.add(new float[]{x, y});
            }
        }
        return spots.toArray(new float[0][]);
    }

    /** Returns true if the player is within interaction range of any red cabinet (every 5th, 1-indexed). */
    private boolean isNearRedCabinet() {
        final float margin = 0.5f;
        for (int i = 0; i < cabinetBoxes.length; i++) {
            if ((i + 1) % 5 != 0) continue;
            float[] c = cabinetBoxes[i];
            if (player.x > c[0] - c[2] - margin && player.x < c[0] + c[2] + margin &&
                player.y > c[1] - c[3] - margin && player.y < c[1] + c[3] + margin) return true;
        }
        return false;
    }

    /** Returns the index of the cabinet the player is within interaction range of, or -1. */
    private int getNearCabinetIndex() {
        final float margin = 0.5f;
        for (int i = 0; i < cabinetBoxes.length; i++) {
            float[] c = cabinetBoxes[i];
            if (player.x > c[0] - c[2] - margin && player.x < c[0] + c[2] + margin &&
                player.y > c[1] - c[3] - margin && player.y < c[1] + c[3] + margin) return i;
        }
        return -1;
    }

    /** Returns true if the player is within interaction range of any cabinet. */
    private boolean isNearCabinet() {
        final float margin = 0.3f;
        for (float[] c : cabinetBoxes) {
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
        for (float[] t : tables) {
            double dx = x - t[0], dy = y - t[1];
            if (dx * dx + dy * dy < r2) return true;
        }
        return false;
    }

    /** Returns true if the given position is inside any cabinet AABB (with a small margin). */
    private boolean cabinetBlocks(double x, double y) {
        final float margin = 0.05f;
        for (float[] c : cabinetBoxes) {
            if (x > c[0] - c[2] - margin && x < c[0] + c[2] + margin &&
                y > c[1] - c[3] - margin && y < c[1] + c[3] + margin) return true;
        }
        return false;
    }

    // ── Game over UI ──────────────────────────────────────────────────────────

    private void buildGameOverUI() {
        gameOverStage = new Stage(new ScreenViewport());

        Label title = new Label("GAME OVER", skin);
        Label retry = new Label("  [ Retry ]  ", skin);
        retry.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { restartGame(); }
        });

        Table panel = new Table();
        panel.setBackground(new TextureRegionDrawable(skin.get("panel-bg", Texture.class)));
        panel.pad(32);
        panel.add(title).padBottom(20).row();
        panel.add(retry);

        Table root = new Table();
        root.setFillParent(true);
        root.center();
        root.add(panel);
        gameOverStage.addActor(root);
    }

    private void restartGame() {
        MapGenerator.GeneratedMap gen = MapGenerator.generate(new java.util.Random());
        map          = new GameMap(gen.grid);
        player       = new Player(gen.spawnX, gen.spawnY);
        doorManager  = new DoorManager(map);
        tables       = gen.tables;
        cabinetBoxes = gen.cabinetBoxes;
        roomsData    = gen.rooms;
        renderer.setWorldObjects(gen.lights, gen.tables, gen.cabinetBoxes);
        doorManager.setLockedDoors(gen.lockedDoorKeys);
        renderer.setLockedDoors(gen.lockedDoorKeys);
        cabinetKeyIndices = gen.cabinetKeyIndices;
        map.outerMargin   = gen.outerMargin;
        hallwayEndpoints  = gen.hallwayEndpoints;
        hallwayCloseTimes = new float[hallwayEndpoints.length];
        hallwayEntered    = new java.util.HashSet<>();
        hallwayEnteredOnce = false;
        playerFrozen          = false;
        playerFreezeTimer     = 0f;
        pendingFreezeOnSight    = 0f;
        pendingFreezeHallwayIdx = -1;
        shadowProximityStatic    = 0f;
        shadowTouchTimer         = 0f;
        lightsOutShadowPositions = new double[0][];
        renderer.setAmbientShadows(lightsOutShadowPositions);
        java.util.Arrays.fill(hallwayCloseTimes, -1f);
        lootedCabinets    = new java.util.HashSet<>();
        openCabinetIndex  = -1;
        keysHeld          = 0;
        oKeyUsed          = false;
        renderer.resetFade();
        lastPlayerX = player.x;
        lastPlayerY = player.y;

        timeSinceStart        = 0f;
        flashlightOn          = false;
        flashlightEverUsed    = false;
        flashlightOnCount     = 0;
        doorEverInteracted    = false;
        warningTriggered      = false;
        thirtySecEventFired   = false;
        eventFiredAt          = -1f;
        flickerRoomIdx        = -1;
        lightsOutFired        = false;
        lightsOut             = false;
        lightsOutAt           = -1f;
        lightsRestoredAt      = -1f;
        postLightsWarningFired = false;
        nextCycleAt           = 30f;
        isFirstCycle          = true;
        staticLevel           = 0f;
        pytyvoFired           = false;
        pytyvoFiredAt         = -1f;
        lastRoomIdx           = -2;
        redTintLevel          = 0f;
        bloodSpots            = new float[0][2];
        renderer.setBloodSpots(bloodSpots);
        lockedDoorMsgAt       = -1f;
        gameOver              = false;
        batteryLevel          = 1.0f;

        renderer.setFlashlight(false);
        renderer.setLightsOut(false);
        if (bgMusic != null) { bgMusic.stop(); bgMusic.play(); }
        Gdx.input.setCursorCatched(true);
        Gdx.input.setInputProcessor(settingsStage);
    }

    // ── Hallway door / shadow helpers ────────────────────────────────────────

    private void checkHallwayEntry(float dt) {
        // Hallway doors no longer lock on entry.
    }

    /** Places stationary shadow figures at the midpoints of 3 random hallways when lights go out. */
    private void spawnLightsOutAmbientShadows() {
        int total = hallwayEndpoints.length;
        if (total == 0) return;
        int count = Math.min(3, total);
        // Shuffle indices to pick 3 unique random hallways
        int[] indices = new int[total];
        for (int i = 0; i < total; i++) indices[i] = i;
        java.util.Random rng = new java.util.Random();
        for (int i = total - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp;
        }
        lightsOutShadowPositions = new double[count][2];
        for (int k = 0; k < count; k++) {
            int[] ep = hallwayEndpoints[indices[k]];
            lightsOutShadowPositions[k][0] = (ep[0] + ep[2]) / 2.0 + 0.5;
            lightsOutShadowPositions[k][1] = (ep[1] + ep[3]) / 2.0 + 0.5;
        }
        renderer.setAmbientShadows(lightsOutShadowPositions);
    }

    /**
     * Scans in front of the player (same range as DoorManager.tryInteract) for a
     * door that belongs to a hallway endpoint.  Returns {col, row, hallwayIndex}
     * or null if none found.
     */
    private int[] findHallwayDoorInFront() {
        for (float dist = 0.4f; dist <= 1.6f; dist += 0.4f) {
            int col = (int)(player.x + player.dirX * dist);
            int row = (int)(player.y + player.dirY * dist);
            for (int i = 0; i < hallwayEndpoints.length; i++) {
                int[] ep = hallwayEndpoints[i];
                if (ep[0] == col && ep[1] == row) return new int[]{col, row, i};
            }
        }
        return null;
    }

    /** Spawns a shadow at the far end of hallway i, running toward the door. Player is not frozen. */
    private void spawnHallwayShadow(int i) {
        pendingFreezeOnSight    = 0f;
        pendingFreezeHallwayIdx = -1;
        int[] ep = hallwayEndpoints[i];
        double doorX = ep[0] + 0.5, doorY = ep[1] + 0.5;
        double farX  = ep[2] + 0.5, farY  = ep[3] + 0.5;
        double fullDist = Math.hypot(doorX - farX, doorY - farY);
        if (fullDist > 2.0) {
            double nx = (doorX - farX) / fullDist, ny = (doorY - farY) / fullDist;
            double dist = fullDist - 1.0;
            renderer.setShadowFigure(farX + nx, farY + ny, nx * 4.0, ny * 4.0, dist);
        }
    }

    /** Spawns a very slow shadow; player freezes when they step into the hallway. */
    private void spawnSlowHallwayShadow(int i) {
        int[] ep = hallwayEndpoints[i];
        double doorX = ep[0] + 0.5, doorY = ep[1] + 0.5;
        double farX  = ep[2] + 0.5, farY  = ep[3] + 0.5;
        double fullDist = Math.hypot(doorX - farX, doorY - farY);
        if (fullDist > 2.0) {
            double nx = (doorX - farX) / fullDist, ny = (doorY - farY) / fullDist;
            double dist = fullDist - 1.0;
            renderer.setShadowFigure(farX + nx, farY + ny, nx * 2.0, ny * 2.0, dist);
            pendingFreezeOnSight    = (float)(dist / 2.0);
            pendingFreezeHallwayIdx = i;
        }
    }

    // ── Demo scene (title screen background) ─────────────────────────────────

    private void initDemoScene() {
        MapGenerator.GeneratedMap gen = MapGenerator.generate(new java.util.Random(7L));
        demoMap         = new GameMap(gen.grid);
        demoDoorManager = new DoorManager(demoMap);
        demoPlayer      = new Player(gen.spawnX + 1.5, gen.spawnY + 1.5);
        renderer.setWorldObjects(gen.lights, gen.tables, gen.cabinetBoxes);
        for (int i = 0; i < Math.min(4, gen.lights.length); i++)
            renderer.setRoomFlickerIntense(i, true);
    }

    // ── Title screen UI ───────────────────────────────────────────────────────

    private void buildTitleUI() {
        titleStage = new Stage(new ScreenViewport());

        Label title   = new Label("SECRET CHRONICLES", skin);
        Label play    = new Label("[ Play ]", skin);
        Label options = new Label("[ Options ]", skin);
        Label quit    = new Label("[ Quit ]", skin);

        title.setFontScale(2.2f);
        play.setFontScale(2.0f);
        options.setFontScale(2.0f);
        quit.setFontScale(2.0f);

        play.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { startGame(); }
        });
        options.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                Gdx.input.setInputProcessor(settingsStage);
            }
        });
        quit.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { Gdx.app.exit(); }
        });

        Table panel = new Table();
        panel.setBackground(new TextureRegionDrawable(skin.get("panel-bg", Texture.class)));
        panel.pad(64);
        panel.add(title).padBottom(80).width(360).left().row();
        panel.add(play).padBottom(32).width(360).left().row();
        panel.add(options).padBottom(32).width(360).left().row();
        panel.add(quit).width(360).left();

        Table root = new Table();
        root.setFillParent(true);
        root.left().padLeft(60);
        root.add(panel).fillY().expandY();
        titleStage.addActor(root);
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
        minimapToggleLabel = new Label("Minimap: ON", skin);
        minimapToggleLabel.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                boolean nowOn = !renderer.isShowMinimap();
                renderer.setShowMinimap(nowOn);
                minimapToggleLabel.setText("Minimap: " + (nowOn ? "ON" : "OFF"));
            }
        });
        Label closeX = new Label("  X  ", skin);
        closeX.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (onTitleScreen) {
                    Gdx.input.setInputProcessor(titleStage);
                } else {
                    Gdx.input.setCursorCatched(true);
                }
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
        table.add(infoToggleLabel).left().colspan(2).row();
        table.add(minimapToggleLabel).left().padTop(8).colspan(2);
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
