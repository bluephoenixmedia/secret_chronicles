package com.iangame.engine;

/**
 * Generates all game textures procedurally at startup.
 *
 * <p>All textures are {@value TEX_SIZE}×{@value TEX_SIZE} RGBA8888 {@code int[]}
 * arrays.  The aesthetic is "old house slowly falling apart" — peeling wallpaper,
 * crumbling brick, rotting wood, water-stained plaster.
 *
 * <p>Noise is produced by a deterministic integer hash so textures are identical
 * every run and require no file assets.
 */
public class TextureAtlas {

    public static final int TEX_SIZE = 64;
    public static final int TEX_MASK = TEX_SIZE - 1; // safe wrap: TEX_SIZE is pow-2

    // wall textures indexed by map tile type (index 0 unused)
    private final int[][] wall = new int[7][];
    public  final int[]   floor;
    public  final int[]   ceiling;

    public TextureAtlas() {
        wall[0] = blank();
        wall[1] = makeCrumblingBrick();
        wall[2] = makeCrackedPlaster();
        wall[3] = makeMossyWoodPanel();
        wall[4] = makePeelingWallpaper();
        wall[5] = makeRottingDoorWood();
        wall[6] = makeTerracotta();
        floor   = makeWornFloorboards();
        ceiling = makeWaterStainedCeiling();
    }

    /** Returns the texture for a wall tile type, falling back to type 1 if unknown. */
    public int[] getWall(int type) {
        if (type < 1 || type >= wall.length) return wall[1];
        return wall[type];
    }

    // ── Noise primitives ──────────────────────────────────────────────────────

    /** Deterministic integer hash → [0, 255]. */
    private static int hash(int x, int y, int seed) {
        int h = seed ^ (x * 374761393) ^ (y * 668265263);
        h = (h ^ (h >>> 13)) * 1540483477;
        return (h ^ (h >>> 15)) & 0xFF;
    }

    /** Bilinear-interpolated smooth noise → [0, 255]. */
    private static int smooth(int x, int y, int scale, int seed) {
        int gx = x / scale, rx = x % scale;
        int gy = y / scale, ry = y % scale;
        int a  = hash(gx,     gy,     seed);
        int b  = hash(gx + 1, gy,     seed);
        int c  = hash(gx,     gy + 1, seed);
        int d  = hash(gx + 1, gy + 1, seed);
        int ab = a + (b - a) * rx / scale;
        int cd = c + (d - c) * rx / scale;
        return ab + (cd - ab) * ry / scale;
    }

    // ── Colour helpers ────────────────────────────────────────────────────────

    private static int rgba(int r, int g, int b) {
        return (clamp(r) << 24) | (clamp(g) << 16) | (clamp(b) << 8) | 0xFF;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private int[] blank() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        java.util.Arrays.fill(t, rgba(0, 0, 0));
        return t;
    }

    // ── Wall type 1: crumbling red brick ──────────────────────────────────────
    //   Offset brick rows, dirty mortar lines, colour variation per brick,
    //   random cracks and a few missing-chunk holes.

    private int[] makeCrumblingBrick() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        final int BH = 9, BW = 18; // brick height / width in texels
        for (int y = 0; y < TEX_SIZE; y++) {
            int row   = y / BH;
            int ly    = y % BH;
            int offX  = (row % 2) * (BW / 2);
            for (int x = 0; x < TEX_SIZE; x++) {
                int lx = (x + offX) % BW;
                boolean mortar = (lx <= 1 || ly == 0);

                int n  = smooth(x, y, 4, 11);
                int nB = hash((x + offX) / BW, row, 22); // per-brick variation

                int r, g, b;
                if (mortar) {
                    r = 128 + (n >> 4) - 8;
                    g = 118 + (n >> 4) - 8;
                    b = 105 + (n >> 5) - 4;
                    // crumbled mortar gaps → near-black
                    if (hash(lx + (x + offX) / BW * 20, ly + row * 9, 33) > 230) {
                        r = 40; g = 33; b = 28;
                    }
                } else {
                    r = 148 + (nB >> 3) + (n >> 4) - 12;
                    g =  62 + (nB >> 5) + (n >> 6) -  4;
                    b =  45 + (n  >> 6);
                    // surface cracks
                    if (hash(x * 3, y * 2, 44) > 244) { r = 38; g = 28; b = 22; }
                    // missing chunk holes
                    if (hash(x + row * 7, y + 3, 55) > 251) { r = 18; g = 14; b = 10; }
                }
                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Wall type 2: cracked dirty plaster ───────────────────────────────────
    //   Off-white base, yellow-brown age stains, branching crack lines,
    //   patches where plaster has fallen away exposing grey stone.

    private int[] makeCrackedPlaster() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int n1 = smooth(x, y,  8, 101);
                int n2 = smooth(x, y, 18, 102);
                int n3 = hash(x, y, 103);

                // Dirty off-white base
                int r = 192 + (n1 >> 3) - 16;
                int g = 183 + (n2 >> 3) - 16;
                int b = 168 + (n1 >> 4) -  8;

                // Yellow-brown age stains
                int stain = smooth(x, y + 8, 22, 104);
                if (stain > 145) {
                    int s = stain - 145;
                    r += s / 3; g += s / 6; b -= s / 5;
                }

                // Exposed grey stone patches (smooth region + local noise)
                if (smooth(x + 12, y + 12, 14, 105) > 195 && n3 > 185) {
                    r = 138 + (n1 >> 5); g = 133 + (n1 >> 5); b = 128 + (n1 >> 5);
                }

                // Crack lines
                if (hash(x * 2, y,     106) > 251) { r = 75; g = 68; b = 58; }
                if (hash(x,     y * 2, 107) > 253) { r = 75; g = 68; b = 58; }

                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Wall type 3: mossy rotting wood paneling ──────────────────────────────
    //   Vertical planks separated by dark gaps, brown grain, green mold
    //   creeping up from the bottom, dark water-stain streaks.

    private int[] makeMossyWoodPanel() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        final int PW = 11; // plank width
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int lx = x % PW;
                boolean gap = (lx <= 1);

                int grain  = smooth(x, y, 3, 201);
                int coarse = smooth(x / PW, y, 7, 202);
                int n      = hash(x, y, 203);

                int r, g, b;
                if (gap) {
                    r = 18; g = 13; b = 8;
                } else {
                    r = 98  + (coarse >> 3) + (grain >> 4) -  8;
                    g = 65  + (coarse >> 4) + (grain >> 5) -  4;
                    b = 32  + (n     >> 6);

                    // Wood grain lines
                    if (hash(x, y * 3, 204) > 218) { r -= 22; g -= 16; b -= 10; }

                    // Moss / mould (builds from bottom: y near TEX_SIZE)
                    int mossy = smooth(x, y + 8, 12, 205);
                    if (mossy > 148) {
                        int m = mossy - 148;
                        r -= m >> 2;
                        g  = g + (m >> 2) - (m >> 4);
                        b -= m >> 4;
                    }

                    // Vertical water-stain streak per plank
                    int streakX = (x / PW) * PW + PW / 2;
                    if (smooth(streakX, y, 5, 206) > 172) { r -= 28; g -= 22; b -= 12; }
                }
                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Wall type 4: peeling wallpaper ────────────────────────────────────────
    //   Faint faded stripe pattern, heavily yellowed, large peel zones that
    //   reveal grey plaster underneath, brown damp stains.

    private int[] makePeelingWallpaper() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int stripe = (x / 9) % 2;
                int n1     = smooth(x, y,  7, 301);
                int n2     = smooth(x, y, 20, 302);
                int n3     = hash(x, y, 303);

                // Yellowed cream/ivory base
                int r = (stripe == 0 ? 218 : 205) + (n1 >> 4) - 8;
                int g = (stripe == 0 ? 205 : 192) + (n1 >> 4) - 8;
                int b = (stripe == 0 ? 168 : 158) + (n2 >> 5) - 4;

                // Ghost floral motif (very subtle)
                int fx = x % 18, fy = y % 18;
                boolean floral = (fx == 8 || fx == 9) && (fy >= 6 && fy <= 11);
                if (floral) { r -= 8; g -= 6; }

                // Peel zones: expose grey plaster
                if (smooth(x + 6, y + 6, 16, 304) > 178 && n3 > 155) {
                    r = 155 + (n1 >> 5); g = 150 + (n1 >> 5); b = 145 + (n1 >> 5);
                }

                // Brown damp stains
                int stain = smooth(x, y + 25, 19, 305);
                if (stain > 168) { r += 18; g -= 4; b -= 22; }

                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Wall type 5: rotting dark wood (door frames) ──────────────────────────
    //   Dark brown wood, heavy grain, deep splits, rot patches with slight
    //   greenish tinge, recessed nail holes.

    private int[] makeRottingDoorWood() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int grain  = smooth(x, y, 2, 401);
                int coarse = smooth(x, y, 9, 402);
                int n      = hash(x, y, 403);

                int r = 72  + (coarse >> 4) + (grain >> 5) -  8;
                int g = 42  + (coarse >> 5) + (grain >> 6) -  4;
                int b = 18  + (grain  >> 6) + (n >> 7);

                // Heavy grain
                int gl = hash(x, y * 2, 404);
                if (gl > 205) { r -= 28; g -= 18; b -= 10; }
                if (gl > 248) { r = 15;  g = 10;  b =  5; }

                // Rot patches (dark, hint of green)
                int rot = smooth(x, y, 13, 405);
                if (rot > 188) {
                    int s = rot - 188;
                    r -= s >> 1;
                    g  = g - (s >> 2) + (s >> 5);
                    b -= s >> 3;
                }

                // Splits
                if (hash(x * 2, y, 406) > 248) { r = 12; g = 8; b = 4; }

                // Nail holes (two per tile)
                int nhx = x % 32, nhy = y % 48;
                if (nhx >= 14 && nhx <= 17 && nhy >= 21 && nhy <= 24) { r = 18; g = 13; b = 8; }

                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Wall type 6: faded terracotta with grime ──────────────────────────────
    //   Warm orange-brown, irregular surface variation, dirt build-up,
    //   faded lighter patches, fine hairline cracks.

    private int[] makeTerracotta() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int n1 = smooth(x, y,  6, 501);
                int n2 = smooth(x, y, 14, 502);

                int r = 172 + (n1 >> 4) - 8;
                int g =  78 + (n2 >> 5) - 4;
                int b =  42 + (n1 >> 5) - 4;

                // Grime (darkens with accumulated noise)
                int grime = smooth(x, y + 12, 9, 503);
                if (grime > 148) {
                    int s = grime - 148;
                    r -= s / 4; g -= s / 5; b -= s / 6;
                }

                // Faded lighter patches
                if (smooth(x + 18, y, 17, 504) > 182) { r += 22; g += 16; b += 12; }

                // Fine cracks
                if (hash(x * 3, y, 505) > 252) { r = 88; g = 52; b = 33; }

                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Floor: worn wooden floorboards ───────────────────────────────────────
    //   Horizontal planks with staggered joints, wood grain within each plank,
    //   dark gaps, worn-through patches, dark spill stains.

    private int[] makeWornFloorboards() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        final int PH = 8; // plank height
        for (int y = 0; y < TEX_SIZE; y++) {
            int plank  = y / PH;
            int ly     = y % PH;
            int offX   = (plank % 4) * 19; // stagger joints
            boolean gap = (ly == 0 || ly == 1);
            for (int x = 0; x < TEX_SIZE; x++) {
                int lx = (x + offX) & TEX_MASK;
                boolean joint = (lx == 0);

                int grain  = smooth(x, y, 3, 601);
                int coarse = smooth((x + offX) / TEX_SIZE, plank, 4, 602);
                int n      = hash(x, y, 603);

                int r, g, b;
                if (gap || joint) {
                    r = 18; g = 13; b = 8;
                } else {
                    r = 95  + (coarse >> 4) + (grain >> 5) -  8;
                    g = 62  + (coarse >> 5) + (grain >> 6) -  4;
                    b = 28  + (grain  >> 6);

                    // Wear scratches
                    if (hash(x * 2, y, 604) > 232) { r -= 22; g -= 16; b -= 10; }

                    // Dark spill stains
                    if (smooth(x, y, 10, 605) > 208 && n > 208) { r -= 38; g -= 30; b -= 20; }
                }
                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Ceiling: water-stained plaster ────────────────────────────────────────
    //   Off-white cream, brownish concentric water-ring stains, fine cracks.

    private int[] makeWaterStainedCeiling() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        // Pre-compute stain centre positions (3 stains, deterministically placed)
        int[] sx = { hash(0, 0, 701) & TEX_MASK, hash(1, 0, 701) & TEX_MASK, hash(2, 0, 701) & TEX_MASK };
        int[] sy = { hash(0, 1, 701) & TEX_MASK, hash(1, 1, 701) & TEX_MASK, hash(2, 1, 701) & TEX_MASK };
        int[] sr = { 7 + (hash(0, 2, 701) & 7), 5 + (hash(1, 2, 701) & 5), 6 + (hash(2, 2, 701) & 6) };

        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int n1 = smooth(x, y, 10, 702);

                int r = 208 + (n1 >> 4) - 8;
                int g = 198 + (n1 >> 4) - 8;
                int b = 183 + (n1 >> 5) - 4;

                // Water-stain rings
                for (int i = 0; i < 3; i++) {
                    int dx   = x - sx[i], dy = y - sy[i];
                    int dist = (int) Math.sqrt(dx * dx + dy * dy);
                    if (dist >= sr[i] - 2 && dist <= sr[i] + 3) {
                        int ring = 55 - Math.abs(dist - sr[i]) * 12;
                        if (ring > 0) { r -= ring >> 1; g -= ring / 3; b -= ring; }
                    }
                    // Faint interior wash
                    if (dist < sr[i] - 2) {
                        int wash = (sr[i] - 2 - dist) * 2;
                        r -= wash >> 3; g -= wash >> 4; b -= wash >> 2;
                    }
                }

                // Fine cracks
                if (hash(x * 2, y, 704) > 250) { r = 158; g = 150; b = 140; }

                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }
}
