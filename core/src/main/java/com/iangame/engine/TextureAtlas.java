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
    private final int[][] wall = new int[10][];
    public  final int[]   floor;
    public  final int[]   ceiling;
    /** Billboard sprite for table objects (transparent background). */
    public  final int[]   tableSprite;
    /** Front face texture for 3-D cabinet boxes (two-door wardrobe). */
    public  final int[]   cabinetFront;
    /** Side face texture for 3-D cabinet boxes (plain dark wood). */
    public  final int[]   cabinetSide;

    public TextureAtlas() {
        wall[0] = blank();
        wall[1] = makeCrumblingBrick();
        wall[2] = makeCrackedPlaster();
        wall[3] = makeMossyWoodPanel();
        wall[4] = makePeelingWallpaper();
        wall[5] = makeRottingDoorWood();
        wall[6] = makeTerracotta();
        wall[7] = makeDoorPanel();
        wall[8] = makeWindowNightSky();
        wall[9] = makeEmergencyExit();
        floor        = makeWornFloorboards();
        ceiling      = makeWaterStainedCeiling();
        tableSprite  = makeTableSprite();
        cabinetFront = makeCabinetFrontTex();
        cabinetSide  = makeCabinetSideTex();
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

    // ── Wall type 7: aged door panel ─────────────────────────────────────────
    //   Four-panel door design (2×2 grid of raised panels) in dark painted wood.
    //   Heavy grain, age stains, small peeling spots.

    private int[] makeDoorPanel() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int grain  = smooth(x, y, 2, 801);
                int coarse = smooth(x, y, 8, 802);
                int n      = hash(x, y, 803);

                // Dark painted wood base
                int r = 58 + (coarse >> 4) + (grain >> 5) - 6;
                int g = 50 + (coarse >> 5) + (grain >> 6) - 3;
                int b = 40 + (grain >> 6) + (n >> 7);

                // 2×2 panel grid — each panel occupies a 32×32 sub-tile
                int lx = x % 32, ly = y % 32;
                boolean onFrame = (lx < 4 || lx > 27 || ly < 4 || ly > 27);
                if (onFrame) {
                    r -= 12; g -= 9; b -= 6;  // frame: darker, worn
                } else {
                    r += 16; g += 12; b += 8; // panel recess: slightly lighter
                }

                // Raised inner rim of each panel
                if (lx == 4 || lx == 27 || ly == 4 || ly == 27) {
                    r += 22; g += 16; b += 10;
                }

                // Vertical wood grain
                if (hash(x, y * 2, 804) > 210) { r -= 16; g -= 11; b -= 7; }
                if (hash(x, y * 3, 805) > 250) { r = 14;  g = 9;   b = 4; }

                // Age stains / grime
                int stain = smooth(x, y, 14, 806);
                if (stain > 185) { r -= 10; g -= 8; b -= 5; }

                // Peeling paint spots
                if (smooth(x + 7, y + 7, 11, 807) > 202 && n > 200) {
                    r += 20; g += 16; b += 10;
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

    // ── Cabinet front-face texture ────────────────────────────────────────────
    //   Full 64×64 opaque texture.  Tall two-door wardrobe (floor-to-ceiling).
    //   y=0 = top, y=63 = floor.

    private int[] makeCabinetFrontTex() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int grain  = smooth(x, y, 2, 950);
                int coarse = smooth(x, y, 8, 951);
                int r = 88 + (coarse >> 4) + (grain >> 5) - 8;
                int g = 52 + (coarse >> 5) + (grain >> 6) - 4;
                int b = 22 + (grain >> 6);
                if (hash(x, y * 2, 952) > 215) { r -= 20; g -= 14; b -= 8; }
                if (hash(x, y * 3, 953) > 250) { r = 25;  g = 14;  b =  5; }

                // Outer frame border
                boolean isFrame = (x < 3 || x > 60 || y < 3 || y > 60);
                if (isFrame) { r -= 18; g -= 13; b -= 7; }

                // Crown moulding highlight at top
                if (y >= 3 && y <= 6) { r += 14; g += 9; b += 4; }

                // Centre vertical divider between the two doors (y 6..55)
                boolean split = (x >= 30 && x <= 33 && y >= 6 && y <= 55);
                if (split) { r -= 24; g -= 17; b -= 10; }

                // Door section y 6..55
                boolean inDoor = (y >= 6 && y <= 55);

                // Panel recesses
                boolean lPanel = inDoor && (x >= 5  && x <= 27 && y >= 10 && y <= 51);
                boolean rPanel = inDoor && (x >= 36 && x <= 58 && y >= 10 && y <= 51);
                if (lPanel || rPanel) { r -= 14; g -= 10; b -= 6; }

                // Panel border highlights
                boolean lBorder = inDoor && lPanel && (x == 5 || x == 27 || y == 10 || y == 51);
                boolean rBorder = inDoor && rPanel && (x == 36 || x == 58 || y == 10 || y == 51);
                if (lBorder || rBorder) { r += 16; g += 11; b += 5; }

                // Brass handles
                boolean lHandle = (x >= 25 && x <= 28 && y >= 29 && y <= 33);
                boolean rHandle = (x >= 35 && x <= 38 && y >= 29 && y <= 33);
                if (lHandle || rHandle) { r = 162; g = 128; b = 52; }

                // Drawer section y 56..60
                boolean inDrawer = (y >= 56 && y <= 60 && x >= 5 && x <= 58);
                if (y >= 55 && y <= 61 && !isFrame) { r -= 10; g -= 7; b -= 4; }
                if (inDrawer) { r -= 12; g -= 9; b -= 5; }
                boolean drawerHandle = (x >= 27 && x <= 36 && y >= 57 && y <= 59);
                if (drawerHandle) { r = 162; g = 128; b = 52; }

                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Cabinet side-face texture ─────────────────────────────────────────────
    //   Full 64×64 opaque texture.  Plain dark wood grain, no detail.

    private int[] makeCabinetSideTex() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int grain  = smooth(x, y, 2, 960);
                int coarse = smooth(x, y, 9, 961);
                int r = 65 + (coarse >> 4) + (grain >> 5) - 8;
                int g = 38 + (coarse >> 5) + (grain >> 6) - 4;
                int b = 15 + (grain >> 6);
                if (hash(x, y * 2, 962) > 208) { r -= 22; g -= 15; b -= 9; }
                if (hash(x, y * 3, 963) > 249) { r = 20;  g = 11;  b =  4; }
                if (x < 3 || x > 60)           { r -= 15; g -= 10; b -= 6; }
                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Wall type 8: small window with plain night sky ────────────────────────
    //   Thick dark-wood frame leaving a narrow opening; flat dark sky, no details.

    private int[] makeWindowNightSky() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];
        final int FRAME = 10; // thin frame — leaves a 44×44 opening

        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean isFrame = (x < FRAME || x >= TEX_SIZE - FRAME ||
                                   y < FRAME || y >= TEX_SIZE - FRAME);
                int r, g, b;
                if (isFrame) {
                    // Aged dark wood frame
                    int grain  = smooth(x, y, 2, 920);
                    int coarse = smooth(x, y, 8, 921);
                    r = 52 + (coarse >> 4) + (grain >> 5) - 6;
                    g = 33 + (coarse >> 5) + (grain >> 6) - 3;
                    b = 14 + (grain >> 6);
                    if (hash(x, y * 2, 922) > 215) { r -= 18; g -= 12; b -= 7; }
                    if (hash(x, y * 3, 923) > 250) { r = 13;  g =  8;  b =  3; }
                } else {
                    // Plain dark night sky
                    r =  5;
                    g =  7;
                    b = 22;
                }
                t[y * TEX_SIZE + x] = rgba(r, g, b);
            }
        }
        return t;
    }

    // ── Wall type 9: emergency exit door with EXIT sign above ─────────────────
    //   Narrow white door centred on the tile (grey surrounds), red EXIT sign
    //   across the top, horizontal push-bar at eye level.
    //
    //   The raycaster mirrors texX for walls the player faces from inside, so
    //   characters are pre-mirrored (word reversed, each glyph flipped) so the
    //   hardware flip produces the correct "EXIT" reading.

    private int[] makeEmergencyExit() {
        int[] t = new int[TEX_SIZE * TEX_SIZE];

        final int L = 10, R = 53; // door left/right bounds (44 px wide, centred in 64)

        // Grey side-wall margins
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                if (x < L || x > R) {
                    int n = hash(x, y, 1003) & 0x0F;
                    int v = 150 + n - 8;
                    t[y * TEX_SIZE + x] = rgba(v, v, v);
                }
            }
        }

        // White door body (below the sign)
        for (int y = 18; y < TEX_SIZE; y++)
            for (int x = L; x <= R; x++) {
                int n = hash(x, y, 1001) & 0x0F;
                int v = 230 + n - 8;
                t[y * TEX_SIZE + x] = rgba(v, v, v);
            }

        // Door frame (slightly darker edge on three sides)
        for (int y = 18; y < TEX_SIZE; y++)
            for (int x = L; x <= R; x++)
                if (x == L || x == R || y == 18 || y >= 62)
                    t[y * TEX_SIZE + x] = rgba(190, 190, 190);

        // Horizontal push-bar at eye level (y 34–38)
        for (int y = 34; y <= 38; y++)
            for (int x = L + 3; x <= R - 3; x++)
                t[y * TEX_SIZE + x] = rgba(155, 162, 172);
        for (int y = 32; y <= 40; y++) {
            for (int x = L + 3; x <= L + 7; x++) t[y * TEX_SIZE + x] = rgba(128, 134, 144);
            for (int x = R - 7; x <= R - 3; x++) t[y * TEX_SIZE + x] = rgba(128, 134, 144);
        }

        // ── Red EXIT sign (y 0..17, same width as door) ──────────────────────
        for (int y = 0; y < 18; y++)
            for (int x = L; x <= R; x++)
                t[y * TEX_SIZE + x] = rgba(185, 22, 22);

        // Sign border
        for (int x = L; x <= R; x++) {
            t[0  * TEX_SIZE + x] = rgba(255, 180, 180);
            t[17 * TEX_SIZE + x] = rgba(255, 180, 180);
        }
        for (int y = 0; y < 18; y++) {
            t[y * TEX_SIZE + L] = rgba(255, 180, 180);
            t[y * TEX_SIZE + R] = rgba(255, 180, 180);
        }

        // "EXIT" pre-mirrored: word order is T,I,X,E and each glyph is flipped
        // horizontally so the raycaster's texX-flip renders it as "EXIT".
        int[][] charT_m = {{1,1,1,1,1},{0,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0}};
        int[][] charI_m = {{0,0,1,1,1},{0,0,0,1,0},{0,0,0,1,0},{0,0,0,1,0},{0,0,1,1,1}};
        int[][] charX_m = {{1,0,0,0,1},{0,1,0,1,0},{0,0,1,0,0},{0,1,0,1,0},{1,0,0,0,1}};
        int[][] charE_m = {{1,1,1,1,1},{0,0,0,0,1},{0,1,1,1,1},{0,0,0,0,1},{1,1,1,1,1}};
        int[][][] word   = {charT_m, charI_m, charX_m, charE_m};

        // 2× scale, gap=1 → total = 4*10 + 3*1 = 43 px; centred in 44 px sign
        int gap = 1, charPx = 10;
        int totalW = word.length * charPx + (word.length - 1) * gap; // 43
        int ox = L + (R - L + 1 - totalW) / 2;  // ≈ L
        int oy = 4;  // vertically centred in 18-px band

        for (int ci = 0; ci < word.length; ci++) {
            int cx = ox + ci * (charPx + gap);
            for (int row = 0; row < 5; row++)
                for (int col = 0; col < 5; col++)
                    if (word[ci][row][col] == 1)
                        for (int dy = 0; dy < 2; dy++)
                            for (int dx = 0; dx < 2; dx++) {
                                int px = cx + col * 2 + dx;
                                int py = oy + row * 2 + dy;
                                if (px >= L && px <= R && py >= 0 && py < 18)
                                    t[py * TEX_SIZE + px] = rgba(255, 255, 255);
                            }
        }

        return t;
    }

    // ── Table billboard sprite ─────────────────────────────────────────────────
    //   64×64 with transparent (0x00000000) background.
    //   Sprite y=32 maps to the player's eye level; y=63 maps to the floor.
    //   The table top sits just below eye level (sprite y≈36-44), legs below (y≈45-63).
    //   A small book and candle rest on the surface.

    private int[] makeTableSprite() {
        int[] t = new int[TEX_SIZE * TEX_SIZE]; // all zero = fully transparent

        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {

                // ── Geometry flags ────────────────────────────────────────────
                boolean topSurface = (y >= 36 && y <= 44 && x >= 4  && x <= 60);
                boolean topEdge    = (y == 36 && x >= 4  && x <= 60);
                boolean legLeft    = (y >= 45 && y <= 63 && x >= 8  && x <= 15);
                boolean legRight   = (y >= 45 && y <= 63 && x >= 49 && x <= 56);
                boolean stretcher  = (y >= 53 && y <= 56 && x >= 15 && x <= 49);
                // book on table (left side)
                boolean book       = (y >= 29 && y <= 36 && x >= 15 && x <= 27);
                boolean bookEdge   = book && (y == 29 || y == 36 || x == 15 || x == 27);
                // candle body (right side)
                boolean candle     = (y >= 25 && y <= 36 && x >= 38 && x <= 43);
                // flame above candle
                boolean flame      = (y >= 19 && y <= 25 && x >= 39 && x <= 42);
                boolean flameTip   = (y == 19 && x >= 40 && x <= 41);

                int r = 0, g = 0, b = 0, a = 0;

                if (topSurface || legLeft || legRight || stretcher) {
                    int grain  = smooth(x, y, 3, 910);
                    int coarse = smooth(x, y, 9, 911);
                    int n      = hash(x, y, 912);
                    r = 105 + (coarse >> 4) + (grain >> 5) - 8;
                    g =  68 + (coarse >> 5) + (grain >> 6) - 4;
                    b =  32 + (grain >> 6) + (n >> 7);
                    if (topEdge)                          { r -= 28; g -= 20; b -= 10; }
                    if (hash(x, y * 2, 913) > 215)        { r -= 16; g -= 11; b -= 6; }
                    if (hash(x * 2, y, 914) > 248)        { r = 18;  g = 11;  b =  5; }
                    a = 0xFF;

                } else if (book) {
                    int n = hash(x, y, 915);
                    r = bookEdge ? 28 : (52 + (n >> 5));
                    g = bookEdge ? 18 : (35 + (n >> 6));
                    b = bookEdge ? 10 : (18 + (n >> 7));
                    a = 0xFF;

                } else if (candle) {
                    // Cream/ivory candle body with subtle wax texture
                    int n = hash(x, y, 916);
                    r = 215 + (n >> 5) - 8;
                    g = 195 + (n >> 5) - 8;
                    b = 155 + (n >> 5) - 4;
                    // Rim shadow
                    if (x == 38 || x == 43) { r -= 30; g -= 25; b -= 18; }
                    a = 0xFF;

                } else if (flame) {
                    // Orange-yellow flame, brighter at tip
                    int n = hash(x, y, 917);
                    r = 255;
                    g = flameTip ? 220 : (140 + (n >> 4) - 8);
                    b = flameTip ?  60 : 10;
                    a = 0xFF;
                }

                t[y * TEX_SIZE + x] = (r << 24) | (g << 16) | (b << 8) | a;
            }
        }
        return t;
    }
}
