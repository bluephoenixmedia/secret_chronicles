package com.iangame.engine;

import com.iangame.player.Player;
import com.iangame.world.GameMap;

/**
 * Core raycasting engine — DDA wall casting + floor/ceiling projection.
 *
 * <p>Lighting model: very dark ambient ({@value AMBIENT}) plus warm point
 * lights, one centred in each room.  Distance falloff is quadratic
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

    private float[][] lights    = new float[0][4];
    private float[]   lightRadSq = new float[0];

    // ── Flicker state (one entry per light) ───────────────────────────────────

    /** Average number of flicker events triggered per second, per light. */
    private static final float FLICKER_CHANCE = 0.4f;
    /** Speed at which the brightness lerps toward its target. */
    private static final float FLICKER_LERP   = 16f;

    private float[]   flickerMult    = new float[0];
    private float[]   flickerTarget  = new float[0];
    private float[]   flickerTimer   = new float[0];
    private boolean[] intenseFlicker = new boolean[0];
    private final java.util.Random rng = new java.util.Random();

    // ── Sprite / table positions (world-space x, y) ───────────────────────────

    private float[][] tables       = new float[0][];
    private float[][] cabinetBoxes = new float[0][];
    private float[][] bloodSpots   = new float[0][2];

    public void setBloodSpots(float[][] spots) { this.bloodSpots = spots; }

    /** Per-column depth buffer populated during wall casting. */
    private final double[] zBuffer;

    private final TextureAtlas atlas;

    // ── Flashlight state ──────────────────────────────────────────────────────

    private boolean flashlightOn = false;
    /** Player position/direction captured at the start of each render pass. */
    private double flashPX, flashPY, flashDX, flashDY;

    // Flashlight flicker (same lerp approach as room lights)
    private float flashMult   = 1f;
    private float flashTarget = 1f;
    private float flashTimer  = 0f;
    private float flashBattery = 1f;  // 0..1, drives brightness and flicker intensity

    /** When true, all room lights are suppressed — only ambient remains. */
    private boolean lightsOut = false;

    /** Toggles the player's flashlight on or off. */
    public void setFlashlight(boolean on) { this.flashlightOn = on; }

    /** Turns all room lights off (true) or back on (false). */
    public void setLightsOut(boolean out) { this.lightsOut = out; }

    /** Updates the battery level (0..1) used to dim and destabilise the flashlight. */
    public void setBattery(float battery) { this.flashBattery = Math.max(0f, Math.min(1f, battery)); }

    public RayCaster(int screenWidth, int screenHeight) {
        this.screenW = screenWidth;
        this.screenH = screenHeight;
        this.halfH   = screenHeight / 2;
        this.atlas   = new TextureAtlas();
        this.zBuffer = new double[screenWidth];
    }

    /**
     * Loads world objects from the procedurally generated map.
     * Rebuilds all flicker arrays to match the new light count.
     */
    public void setWorldObjects(float[][] lights, float[][] tables, float[][] cabinetBoxes) {
        this.lights       = lights;
        this.tables       = tables;
        this.cabinetBoxes = cabinetBoxes;

        int n = lights.length;
        lightRadSq    = new float[n];
        flickerMult   = new float[n];
        flickerTarget = new float[n];
        flickerTimer  = new float[n];
        intenseFlicker = new boolean[n];
        for (int i = 0; i < n; i++) {
            float r = lights[i][3];
            lightRadSq[i]   = r * r;
            flickerMult[i]  = 1f;
            flickerTarget[i] = 1f;
        }
    }

    /** Enables or disables aggressive flickering on a single room light. */
    public void setIntenseFlicker(int lightIndex, boolean on) {
        if (lightIndex >= 0 && lightIndex < intenseFlicker.length)
            intenseFlicker[lightIndex] = on;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Advances flicker animations.  Call once per frame before {@link #render}.
     */
    public void update(float dt) {
        for (int i = 0; i < lights.length; i++) {
            flickerTimer[i] -= dt;

            if (flickerTimer[i] <= 0f) {
                float chance = intenseFlicker[i] ? FLICKER_CHANCE * 20f : FLICKER_CHANCE;
                if (rng.nextFloat() < chance * dt) {
                    // Trigger a flicker: intense mode dips much deeper and more often
                    float floor  = intenseFlicker[i] ? 0.02f : 0.10f;
                    float range  = intenseFlicker[i] ? 0.30f : 0.55f;
                    float hold   = intenseFlicker[i] ? 0.08f + rng.nextFloat() * 0.18f
                                                     : 0.03f + rng.nextFloat() * 0.11f;
                    flickerTarget[i] = floor + rng.nextFloat() * range;
                    flickerTimer[i]  = hold;
                } else {
                    // Idle: intense mode stays dimmer on average
                    flickerTarget[i] = intenseFlicker[i]
                        ? 0.55f + rng.nextFloat() * 0.30f
                        : 0.97f + rng.nextFloat() * 0.06f;
                }
            }

            // Lerp current multiplier toward target
            flickerMult[i] += (flickerTarget[i] - flickerMult[i])
                              * Math.min(1f, FLICKER_LERP * dt);
            if (flickerMult[i] < 0.05f) flickerMult[i] = 0.05f;
            if (flickerMult[i] > 1.00f) flickerMult[i] = 1.00f;
        }

        // Flashlight flicker — chance and dip depth increase as battery drains
        if (flashlightOn) {
            float depletion     = 1f - flashBattery;               // 0 = full, 1 = dead
            float flickerChance = 0.3f + depletion * 5.0f;        // ramps up sharply
            float dipMin        = 0.35f - depletion * 0.30f;      // floor drops lower
            float idleMax       = 1.00f - depletion * 0.25f;      // ceiling falls too

            flashTimer -= dt;
            if (flashTimer <= 0f) {
                if (rng.nextFloat() < flickerChance * dt) {
                    flashTarget = dipMin + rng.nextFloat() * 0.35f;
                    flashTimer  = 0.02f + rng.nextFloat() * 0.08f;
                } else {
                    flashTarget = (idleMax - 0.10f) + rng.nextFloat() * 0.10f;
                }
            }
            flashMult += (flashTarget - flashMult) * Math.min(1f, FLICKER_LERP * dt);
            if (flashMult < 0.05f) flashMult = 0.05f;
            if (flashMult > 1.00f) flashMult = 1.00f;
        }
    }

    public void render(Player player, GameMap map, DoorManager doors, int[] pixels) {
        // Snapshot player state so computeLighting can use it for the flashlight.
        flashPX = player.x;  flashPY = player.y;
        flashDX = player.dirX; flashDY = player.dirY;

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
        if (!lightsOut) for (int i = 0; i < lights.length; i++) {
            double dx    = wx - lights[i][0];
            double dy    = wy - lights[i][1];
            double dist2 = dx * dx + dy * dy;
            if (dist2 < lightRadSq[i]) {
                double t = 1.0 - dist2 / lightRadSq[i];
                total   += (float)(lights[i][2] * flickerMult[i] * t * t);
            }
        }
        // Flashlight: a directional cone centred on the player's aim direction.
        if (flashlightOn) {
            double fdx   = wx - flashPX;
            double fdy   = wy - flashPY;
            double dist2 = fdx * fdx + fdy * fdy;
            double maxR  = 10.0;
            if (dist2 < maxR * maxR && dist2 > 0.0001) {
                double dist = Math.sqrt(dist2);
                double dot  = (fdx / dist) * flashDX + (fdy / dist) * flashDY;
                // ~40° half-angle cone (cos 40° ≈ 0.766)
                if (dot > 0.766) {
                    double falloff     = 1.0 - dist2 / (maxR * maxR);
                    double angleFactor = (dot - 0.766) / (1.0 - 0.766);
                    total += (float)(0.65 * flashBattery * flashMult * falloff * falloff * angleFactor);
                }
            }
        }

        return Math.min(1f, total);
    }

    /** Blends a colour toward dark red by factor t (0=original, 1=full blood tint). */
    private static int blendToRed(int rgba, float t) {
        int r = (rgba >> 24) & 0xFF;
        int g = (rgba >> 16) & 0xFF;
        int b = (rgba >>  8) & 0xFF;
        r = Math.min(255, r + (int)(70 * t));
        g = Math.max(0,   (int)(g * (1f - t * 0.75f)));
        b = Math.max(0,   (int)(b * (1f - t * 0.75f)));
        return (r << 24) | (g << 16) | (b << 8) | 0xFF;
    }

    /** Shifts a lit colour to red (boosts R, crushes G/B). */
    private static int applyRedTint(int rgba) {
        int r = Math.min(255, (int)(((rgba >> 24) & 0xFF) * 1.4f));
        int g = (int)(((rgba >> 16) & 0xFF) * 0.18f);
        int b = (int)(((rgba >>  8) & 0xFF) * 0.18f);
        return (r << 24) | (g << 16) | (b << 8) | 0xFF;
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

                int floorColor = applyLight(floorTex[idx], lit);
                for (float[] spot : bloodSpots) {
                    double bdx = worldX - spot[0];
                    double bdy = worldY - spot[1];
                    double bd2 = bdx * bdx + bdy * bdy;
                    if (bd2 < 0.25) {
                        floorColor = blendToRed(floorColor, (float)(1.0 - bd2 / 0.25) * 0.85f);
                    }
                }
                pixels[floorRow + x] = floorColor;
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

        // ── Cabinet slab intersection (ray-AABB) ─────────────────────────────
        int     hitCab   = -1;
        boolean cabXFace = false;
        double  cabU     = 0;
        for (int ci = 0; ci < cabinetBoxes.length; ci++) {
            float  cx2 = cabinetBoxes[ci][0], cy2 = cabinetBoxes[ci][1];
            float  hw  = cabinetBoxes[ci][2], hd  = cabinetBoxes[ci][3];
            double txMin, txMax, tyMin, tyMax;
            if (rayDirX != 0) {
                txMin = ((cx2 - hw) - player.x) / rayDirX;
                txMax = ((cx2 + hw) - player.x) / rayDirX;
                if (txMin > txMax) { double tmp = txMin; txMin = txMax; txMax = tmp; }
            } else if (player.x >= cx2 - hw && player.x <= cx2 + hw) {
                txMin = -1e18; txMax = 1e18;
            } else continue;
            if (rayDirY != 0) {
                tyMin = ((cy2 - hd) - player.y) / rayDirY;
                tyMax = ((cy2 + hd) - player.y) / rayDirY;
                if (tyMin > tyMax) { double tmp = tyMin; tyMin = tyMax; tyMax = tmp; }
            } else if (player.y >= cy2 - hd && player.y <= cy2 + hd) {
                tyMin = -1e18; tyMax = 1e18;
            } else continue;
            double tEnter = Math.max(txMin, tyMin);
            double tExit  = Math.min(txMax, tyMax);
            if (tEnter >= tExit || tEnter <= 0.001 || tEnter >= perpWallDist) continue;
            perpWallDist = tEnter;
            hitCab   = ci;
            cabXFace = (txMin >= tyMin);
            if (cabXFace) {
                double hy = player.y + tEnter * rayDirY;
                cabU = Math.max(0, Math.min(1, (hy - (cy2 - hd)) / (2.0 * hd)));
            } else {
                double hx = player.x + tEnter * rayDirX;
                cabU = Math.max(0, Math.min(1, (hx - (cx2 - hw)) / (2.0 * hw)));
            }
        }

        // Record depth for sprite occlusion
        zBuffer[x] = perpWallDist;

        // World position of the hit — used for lighting
        double hitX   = player.x + perpWallDist * rayDirX;
        double hitY   = player.y + perpWallDist * rayDirY;
        float  wallLit = computeLighting(hitX, hitY);

        int    sz   = TextureAtlas.TEX_SIZE;
        int[]  tex;
        int    texX;

        boolean redCabinet = false;
        if (hitCab >= 0) {
            // Front face: X-aligned hit where the ray approaches from the correct side
            boolean isFront = cabXFace && ((rayDirX < 0 ? 1f : -1f) == cabinetBoxes[hitCab][4]);
            if (!isFront) wallLit *= SIDE_SHADE;
            tex  = isFront ? atlas.cabinetFront : atlas.cabinetSide;
            texX = (int)(cabU * sz) & TextureAtlas.TEX_MASK;
            redCabinet = ((hitCab + 1) % 5 == 0);
        } else {
            // E/W faces receive an extra directional shade
            if (side == 1 && !hitDoor) wallLit *= SIDE_SHADE;
            tex = atlas.getWall(wallType);
            if (hitDoor) {
                texX = (int)(wallX * sz) & TextureAtlas.TEX_MASK;
            } else {
                texX = (int)(wallX * sz);
                if (side == 0 && rayDirX > 0) texX = sz - texX - 1;
                if (side == 1 && rayDirY < 0) texX = sz - texX - 1;
                texX = texX & TextureAtlas.TEX_MASK;
            }
        }

        int lineHeight = (int) (screenH / perpWallDist);
        int drawStart  = Math.max(0,       halfH - lineHeight / 2);
        int drawEnd    = Math.min(screenH, halfH + lineHeight / 2);

        double texStep = (double) sz / lineHeight;
        double texPos  = (drawStart - halfH + lineHeight / 2.0) * texStep;

        for (int y = drawStart; y < drawEnd; y++) {
            int texY  = (int) texPos & TextureAtlas.TEX_MASK;
            texPos   += texStep;
            int color = applyLight(tex[texY * sz + texX], wallLit);
            pixels[y * screenW + x] = redCabinet ? applyRedTint(color) : color;
        }
    }

    // ── Sprite rendering ──────────────────────────────────────────────────────

    /**
     * Projects and draws every table billboard.
     * Sprites are sorted back-to-front; only columns closer than zBuffer are drawn.
     * Pixels with alpha == 0 in the sprite texture are skipped.
     */
    private void renderSprites(Player player, int[] pixels) {
        int n = tables.length;
        if (n == 0) return;

        // ── Sort back-to-front (insertion sort — n is small) ─────────────────
        double[] dist  = new double[n];
        int[]    order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
            double dx = player.x - tables[i][0];
            double dy = player.y - tables[i][1];
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
            double spX = tables[idx][0] - player.x;
            double spY = tables[idx][1] - player.y;

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

            float spriteLit = computeLighting(tables[idx][0], tables[idx][1]);

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
