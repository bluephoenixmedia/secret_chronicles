package com.iangame.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
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

    private static final boolean SHOW_MINIMAP  = true;
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

    // ── Constructor ───────────────────────────────────────────────────────────

    public GameRenderer() {
        pixels    = new int[RENDER_WIDTH * RENDER_HEIGHT];
        rayCaster = new RayCaster(RENDER_WIDTH, RENDER_HEIGHT);
        pixmap    = new Pixmap(RENDER_WIDTH, RENDER_HEIGHT, Pixmap.Format.RGBA8888);
        texture   = new Texture(pixmap);
        batch     = new SpriteBatch();
        shapes    = new ShapeRenderer();
        camera    = new OrthographicCamera();
        viewport  = new FitViewport(RENDER_WIDTH, RENDER_HEIGHT, camera);
    }

    /** Toggles the player's flashlight on or off. */
    public void setFlashlight(boolean on) { rayCaster.setFlashlight(on); }

    /** Forwards the current battery level (0..1) to the raycaster. */
    public void setBattery(float battery) { rayCaster.setBattery(battery); }

    /**
     * Draws a battery meter in the bottom-right corner.
     * Only call this when the flashlight is on.
     */
    public void drawBatteryMeter(float battery) {
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
    }

    /** Call this whenever the window is resized (including entering fullscreen). */
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    /** Advances light flicker animations. Call once per frame before {@link #render}. */
    public void update(float dt) {
        rayCaster.update(dt);
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
        if (SHOW_MINIMAP) renderMinimap(player, map, doors);
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
        int offX = MINIMAP_PAD;
        int offY = MINIMAP_PAD;

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        for (int row = 0; row < map.height; row++) {
            for (int col = 0; col < map.width; col++) {
                int tile = grid[row][col];
                if (tile == 7) {
                    // Door: amber when closed, green when open
                    float open = (doors != null) ? doors.getOpenAmount(col, row) : 0f;
                    shapes.setColor(1f - open * 0.6f, 0.55f + open * 0.35f, 0.0f, 0.95f);
                } else if (tile == 8) {
                    shapes.setColor(0.12f, 0.15f, 0.45f, 0.95f); // window: night sky blue
                } else if (tile == 5) {
                    shapes.setColor(0.4f, 0.25f, 0.1f, 0.85f);  // dark wood frames
                } else if (tile > 0) {
                    shapes.setColor(0.55f, 0.55f, 0.55f, 0.85f);
                } else {
                    shapes.setColor(0.08f, 0.08f, 0.08f, 0.7f);
                }
                shapes.rect(
                    offX + col * MINIMAP_TILE,
                    offY + (map.height - 1 - row) * MINIMAP_TILE,
                    MINIMAP_TILE - 1,
                    MINIMAP_TILE - 1
                );
            }
        }

        // Player dot.
        shapes.setColor(1f, 0.9f, 0f, 1f);
        float px = offX + (float) player.x * MINIMAP_TILE;
        float py = offY + (map.height - 1 - (float) player.y) * MINIMAP_TILE;
        shapes.circle(px, py, MINIMAP_TILE * 0.6f);

        shapes.end();
    }

    @Override
    public void dispose() {
        pixmap.dispose();
        texture.dispose();
        batch.dispose();
        shapes.dispose();
    }
}
