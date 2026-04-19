package com.iangame.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.iangame.engine.DoorManager;
import com.iangame.engine.RayCaster;
import com.iangame.player.Player;
import com.iangame.world.GameMap;

/**
 * Manages the off-screen pixel buffer and uploads it to the GPU each frame.
 *
 * <h2>Rendering pipeline</h2>
 * <ol>
 *   <li>{@link RayCaster} fills an {@code int[]} pixel buffer (CPU, RGBA8888).</li>
 *   <li>The buffer is copied into a {@link Pixmap} and uploaded to a {@link Texture}.</li>
 *   <li>The texture is stretched to fill the screen via {@link SpriteBatch}.</li>
 *   <li>An optional minimap overlay is drawn on top with {@link ShapeRenderer}.</li>
 * </ol>
 *
 * <p>The internal render resolution is intentionally lower than the window
 * ({@value RENDER_WIDTH}×{@value RENDER_HEIGHT}) to keep the raycaster fast.
 */
public class GameRenderer implements Disposable {

    // ── Internal render resolution ────────────────────────────────────────────

    public static final int RENDER_WIDTH  = 640;
    public static final int RENDER_HEIGHT = 360;

    // ── Minimap config ────────────────────────────────────────────────────────

    private boolean showMinimap = true;
    public void setShowMinimap(boolean show) { this.showMinimap = show; }
    public boolean isShowMinimap() { return showMinimap; }
    private static final int     MINIMAP_TILE  = 3;    // pixels per map tile
    private static final int     MINIMAP_PAD   = 8;    // screen-edge padding

    // ── GPU resources ─────────────────────────────────────────────────────────

    private final Pixmap        pixmap;
    private final Texture       texture;
    private final SpriteBatch   batch;
    private final ShapeRenderer shapes;

    // ── Viewport (maintains aspect ratio on resize / fullscreen) ─────────────

    private final OrthographicCamera camera;
    private final FitViewport        viewport;

    // ── CPU pixel buffer (RGBA8888 packed ints) ───────────────────────────────

    private final int[]       pixels;
    private final RayCaster   rayCaster;
    private final BitmapFont  infoFont;
    private final GlyphLayout infoLayout;

    private static final float FADE_DURATION = 1.5f;
    private float fadeAlpha = 1.0f;

    // ── Static / game-over overlay ────────────────────────────────────────────

    private Pixmap  staticPixmap;
    private Texture staticTexture;
    private static final int STATIC_W = 160;
    private static final int STATIC_H = 90;
    private final java.util.Random staticRng = new java.util.Random();

    /** Keys of locked doors (col*10000+row) shown in red on the minimap. */
    private java.util.Set<Integer> lockedDoorKeys = new java.util.HashSet<>();

    public void setLockedDoors(java.util.Set<Integer> keys) { this.lockedDoorKeys = keys; }

    // ── Constructor ───────────────────────────────────────────────────────────

    public GameRenderer() {
        pixels     = new int[RENDER_WIDTH * RENDER_HEIGHT];
        rayCaster  = new RayCaster(RENDER_WIDTH, RENDER_HEIGHT);
        pixmap     = new Pixmap(RENDER_WIDTH, RENDER_HEIGHT, Pixmap.Format.RGBA8888);
        texture    = new Texture(pixmap);
        batch      = new SpriteBatch();
        shapes     = new ShapeRenderer();
        camera     = new OrthographicCamera();
        viewport   = new FitViewport(RENDER_WIDTH, RENDER_HEIGHT, camera);
        infoFont   = new BitmapFont();
        infoLayout = new GlyphLayout();
    }

    /** Toggles the player's flashlight on or off. */
    public void setFlashlight(boolean on) { rayCaster.setFlashlight(on); }

    /** Forwards the current battery level (0..1) to the raycaster. */
    public void setBattery(float battery) { rayCaster.setBattery(battery); }

    /** Enables or disables aggressive flickering on the given room light index (0-8). */
    public void setRoomFlickerIntense(int lightIndex, boolean on) {
        rayCaster.setIntenseFlicker(lightIndex, on);
    }

    /** Turns all room lights off (true) or back on (false). */
    public void setLightsOut(boolean out) { rayCaster.setLightsOut(out); }

    /** Loads procedurally generated world objects into the raycaster. */
    public void setWorldObjects(float[][] lights, float[][] tables, float[][] cabinetBoxes) {
        rayCaster.setWorldObjects(lights, tables, cabinetBoxes);
    }

    /** Sets blood splatter positions rendered on the floor. */
    public void setBloodSpots(float[][] spots) { rayCaster.setBloodSpots(spots); }

    /**
     * Draws a battery meter in the bottom-right corner.
     * Only call this when the flashlight is on.
     *
     * @param showLabel  when true, draws "Flashlight battery" to the left of the bar
     */
    public void drawBatteryMeter(float battery, boolean showLabel) {
        final int BAR_W = 60, BAR_H = 8, PAD = 10, NUB_W = 4, NUB_H = 4;
        float x = RENDER_WIDTH  - PAD - BAR_W;
        float y = PAD;

        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Background
        shapes.setColor(0.1f, 0.1f, 0.1f, 0.85f);
        shapes.rect(x, y, BAR_W, BAR_H);
        // Battery nub
        shapes.setColor(0.35f, 0.35f, 0.35f, 0.9f);
        shapes.rect(x + BAR_W, y + (BAR_H - NUB_H) / 2f, NUB_W, NUB_H);

        // Fill colour: green → yellow → red
        if (battery > 0.5f)      shapes.setColor(0.2f, 0.85f, 0.2f,  1f);
        else if (battery > 0.2f) shapes.setColor(0.95f, 0.80f, 0.1f, 1f);
        else                     shapes.setColor(0.95f, 0.18f, 0.18f, 1f);
        shapes.rect(x, y, BAR_W * battery, BAR_H);

        shapes.end();

        // Outline drawn separately (Line type)
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.7f, 0.7f, 0.7f, 1f);
        shapes.rect(x, y, BAR_W, BAR_H);
        shapes.end();

        if (showLabel) {
            final String label = "Flashlight battery";
            final int    GAP   = 6;
            infoLayout.setText(infoFont, label);
            float labelX = x - GAP - infoLayout.width;
            float labelY = y + BAR_H / 2f + infoFont.getCapHeight() / 2f;
            batch.setProjectionMatrix(camera.combined);
            batch.begin();
            infoFont.draw(batch, infoLayout, labelX, labelY);
            batch.end();
        }
    }

    /**
     * Draws one or more overlay texts stacked in the top-right corner.
     * Each entry is offset downward by the font line height + a small gap,
     * so multiple simultaneous labels never overlap.
     */
    /**
     * Draws one or more overlay texts stacked in the top-right corner.
     * Wrap a text in ** ** (e.g. {@code "**Hello**"}) to render it bold
     * (simulated via a 1-pixel offset second draw pass).
     */
    public void drawOverlayTexts(String... texts) {
        if (texts.length == 0) return;
        final int   PAD       = 10;
        final float LINE_GAP  = 4f;
        final float BOLD_PAD  = 6f;  // extra space inserted before/after a bold entry
        float y = RENDER_HEIGHT - PAD;

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        for (int i = 0; i < texts.length; i++) {
            boolean bold = texts[i].startsWith("**") && texts[i].endsWith("**");
            // Push down before a bold entry if preceded by a non-bold one
            if (bold && i > 0 && !(texts[i-1].startsWith("**") && texts[i-1].endsWith("**")))
                y -= BOLD_PAD;
            String display = bold ? texts[i].substring(2, texts[i].length() - 2) : texts[i];
            infoLayout.setText(infoFont, display);
            float x = RENDER_WIDTH - PAD - infoLayout.width;
            infoFont.draw(batch, infoLayout, x, y);
            if (bold) infoFont.draw(batch, infoLayout, x + 1f, y);  // second pass = pseudo-bold
            y -= infoFont.getLineHeight() + LINE_GAP;
            // Push down after a bold entry if followed by a non-bold one
            if (bold && i < texts.length - 1 && !(texts[i+1].startsWith("**") && texts[i+1].endsWith("**")))
                y -= BOLD_PAD;
        }
        batch.end();
    }

    /** Call this whenever the window is resized (including entering fullscreen). */
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    /** Advances light flicker animations and the spawn fade. Call once per frame before {@link #render}. */
    public void update(float dt) {
        rayCaster.update(dt);
        if (fadeAlpha > 0f) fadeAlpha = Math.max(0f, fadeAlpha - dt / FADE_DURATION);
    }

    /**
     * Draws a growing TV-static effect over the screen.
     * {@code level} ranges 0 (invisible) to 1 (fully covering).
     * When {@code black} is true the noise pixels are black instead of grey.
     */
    public void drawStaticOverlay(float level, boolean black) {
        if (staticPixmap == null) {
            staticPixmap  = new Pixmap(STATIC_W, STATIC_H, Pixmap.Format.RGBA8888);
            staticTexture = new Texture(STATIC_W, STATIC_H, Pixmap.Format.RGBA8888);
        }

        // Tint underlay
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (black) shapes.setColor(0f, 0f, 0f, level * 0.07f);
        else       shapes.setColor(0.45f, 0.45f, 0.45f, level * 0.07f);
        shapes.rect(0, 0, RENDER_WIDTH, RENDER_HEIGHT);
        shapes.end();

        // Noise pixels — at most ~25% coverage so the scene stays visible underneath
        staticPixmap.setBlending(Pixmap.Blending.None);
        int alpha = black ? 255 : Math.min(60, (int)(20 + 40 * level));
        for (int y = 0; y < STATIC_H; y++) {
            for (int x = 0; x < STATIC_W; x++) {
                if (staticRng.nextFloat() < level * 0.25f) {
                    int g = black ? 0 : 20 + staticRng.nextInt(220);
                    staticPixmap.drawPixel(x, y, (g << 24) | (g << 16) | (g << 8) | alpha);
                } else {
                    staticPixmap.drawPixel(x, y, 0x00000000);
                }
            }
        }
        staticTexture.draw(staticPixmap, 0, 0);

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.draw(staticTexture, 0, 0, RENDER_WIDTH, RENDER_HEIGHT);
        batch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Resets the spawn fade so it plays again (used on retry). */
    public void resetFade() { fadeAlpha = 1.0f; }

    /**
     * Draws a centred inventory panel listing how many keys the player holds.
     * Call each frame while the inventory should be visible.
     */
    public void drawInventory(int keys) {
        final int PAD = 14, PANEL_W = 160, PANEL_H = 56;
        float px = (RENDER_WIDTH  - PANEL_W) / 2f;
        float py = (RENDER_HEIGHT - PANEL_H) / 2f;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Background panel
        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.04f, 0.04f, 0.04f, 0.88f);
        shapes.rect(px, py, PANEL_W, PANEL_H);
        shapes.end();

        // Border
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.6f, 0.6f, 0.6f, 0.9f);
        shapes.rect(px, py, PANEL_W, PANEL_H);
        shapes.end();

        // Text
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        infoLayout.setText(infoFont, "INVENTORY");
        infoFont.draw(batch, infoLayout,
                px + (PANEL_W - infoLayout.width) / 2f,
                py + PANEL_H - PAD);

        String keyLine = keys > 0 ? "Key  x" + keys : "Key  x0  (empty)";
        infoLayout.setText(infoFont, keyLine);
        infoFont.draw(batch, infoLayout,
                px + PAD,
                py + PANEL_H - PAD - infoFont.getLineHeight() - 6f);

        batch.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Draws a full-screen red tint at the given opacity (0 = none, 1 = solid red). */
    /**
     * Draws a simple cabinet inventory panel in the centre of the screen.
     *
     * @param hasKey   true if this cabinet still holds an un-looted key
     */
    public void drawCabinetUI(boolean hasKey) {
        final int PW = 260, PH = 160;
        final int PX = (RENDER_WIDTH  - PW) / 2;
        final int PY = (RENDER_HEIGHT - PH) / 2;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // dark background
        shapes.setColor(0f, 0f, 0f, 0.82f);
        shapes.rect(PX, PY, PW, PH);
        shapes.end();

        // border
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.7f, 0.7f, 0.7f, 1f);
        shapes.rect(PX, PY, PW, PH);
        shapes.end();

        if (hasKey) {
            // key icon — a small filled yellow rect to represent the key
            final int KW = 40, KH = 20;
            final int KX = PX + (PW - KW) / 2;
            final int KY = PY + PH / 2;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 0.85f, 0f, 1f);
            shapes.rect(KX, KY, KW, KH);
            // key bow (circle-ish square)
            shapes.rect(KX - 14, KY - 4, 18, 28);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(0.6f, 0.5f, 0f, 1f);
            shapes.rect(KX - 14, KY - 4, 18, 28);
            shapes.rect(KX, KY, KW, KH);
            shapes.end();
        }

        Gdx.gl.glDisable(GL20.GL_BLEND);

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        // title
        infoLayout.setText(infoFont, "Cabinet");
        infoFont.draw(batch, infoLayout, PX + (PW - infoLayout.width) / 2f, PY + PH - 10f);
        // item line
        String itemText = hasKey ? "Key" : "(empty)";
        infoLayout.setText(infoFont, itemText);
        infoFont.draw(batch, infoLayout, PX + (PW - infoLayout.width) / 2f, PY + PH / 2f - 28f);
        // instruction
        String hint = hasKey ? "Left click to take key" : "Press E to close";
        infoLayout.setText(infoFont, hint);
        infoFont.draw(batch, infoLayout, PX + (PW - infoLayout.width) / 2f, PY + 18f);
        batch.end();
    }

    /** Spawns the 3-D shadow figure in the world at (x,y) moving at (vx,vy) tiles/s. */
    public void setShadowFigure(double x, double y, double vx, double vy, double maxDist) {
        rayCaster.setShadowFigure(x, y, vx, vy, maxDist);
    }

    public boolean isShadowFigureActive() { return rayCaster.isShadowFigureActive(); }

    public void drawRedTint(float level) {
        if (level <= 0f) return;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 0f, 0f, level);
        shapes.rect(0, 0, RENDER_WIDTH, RENDER_HEIGHT);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

/**
     * Draws a full-screen black overlay that fades out on spawn.
     * A no-op once the fade has finished. Call last each frame so it covers all other UI.
     */
    public void drawFadeOverlay() {
        if (fadeAlpha <= 0f) return;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, fadeAlpha);
        shapes.rect(0, 0, RENDER_WIDTH, RENDER_HEIGHT);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Renders one full frame: raycasted scene + optional minimap.
     *
     * @param player  current player state
     * @param map     world map
     * @param doors   door state manager (may be null)
     */
    public void render(Player player, GameMap map, DoorManager doors) {
        // 1. Raycast into CPU buffer.
        rayCaster.render(player, map, doors, pixels);

        // 2. Upload pixels to Pixmap, then to Texture.
        uploadPixels();

        // 3. Clear full framebuffer (fills letterbox/pillarbox bars with black),
        //    then restrict rendering to the viewport rectangle.
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        viewport.apply();
        camera.update();

        // 4. Draw the scene texture at the internal resolution; the camera/viewport
        //    maps this to the correctly scaled area on screen.
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.draw(texture, 0, 0, RENDER_WIDTH, RENDER_HEIGHT);
        batch.end();

        // 5. Minimap overlay.
        if (showMinimap) renderMinimap(player, map, doors);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Copies the int[] pixel buffer into the Pixmap and refreshes the Texture. */
    private void uploadPixels() {
        java.nio.ByteBuffer buf = pixmap.getPixels();
        buf.rewind();
        for (int packed : pixels) {
            buf.put((byte) ((packed >> 24) & 0xFF));  // R
            buf.put((byte) ((packed >> 16) & 0xFF));  // G
            buf.put((byte) ((packed >>  8) & 0xFF));  // B
            buf.put((byte)  (packed        & 0xFF));  // A
        }
        buf.rewind();
        texture.draw(pixmap, 0, 0);
    }

    /** Draws a top-down minimap in the bottom-left corner of the viewport. */
    private void renderMinimap(Player player, GameMap map, DoorManager doors) {
        shapes.setProjectionMatrix(camera.combined);
        int[][] grid = map.getGrid();

        // Fixed-size scrolling window centred on the player.
        final int HALF = 15;   // tiles visible on each side of the player
        final int VIEW = HALF * 2;

        int pCol = (int) player.x;
        int pRow = (int) player.y;

        // Ideal window: player at the centre.
        int minCol = pCol - HALF;
        int minRow = pRow - HALF;

        // Clamp so the window never goes outside the map.
        minCol = Math.max(0, Math.min(map.width  - VIEW, minCol));
        minRow = Math.max(0, Math.min(map.height - VIEW, minRow));
        int maxCol = minCol + VIEW;
        int maxRow = minRow + VIEW;

        int offX = MINIMAP_PAD;
        int offY = MINIMAP_PAD;

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        for (int row = minRow; row < maxRow; row++) {
            for (int col = minCol; col < maxCol; col++) {
                int tile = grid[row][col];
                if (tile == 7) {
                    if (doors != null && doors.isLockedDoor(col, row)) {
                        shapes.setColor(0.85f, 0.1f, 0.1f, 1f);
                    } else {
                        float open = (doors != null) ? doors.getOpenAmount(col, row) : 0f;
                        shapes.setColor(1f - open * 0.6f, 0.55f + open * 0.35f, 0.0f, 0.95f);
                    }
                } else if (tile == 8) {
                    shapes.setColor(0.12f, 0.15f, 0.45f, 0.95f);
                } else if (tile == 5) {
                    shapes.setColor(0.4f, 0.25f, 0.1f, 0.85f);
                } else if (tile > 0) {
                    shapes.setColor(0.55f, 0.55f, 0.55f, 0.85f);
                } else {
                    shapes.setColor(0.08f, 0.08f, 0.08f, 0.7f);
                }
                shapes.rect(
                    offX + (col - minCol) * MINIMAP_TILE,
                    offY + (maxRow - 1 - row) * MINIMAP_TILE,
                    MINIMAP_TILE - 1,
                    MINIMAP_TILE - 1
                );
            }
        }

        // Player dot — always inside the window (window is clamped to map).
        float dotR = MINIMAP_TILE * 0.6f;
        float px = offX + ((float) player.x - minCol) * MINIMAP_TILE;
        float py = offY + (maxRow - (float) player.y) * MINIMAP_TILE;
        shapes.setColor(1f, 0.9f, 0f, 1f);
        shapes.circle(px, py, dotR);

        shapes.end();
    }

    @Override
    public void dispose() {
        pixmap.dispose();
        texture.dispose();
        batch.dispose();
        shapes.dispose();
        infoFont.dispose();
        if (staticPixmap  != null) staticPixmap.dispose();
        if (staticTexture != null) staticTexture.dispose();
    }
}
