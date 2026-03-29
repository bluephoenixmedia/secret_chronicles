package com.iangame.engine;

import com.iangame.player.Player;
import com.iangame.world.GameMap;

/**
 * Core raycasting engine — DDA wall casting + floor/ceiling projection.
 *
 * <p>Lighting model: very dark ambient ({@value AMBIENT}) plus nine warm
 * point lights, one centred in each room.  Distance falloff is quadratic
 * (smooth zero at the light's radius).  The warm colour bias (R > G > B)
 * gives an incandescent / candlelight feel in lit areas.
 */
public class RayCaster {

    private final int screenW;
    private final int screenH;
    private final int halfH;

    /** Directional shading factor for E/W (side-1) wall faces. */
    private static final float SIDE_SHADE = 0.6f;

    /** Minimum scene brightness — everything is very dark without a nearby light. */
    private static final float AMBIENT = 0.04f;

    // ── Point lights (x, y, intensity, radius) ────────────────────────────────
    //  One light centred in each of the nine rooms.
    //  Corridors have no lights so they stay nearly pitch-black.

    private static final float[][] LIGHTS = {
        //  x       y      intensity  radius
        {  4.5f,  4.5f,   1.0f,  5.0f },   // room (0,0) grey
        { 18.5f,  4.5f,   1.0f,  6.0f },   // room (0,1) terracotta
        { 31.0f,  4.5f,   1.0f,  4.5f },   // room (0,2) moss
        {  4.5f, 17.5f,   1.0f,  5.5f },   // room (1,0) white
        { 18.5f, 17.5f,   1.0f,  6.5f },   // room (1,1) terracotta
        { 31.0f, 17.5f,   1.0f,  4.5f },   // room (1,2) grey
        {  4.5f, 30.0f,   1.0f,  5.0f },   // room (2,0) moss
        { 18.5f, 30.0f,   1.0f,  6.0f },   // room (2,1) white
        { 31.0f, 30.0f,   1.0f,  4.5f },   // room (2,2) terracotta
    };

    /** Pre-computed radius² so the inner loop avoids sqrt. */
    private static final float[] LIGHT_RAD_SQ;
    static {
        LIGHT_RAD_SQ = new float[LIGHTS.length];
        for (int i = 0; i < LIGHTS.length; i++) {
            float r = LIGHTS[i][3];
            LIGHT_RAD_SQ[i] = r * r;
        }
    }

    // ── Flicker state (one entry per light) ───────────────────────────────────

    /** Average number of flicker events triggered per second, per light. */
    private static final float FLICKER_CHANCE = 0.4f;
    /** Speed at which the brightness lerps toward its target. */
    private static final float FLICKER_LERP   = 16f;

    private final float[] flickerMult;    // current brightness multiplier in [0,1]
    private final float[] flickerTarget;  // lerp target
    private final float[] flickerTimer;   // seconds remaining in current dip
    private final java.util.Random rng    = new java.util.Random();

    // ── Sprite / table positions (world-space x, y) ───────────────────────────
    //  2–4 tables scattered through each of the nine rooms, clear of doorways.

    public static final float[][] TABLES = {
        // Room (0,0) grey      cols 2-7, rows 2-7
        { 3.5f,  2.8f }, { 6.2f, 5.5f }, { 2.8f, 6.2f },
        // Room (0,1) terracotta cols 14-23, rows 2-7
        { 15.5f, 3.0f }, { 21.5f, 3.0f }, { 18.5f, 6.2f },
        // Room (0,2) moss       cols 29-33, rows 2-7
        { 30.5f, 3.0f }, { 31.5f, 6.0f },
        // Room (1,0) white      cols 2-7, rows 14-21
        { 3.0f, 15.0f }, { 6.0f, 20.0f }, { 4.5f, 18.0f },
        // Room (1,1) terracotta cols 14-23, rows 14-21
        { 15.5f, 15.0f }, { 21.5f, 15.0f }, { 16.0f, 20.5f }, { 22.0f, 20.5f },
        // Room (1,2) grey       cols 29-33, rows 14-21
        { 30.0f, 15.5f }, { 31.5f, 19.5f },
        // Room (2,0) moss       cols 2-7, rows 27-33
        { 3.0f, 28.0f }, { 6.0f, 31.5f },
        // Room (2,1) white      cols 14-23, rows 27-33
        { 16.0f, 28.5f }, { 21.0f, 28.5f }, { 18.5f, 32.0f },
        // Room (2,2) terracotta cols 29-33, rows 27-33
        { 30.5f, 28.5f }, { 31.0f, 32.5f },
    };

    /** Per-column depth buffer populated during wall casting. */
    private final double[] zBuffer;

    private final TextureAtlas atlas;

    public RayCaster(int screenWidth, int screenHeight) {
        this.screenW = screenWidth;
        this.screenH = screenHeight;
        this.halfH   = screenHeight / 2;
        this.atlas   = new TextureAtlas();
        this.zBuffer = new double[screenWidth];

        int n        = LIGHTS.length;
        flickerMult  = new float[n];
        flickerTarget = new float[n];
        flickerTimer  = new float[n];
        java.util.Arrays.fill(flickerMult,   1f);
        java.util.Arrays.fill(flickerTarget, 1f);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Advances flicker animations.  Call once per frame before {@link #render}.
     */
    public void update(float dt) {
        for (int i = 0; i < LIGHTS.length; i++) {
            flickerTimer[i] -= dt;

            if (flickerTimer[i] <= 0f) {
                if (rng.nextFloat() < FLICKER_CHANCE * dt) {
                    // Trigger a flicker: snap brightness down to a random low value
                    flickerTarget[i] = 0.10f + rng.nextFloat() * 0.55f;
                    flickerTimer[i]  = 0.03f + rng.nextFloat() * 0.11f;
                } else {
                    // Idle: tiny random breathing (±3 % around 1.0)
                    flickerTarget[i] = 0.97f + rng.nextFloat() * 0.06f;
                }
            }

            // Lerp current multiplier toward target
            flickerMult[i] += (flickerTarget[i] - flickerMult[i])
                              * Math.min(1f, FLICKER_LERP * dt);
            if (flickerMult[i] < 0.05f) flickerMult[i] = 0.05f;
            if (flickerMult[i] > 1.00f) flickerMult[i] = 1.00f;
        }
    }

    public void render(Player player, GameMap map, DoorManager doors, int[] pixels) {
        java.util.Arrays.fill(zBuffer, Double.MAX_VALUE);
        castFloorAndCeiling(player, pixels);
        for (int x = 0; x < screenW; x++) {
            castWallColumn(x, player, map, doors, pixels);
        }
        renderSprites(player, pixels);
    }

    // ── Lighting ──────────────────────────────────────────────────────────────

    /**
     * Computes total brightness at a world-space position, including per-light
     * flicker multipliers.  Returns a value in [AMBIENT, 1.0].
     */
    private float computeLighting(double wx, double wy) {
        float total = AMBIENT;
        for (int i = 0; i < LIGHTS.length; i++) {
            double dx    = wx - LIGHTS[i][0];
            double dy    = wy - LIGHTS[i][1];
            double dist2 = dx * dx + dy * dy;
            if (dist2 < LIGHT_RAD_SQ[i]) {
                double t = 1.0 - dist2 / LIGHT_RAD_SQ[i];
                total   += (float)(LIGHTS[i][2] * flickerMult[i] * t * t);
            }
        }
        return Math.min(1f, total);
    }

    /**
     * Applies warm-tinted lighting to a colour:
     * Red channel least attenuated, blue most — gives an incandescent glow.
     */
    private static int applyLight(int rgba, float brightness) {
        int r = (int)(((rgba >> 24) & 0xFF) * Math.min(1f, brightness));
        int g = (int)(((rgba >> 16) & 0xFF) * Math.min(1f, brightness * 0.88f));
        int b = (int)(((rgba >>  8) & 0xFF) * Math.min(1f, brightness * 0.70f));
        return (r << 24) | (g << 16) | (b << 8) | 0xFF;
    }

    // ── Floor / ceiling ───────────────────────────────────────────────────────

    private void castFloorAndCeiling(Player player, int[] pixels) {
        double rdx0 = player.dirX - player.planeX;
        double rdy0 = player.dirY - player.planeY;
        double rdx1 = player.dirX + player.planeX;
        double rdy1 = player.dirY + player.planeY;

        int[] floorTex = atlas.floor;
        int[] ceilTex  = atlas.ceiling;
        int   sz       = TextureAtlas.TEX_SIZE;
        int   mask     = TextureAtlas.TEX_MASK;

        for (int y = halfH; y < screenH; y++) {
            int p = y - halfH;
            if (p == 0) p = 1;

            double rowDist = 0.5 * screenH / p;
            double stepX   = rowDist * (rdx1 - rdx0) / screenW;
            double stepY   = rowDist * (rdy1 - rdy0) / screenW;
            double worldX  = player.x + rowDist * rdx0;
            double worldY  = player.y + rowDist * rdy0;

            int floorRow = y                * screenW;
            int ceilRow  = (screenH - y - 1) * screenW;

            for (int x = 0; x < screenW; x++) {
                int tx  = (int)(worldX * sz) & mask;
                int ty  = (int)(worldY * sz) & mask;
                int idx = ty * sz + tx;

                float lit = computeLighting(worldX, worldY);

                pixels[floorRow + x] = applyLight(floorTex[idx], lit);
                // Ceilings are slightly darker — lights hang lower
                pixels[ceilRow  + x] = applyLight(ceilTex [idx], lit * 0.72f);

                worldX += stepX;
                worldY += stepY;
            }
        }
    }

    // ── Wall column ───────────────────────────────────────────────────────────

    private void castWallColumn(int x, Player player, GameMap map,
                                DoorManager doors, int[] pixels) {

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

        int     side = 0, wallType = 0;
        boolean hitDoor     = false;
        double  doorMidPerp = 0, doorFaceX = 0;

        while (true) {
            if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
            else                       { sideDistY += deltaDistY; mapY += stepY; side = 1; }

            wallType = map.getCell(mapX, mapY);
            if (wallType < 0) return;
            if (wallType == 0) continue;

            if (wallType == 7) {
                double perpAtWall = (side == 0) ? sideDistX - deltaDistX
                                                : sideDistY - deltaDistY;
                double halfStep   = 0.5 * (side == 0 ? deltaDistX : deltaDistY);
                double midPerp    = perpAtWall + halfStep;

                int midCellX = (int)(player.x + midPerp * rayDirX);
                int midCellY = (int)(player.y + midPerp * rayDirY);
                if (midCellX != mapX || midCellY != mapY) continue;

                double faceX = (side == 0) ? player.y + midPerp * rayDirY
                                           : player.x + midPerp * rayDirX;
                faceX -= Math.floor(faceX);

                float openAmt = (doors != null) ? doors.getOpenAmount(mapX, mapY) : 0f;
                if (faceX < openAmt) continue;

                hitDoor    = true;
                doorMidPerp = midPerp;
                doorFaceX   = faceX;
                break;
            }

            break;
        }

        double perpWallDist;
        double wallX;

        if (hitDoor) {
            perpWallDist = Math.max(0.0001, doorMidPerp);
            wallX        = doorFaceX;
        } else {
            perpWallDist = (side == 0) ? sideDistX - deltaDistX : sideDistY - deltaDistY;
            if (perpWallDist < 0.0001) perpWallDist = 0.0001;
            wallX = (side == 0) ? player.y + perpWallDist * rayDirY
                                : player.x + perpWallDist * rayDirX;
            wallX -= Math.floor(wallX);
        }

        // Record depth for sprite occlusion
        zBuffer[x] = perpWallDist;

        // World position of the hit — used for lighting
        double hitX   = player.x + perpWallDist * rayDirX;
        double hitY   = player.y + perpWallDist * rayDirY;
        float  wallLit = computeLighting(hitX, hitY);
        // E/W faces receive an extra directional shade
        if (side == 1 && !hitDoor) wallLit *= SIDE_SHADE;

        int lineHeight = (int) (screenH / perpWallDist);
        int drawStart  = Math.max(0,       halfH - lineHeight / 2);
        int drawEnd    = Math.min(screenH, halfH + lineHeight / 2);

        int texX;
        if (hitDoor) {
            texX = (int)(wallX * TextureAtlas.TEX_SIZE) & TextureAtlas.TEX_MASK;
        } else {
            texX = (int)(wallX * TextureAtlas.TEX_SIZE);
            if (side == 0 && rayDirX > 0) texX = TextureAtlas.TEX_SIZE - texX - 1;
            if (side == 1 && rayDirY < 0) texX = TextureAtlas.TEX_SIZE - texX - 1;
            texX = texX & TextureAtlas.TEX_MASK;
        }

        int[]  tex     = atlas.getWall(wallType);
        int    sz      = TextureAtlas.TEX_SIZE;
        double texStep = (double) sz / lineHeight;
        double texPos  = (drawStart - halfH + lineHeight / 2.0) * texStep;

        for (int y = drawStart; y < drawEnd; y++) {
            int texY  = (int) texPos & TextureAtlas.TEX_MASK;
            texPos   += texStep;
            pixels[y * screenW + x] = applyLight(tex[texY * sz + texX], wallLit);
        }
    }

    // ── Sprite rendering ──────────────────────────────────────────────────────

    /**
     * Projects and draws every table billboard.
     * Sprites are sorted back-to-front; only columns closer than zBuffer are drawn.
     * Pixels with alpha == 0 in the sprite texture are skipped.
     */
    private void renderSprites(Player player, int[] pixels) {
        int n = TABLES.length;

        // ── Sort back-to-front (insertion sort — n is small) ─────────────────
        double[] dist  = new double[n];
        int[]    order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
            double dx = player.x - TABLES[i][0];
            double dy = player.y - TABLES[i][1];
            dist[i]   = dx * dx + dy * dy;
        }
        for (int i = 1; i < n; i++) {
            int    key  = order[i];
            double keyD = dist[key];
            int j = i - 1;
            while (j >= 0 && dist[order[j]] < keyD) { order[j + 1] = order[j--]; }
            order[j + 1] = key;
        }

        // ── Camera inverse determinant ────────────────────────────────────────
        double invDet = 1.0 / (player.planeX * player.dirY - player.dirX * player.planeY);

        int[] tex  = atlas.tableSprite;
        int   sz   = TextureAtlas.TEX_SIZE;
        int   mask = TextureAtlas.TEX_MASK;

        for (int s = 0; s < n; s++) {
            int    idx = order[s];
            double spX = TABLES[idx][0] - player.x;
            double spY = TABLES[idx][1] - player.y;

            // Transform to camera space
            double transformX = invDet * ( player.dirY  * spX - player.dirX  * spY);
            double transformY = invDet * (-player.planeY * spX + player.planeX * spY);

            if (transformY <= 0.1) continue; // behind or too close

            int screenX = (int)((screenW / 2.0) * (1.0 + transformX / transformY));

            int spriteH  = Math.abs((int)(screenH / transformY));
            int spriteW  = spriteH;

            int drawStartY = Math.max(0,       halfH - spriteH / 2);
            int drawEndY   = Math.min(screenH, halfH + spriteH / 2);
            int drawStartX = Math.max(0,       screenX - spriteW / 2);
            int drawEndX   = Math.min(screenW, screenX + spriteW / 2);

            float spriteLit = computeLighting(TABLES[idx][0], TABLES[idx][1]);

            for (int stripe = drawStartX; stripe < drawEndX; stripe++) {
                if (transformY >= zBuffer[stripe]) continue; // behind a wall

                int texX = (stripe - (screenX - spriteW / 2)) * sz / spriteW;
                texX = texX & mask;

                for (int y = drawStartY; y < drawEndY; y++) {
                    int texY  = (y - drawStartY) * sz / spriteH;
                    texY      = texY & mask;
                    int color = tex[texY * sz + texX];
                    if ((color & 0xFF) != 0) { // skip transparent pixels
                        pixels[y * screenW + stripe] = applyLight(color, spriteLit);
                    }
                }
            }
        }
    }
}
