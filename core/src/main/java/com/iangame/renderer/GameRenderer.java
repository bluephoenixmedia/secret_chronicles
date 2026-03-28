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

    /** Call this whenever the window is resized (including entering fullscreen). */
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Renders one full frame: raycasted scene + optional minimap.
     *
     * @param player  current player state
     * @param map     world map
     */
    public void render(Player player, GameMap map) {
        // 1. Raycast into CPU buffer.
        rayCaster.render(player, map, pixels);

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
        if (SHOW_MINIMAP) renderMinimap(player, map);
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
    private void renderMinimap(Player player, GameMap map) {
        shapes.setProjectionMatrix(camera.combined);
        int[][] grid = map.getGrid();
        int offX = MINIMAP_PAD;
        int offY = MINIMAP_PAD;

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        for (int row = 0; row < map.height; row++) {
            for (int col = 0; col < map.width; col++) {
                if (grid[row][col] > 0) {
                    shapes.setColor(0.6f, 0.6f, 0.6f, 0.85f);
                } else {
                    shapes.setColor(0.1f, 0.1f, 0.1f, 0.7f);
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
