package com.iangame.engine;

import com.iangame.player.Player;
import com.iangame.world.GameMap;

/**
 * Core raycasting engine — DDA wall casting + floor/ceiling projection.
 *
 * <p>Walls are texture-mapped using a {@link TextureAtlas}; floor and ceiling
 * are rendered with a per-pixel perspective-correct projection that samples the
 * same atlas.  All output is written into an RGBA8888 {@code int[]} pixel buffer.
 */
public class RayCaster {

    private final int screenW;
    private final int screenH;
    private final int halfH;

    /** Darkening factor applied to E/W (side-1) wall faces. */
    private static final float SIDE_SHADE = 0.6f;

    /** Maximum brightness fade for distant floor/ceiling (0 = no fade). */
    private static final float FLOOR_FADE = 0.72f;

    private final TextureAtlas atlas;

    public RayCaster(int screenWidth, int screenHeight) {
        this.screenW = screenWidth;
        this.screenH = screenHeight;
        this.halfH   = screenHeight / 2;
        this.atlas   = new TextureAtlas();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void render(Player player, GameMap map, int[] pixels) {
        castFloorAndCeiling(player, pixels);
        for (int x = 0; x < screenW; x++) {
            castWallColumn(x, player, map, pixels);
        }
    }

    // ── Floor / ceiling ───────────────────────────────────────────────────────

    /**
     * Fills every pixel in the top and bottom halves using perspective-correct
     * floor projection.  Each row maps to a specific world distance; texture
     * coordinates are stepped across all columns in that row.
     */
    private void castFloorAndCeiling(Player player, int[] pixels) {
        // Left-edge and right-edge ray directions
        double rdx0 = player.dirX - player.planeX;
        double rdy0 = player.dirY - player.planeY;
        double rdx1 = player.dirX + player.planeX;
        double rdy1 = player.dirY + player.planeY;

        int[]  floorTex = atlas.floor;
        int[]  ceilTex  = atlas.ceiling;
        int    sz       = TextureAtlas.TEX_SIZE;
        int    mask     = TextureAtlas.TEX_MASK;

        for (int y = halfH; y < screenH; y++) {
            int p = y - halfH;            // rows below the horizon
            if (p == 0) p = 1;            // avoid division by zero at horizon

            double rowDist   = 0.5 * screenH / p;
            double stepX     = rowDist * (rdx1 - rdx0) / screenW;
            double stepY     = rowDist * (rdy1 - rdy0) / screenW;
            double worldX    = player.x + rowDist * rdx0;
            double worldY    = player.y + rowDist * rdy0;

            // Distance-based brightness: close rows bright, horizon dark
            float bright = Math.min(1f, (float) p * 2f / screenH + 0.15f);

            int floorRow = y         * screenW;
            int ceilRow  = (screenH - y - 1) * screenW;

            for (int x = 0; x < screenW; x++) {
                int tx = (int)(worldX * sz) & mask;
                int ty = (int)(worldY * sz) & mask;
                int idx = ty * sz + tx;

                pixels[floorRow + x] = dim(floorTex[idx], bright);
                pixels[ceilRow  + x] = dim(ceilTex [idx], bright);

                worldX += stepX;
                worldY += stepY;
            }
        }
    }

    // ── Wall column ───────────────────────────────────────────────────────────

    private void castWallColumn(int x, Player player, GameMap map, int[] pixels) {

        // Camera-space X: maps column [0, screenW) → [-1, +1]
        double cameraX = 2.0 * x / screenW - 1.0;

        double rayDirX = player.dirX + player.planeX * cameraX;
        double rayDirY = player.dirY + player.planeY * cameraX;

        int mapX = (int) player.x;
        int mapY = (int) player.y;

        double deltaDistX = (rayDirX == 0.0) ? Double.MAX_VALUE : Math.abs(1.0 / rayDirX);
        double deltaDistY = (rayDirY == 0.0) ? Double.MAX_VALUE : Math.abs(1.0 / rayDirY);

        int    stepX, stepY;
        double sideDistX, sideDistY;

        if (rayDirX < 0) { stepX = -1; sideDistX = (player.x - mapX) * deltaDistX; }
        else             { stepX =  1; sideDistX = (mapX + 1.0 - player.x) * deltaDistX; }
        if (rayDirY < 0) { stepY = -1; sideDistY = (player.y - mapY) * deltaDistY; }
        else             { stepY =  1; sideDistY = (mapY + 1.0 - player.y) * deltaDistY; }

        // DDA
        int side = 0, wallType = 0;
        while (true) {
            if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
            else                       { sideDistY += deltaDistY; mapY += stepY; side = 1; }
            wallType = map.getCell(mapX, mapY);
            if (wallType > 0) break;
            if (wallType < 0) return; // out of bounds
        }

        double perpWallDist = (side == 0) ? sideDistX - deltaDistX : sideDistY - deltaDistY;
        if (perpWallDist < 0.0001) perpWallDist = 0.0001;

        int lineHeight = (int) (screenH / perpWallDist);
        int drawStart  = Math.max(0,       halfH - lineHeight / 2);
        int drawEnd    = Math.min(screenH, halfH + lineHeight / 2);

        // Texture X coordinate: where on the wall face the ray hit (0–1 → 0–63)
        double wallX = (side == 0)
            ? player.y + perpWallDist * rayDirY
            : player.x + perpWallDist * rayDirX;
        wallX -= Math.floor(wallX);

        int texX = (int)(wallX * TextureAtlas.TEX_SIZE);
        if (side == 0 && rayDirX > 0) texX = TextureAtlas.TEX_SIZE - texX - 1;
        if (side == 1 && rayDirY < 0) texX = TextureAtlas.TEX_SIZE - texX - 1;
        texX = texX & TextureAtlas.TEX_MASK; // safety clamp

        int[]  tex     = atlas.getWall(wallType);
        int    sz      = TextureAtlas.TEX_SIZE;
        double texStep = (double) sz / lineHeight;
        // Starting texY accounts for wall being clipped at the top of the screen
        double texPos  = (drawStart - halfH + lineHeight / 2.0) * texStep;

        for (int y = drawStart; y < drawEnd; y++) {
            int texY  = (int) texPos & TextureAtlas.TEX_MASK;
            texPos   += texStep;
            int color = tex[texY * sz + texX];
            if (side == 1) color = shade(color, SIDE_SHADE);
            pixels[y * screenW + x] = color;
        }
    }

    // ── Colour helpers ────────────────────────────────────────────────────────

    /** Multiplies each RGB channel by {@code factor} (0–1). */
    private static int shade(int rgba, float factor) {
        int r = (int)(((rgba >> 24) & 0xFF) * factor);
        int g = (int)(((rgba >> 16) & 0xFF) * factor);
        int b = (int)(((rgba >>  8) & 0xFF) * factor);
        return (r << 24) | (g << 16) | (b << 8) | 0xFF;
    }

    /** Scales brightness for floor/ceiling distance fade. */
    private static int dim(int rgba, float bright) {
        float f = 1f - (1f - bright) * FLOOR_FADE;
        return shade(rgba, f);
    }
}
