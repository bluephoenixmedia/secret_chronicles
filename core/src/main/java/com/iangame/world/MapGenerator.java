package com.iangame.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Procedurally generates a random dungeon map on each call to {@link #generate}.
 *
 * <p>Layout: a numRows × numCols grid of rooms connected by single-tile-wide
 * corridors through fixed-width gaps.  Every room has one H-corridor (horizontal
 * passage shared by all rooms in the same row) and one V-corridor (shared by all
 * rooms in the same column), so the map is always fully connected.
 *
 * <p>The spawn room is always grid position (row=0, col=0) and is kept clear of
 * tables and cabinets.
 */
public class MapGenerator {

    /** Output bundle returned by {@link #generate}. */
    public static class GeneratedMap {
        public final int[][]         grid;
        public final float[][]       lights;        // {x, y, intensity, radius} — one per room
        public final float[][]       tables;        // {x, y}
        public final float[][]       cabinetBoxes;  // {cx, cy, hw, hd, frontNx}
        public final double          spawnX;
        public final double          spawnY;
        /** Room bounds in light-index order: {colStart, rowStart, width, height}. */
        public final int[][]         rooms;
        /** Keys (col*10000+row) of doors that are permanently locked. */
        public final java.util.Set<Integer> lockedDoorKeys;
        /** Indices into cabinetBoxes that contain a key the player can pick up. */
        public final java.util.Set<Integer> cabinetKeyIndices;
        /** Number of outer-margin tiles on each side (border + hallway depth). */
        public final int outerMargin;
        /**
         * Each row is {doorCol, doorRow, endCol, endRow} for an exterior hallway.
         * When the player reaches (endCol, endRow) the door should auto-lock.
         */
        public final int[][] hallwayEndpoints;

        GeneratedMap(int[][] grid, float[][] lights, float[][] tables,
                     float[][] cabinetBoxes, double spawnX, double spawnY, int[][] rooms,
                     java.util.Set<Integer> lockedDoorKeys,
                     java.util.Set<Integer> cabinetKeyIndices, int outerMargin,
                     int[][] hallwayEndpoints) {
            this.grid              = grid;
            this.lights            = lights;
            this.tables            = tables;
            this.cabinetBoxes      = cabinetBoxes;
            this.spawnX            = spawnX;
            this.spawnY            = spawnY;
            this.rooms             = rooms;
            this.lockedDoorKeys    = lockedDoorKeys;
            this.cabinetKeyIndices = cabinetKeyIndices;
            this.outerMargin       = outerMargin;
            this.hallwayEndpoints  = hallwayEndpoints;
        }
    }

    private static final int[]   WALL_TYPES  = { 2, 3, 4, 6 };
    private static final float[] LIGHT_RADII = { 4.5f, 5.0f, 5.5f, 6.0f, 6.5f };

    /** Generates a new random map using the supplied RNG. */
    public static GeneratedMap generate(Random rng) {

        // ── 1. Grid dimensions (total rooms kept in [9, 16]) ──────────────────
        int numCols, numRows;
        do {
            numCols = 2 + rng.nextInt(3);   // 2–4 columns
            numRows = 2 + rng.nextInt(3);   // 2–4 rows
        } while (numCols * numRows < 9);    // max is 4×4=16, so no upper check needed

        // ── 2. Room sizes (includes walls; interior = size − 2) ────────────────
        int[] roomW = new int[numCols];
        int[] roomH = new int[numRows];
        for (int c = 0; c < numCols; c++) roomW[c] = 7 + rng.nextInt(7);  // 7–13
        for (int r = 0; r < numRows; r++) roomH[r] = 7 + rng.nextInt(7);  // 7–13

        // ── 3. Gap sizes (odd so the door lands at the exact centre) ───────────
        int[] gapW = new int[numCols - 1];
        int[] gapH = new int[numRows - 1];
        for (int i = 0; i < gapW.length; i++) gapW[i] = 3 + rng.nextInt(2) * 2;  // 3 or 5
        for (int i = 0; i < gapH.length; i++) gapH[i] = 3 + rng.nextInt(2) * 2;  // 3 or 5

        // Outer margin: 1 border tile + (OUTER-1) hallway tiles on every side.
        // A large value makes exterior hallways appear to stretch to infinity.
        final int OUTER = 32;

        // ── 4. Map dimensions ──────────────────────────────────────────────────
        int mapW = 2 * OUTER;  // OUTER-wide margins on both sides
        for (int w : roomW) mapW += w;
        for (int g : gapW)  mapW += g;

        int mapH = 2 * OUTER;
        for (int h : roomH) mapH += h;
        for (int g : gapH)  mapH += g;

        // ── 5. Room/gap start positions ────────────────────────────────────────
        int[] colStart = new int[numCols];
        int[] rowStart = new int[numRows];
        colStart[0] = OUTER;
        for (int c = 1; c < numCols; c++)
            colStart[c] = colStart[c - 1] + roomW[c - 1] + gapW[c - 1];
        rowStart[0] = OUTER;
        for (int r = 1; r < numRows; r++)
            rowStart[r] = rowStart[r - 1] + roomH[r - 1] + gapH[r - 1];

        // ── 6. Corridor centre positions ───────────────────────────────────────
        int[] hRow = new int[numRows];   // H-corridor map row for each room row
        int[] vCol = new int[numCols];   // V-corridor map col for each room col
        for (int r = 0; r < numRows; r++) hRow[r] = rowStart[r] + roomH[r] / 2;
        for (int c = 0; c < numCols; c++) vCol[c] = colStart[c] + roomW[c] / 2;

        // ── 7. Wall colours (random per room) ──────────────────────────────────
        int[][] wt = new int[numRows][numCols];
        for (int r = 0; r < numRows; r++)
            for (int c = 0; c < numCols; c++)
                wt[r][c] = WALL_TYPES[rng.nextInt(WALL_TYPES.length)];

        // ── 8. Initialise grid (all solid) ─────────────────────────────────────
        int[][] grid = new int[mapH][mapW];
        for (int[] row : grid) java.util.Arrays.fill(row, 1);

        // ── 9. Carve rooms ─────────────────────────────────────────────────────
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                int cs = colStart[c], rs = rowStart[r];
                int w  = roomW[c],    h  = roomH[r];
                int wc = wt[r][c];

                // Room walls
                for (int row = rs; row < rs + h; row++)
                    for (int col = cs; col < cs + w; col++)
                        grid[row][col] = wc;

                // Interior (empty)
                for (int row = rs + 1; row < rs + h - 1; row++)
                    for (int col = cs + 1; col < cs + w - 1; col++)
                        grid[row][col] = 0;

                // Windows on outer perimeter walls (2 per exposed wall face)
                if (r == 0) {
                    addWindow(grid, rs,         cs + w / 3);
                    addWindow(grid, rs,         cs + 2 * w / 3);
                }
                if (r == numRows - 1) {
                    addWindow(grid, rs + h - 1, cs + w / 3);
                    addWindow(grid, rs + h - 1, cs + 2 * w / 3);
                }
                if (c == 0) {
                    addWindow(grid, rs + h / 3,     cs);
                    addWindow(grid, rs + 2 * h / 3, cs);
                }
                if (c == numCols - 1) {
                    addWindow(grid, rs + h / 3,     cs + w - 1);
                    addWindow(grid, rs + 2 * h / 3, cs + w - 1);
                }
            }
        }

        // ── 10. Carve H-corridor gaps (horizontal connections) ─────────────────
        for (int gi = 0; gi < gapW.length; gi++) {
            int gStart = colStart[gi] + roomW[gi];   // first gap column
            int gEnd   = colStart[gi + 1];           // first column of right room
            int doorC  = (gStart + gEnd) / 2;

            for (int r = 0; r < numRows; r++) {
                int cr = hRow[r];
                for (int col = gStart; col < gEnd; col++) grid[cr][col] = 0;
                grid[cr][doorC] = 7;

                int lw = gStart - 1, rw = gEnd;
                grid[cr][lw] = 0;
                grid[cr][rw] = 0;
                setFrame(grid, cr - 1, lw, mapH, mapW);
                setFrame(grid, cr + 1, lw, mapH, mapW);
                setFrame(grid, cr - 1, rw, mapH, mapW);
                setFrame(grid, cr + 1, rw, mapH, mapW);
            }
        }

        // ── 11. Carve V-corridor gaps (vertical connections) ───────────────────
        for (int gi = 0; gi < gapH.length; gi++) {
            int gStart = rowStart[gi] + roomH[gi];   // first gap row
            int gEnd   = rowStart[gi + 1];           // first row of lower room
            int doorR  = (gStart + gEnd) / 2;

            for (int c = 0; c < numCols; c++) {
                int cc = vCol[c];
                for (int row = gStart; row < gEnd; row++) grid[row][cc] = 0;
                grid[doorR][cc] = 7;

                int tw = gStart - 1, bw = gEnd;
                grid[tw][cc] = 0;
                grid[bw][cc] = 0;
                setFrame(grid, tw, cc - 1, mapH, mapW);
                setFrame(grid, tw, cc + 1, mapH, mapW);
                setFrame(grid, bw, cc - 1, mapH, mapW);
                setFrame(grid, bw, cc + 1, mapH, mapW);
            }
        }

        // ── 12. Lock outer border solid ────────────────────────────────────────
        for (int c = 0; c < mapW; c++) { grid[0][c] = 1; grid[mapH - 1][c] = 1; }
        for (int r = 0; r < mapH; r++) { grid[r][0] = 1; grid[r][mapW - 1] = 1; }

        // ── 12.5. Emergency exits on exterior-facing room walls ───────────────
        // One per exposed exterior side, at the horizontal/vertical centre of the
        // room nearest the map midpoint.  Only placed if the cell is a plain wall.
        placeExit(grid, rowStart[0],                              vCol[numCols / 2]);             // top
        placeExit(grid, rowStart[numRows-1] + roomH[numRows-1]-1, vCol[numCols / 2]);             // bottom
        placeExit(grid, hRow[numRows / 2],                        colStart[0]);                   // left
        placeExit(grid, hRow[numRows / 2],                        colStart[numCols-1] + roomW[numCols-1]-1); // right

        // ── 12.6. Exterior doors — 20% chance per every-other perimeter room ──
        // When a door is placed, a matching hallway is carved outward using the
        // room's wall texture so it looks like a continuation of the room.
        // The spawn room (col 0, row 0) always gets a guaranteed hallway on its
        // top wall so the player can explore one immediately after spawning.
        List<float[]> cabList          = new ArrayList<>();
        List<float[]> hallwayLights    = new ArrayList<>();
        List<int[]>   hallwayEndpointList = new ArrayList<>();
        {
            int dr = rowStart[0], dc = vCol[0];
            if (addExteriorDoor(grid, dr, dc))
                carveExteriorHallway(grid, dr, dc, -1, 0, OUTER, wt[0][0], hallwayLights, hallwayEndpointList, cabList, rng);
        }
        for (int c = 0; c < numCols; c += 2) {          // top — even columns
            int dr = rowStart[0], dc = vCol[c];
            if (rng.nextFloat() < 0.20f && addExteriorDoor(grid, dr, dc))
                carveExteriorHallway(grid, dr, dc, -1, 0, OUTER, wt[0][c], hallwayLights, hallwayEndpointList, cabList, rng);
        }
        for (int c = 1; c < numCols; c += 2) {          // bottom — odd columns
            int dr = rowStart[numRows - 1] + roomH[numRows - 1] - 1, dc = vCol[c];
            if (rng.nextFloat() < 0.20f && addExteriorDoor(grid, dr, dc))
                carveExteriorHallway(grid, dr, dc, 1, 0, OUTER, wt[numRows - 1][c], hallwayLights, hallwayEndpointList, cabList, rng);
        }
        for (int r = 0; r < numRows; r += 2) {          // left — even rows
            int dr = hRow[r], dc = colStart[0];
            if (rng.nextFloat() < 0.20f && addExteriorDoor(grid, dr, dc))
                carveExteriorHallway(grid, dr, dc, 0, -1, OUTER, wt[r][0], hallwayLights, hallwayEndpointList, cabList, rng);
        }
        for (int r = 1; r < numRows; r += 2) {          // right — odd rows
            int dr = hRow[r], dc = colStart[numCols - 1] + roomW[numCols - 1] - 1;
            if (rng.nextFloat() < 0.20f && addExteriorDoor(grid, dr, dc))
                carveExteriorHallway(grid, dr, dc, 0, 1, OUTER, wt[r][numCols - 1], hallwayLights, hallwayEndpointList, cabList, rng);
        }

        // ── 13. Room metadata + lights ─────────────────────────────────────────
        int      numRooms = numRows * numCols;
        int[][]  rooms    = new int[numRooms][4];
        // Room lights + hallway lights merged into one array.
        float[][] lights  = new float[numRooms + hallwayLights.size()][4];

        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                int idx = r * numCols + c;
                rooms[idx][0]  = colStart[c];
                rooms[idx][1]  = rowStart[r];
                rooms[idx][2]  = roomW[c];
                rooms[idx][3]  = roomH[r];
                lights[idx][0] = colStart[c] + roomW[c] / 2.0f;
                lights[idx][1] = rowStart[r] + roomH[r] / 2.0f;
                lights[idx][2] = 1.0f;
                lights[idx][3] = LIGHT_RADII[rng.nextInt(LIGHT_RADII.length)];
            }
        }
        for (int i = 0; i < hallwayLights.size(); i++) {
            lights[numRooms + i] = hallwayLights.get(i);
        }

        // ── 14. Tables (2–3 per room; spawn room skipped) ─────────────────────
        List<float[]> tableList = new ArrayList<>();
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                if (r == 0 && c == 0) continue;
                int cs = colStart[c], rs = rowStart[r];
                int w  = roomW[c],    h  = roomH[r];
                float x0 = cs + 1.8f, x1 = cs + w - 1.8f;
                float y0 = rs + 1.8f, y1 = rs + h - 1.8f;
                if (x1 <= x0 || y1 <= y0) continue;
                for (int t = 0, n = 2 + rng.nextInt(2); t < n; t++)
                    tableList.add(new float[]{
                        x0 + rng.nextFloat() * (x1 - x0),
                        y0 + rng.nextFloat() * (y1 - y0) });
            }
        }

        // ── 15. Cabinets ──────────────────────────────────────────────────────
        // Declared before step 12.6 call sites that also populate it; see declaration above.

        // Spawn-room cabinets (added first so they occupy indices 0..3).
        // Each one contains a key — marked in step 18.
        {
            int cs = colStart[0], rs = rowStart[0];
            int w  = roomW[0],    h  = roomH[0];
            float lx = cs + 1.15f, rx = cs + w - 1.15f;
            cabList.add(new float[]{ lx, rs + h * 0.25f, 0.15f, 0.35f,  1f });
            cabList.add(new float[]{ lx, rs + h * 0.75f, 0.15f, 0.35f,  1f });
            cabList.add(new float[]{ rx, rs + h * 0.35f, 0.15f, 0.35f, -1f });
            cabList.add(new float[]{ rx, rs + h * 0.65f, 0.15f, 0.35f, -1f });
        }
        final int SPAWN_CAB_COUNT = 4;

        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                if (r == 0 && c == 0) continue;
                int cs = colStart[c], rs = rowStart[r];
                int w  = roomW[c],    h  = roomH[r];
                float lx  = cs + 1.15f,      rx  = cs + w - 1.15f;
                float my1 = rs + h * 0.35f,  my2 = rs + h * 0.65f;
                cabList.add(new float[]{ lx,  my1, 0.15f, 0.35f,  1f });
                cabList.add(new float[]{ rx,  my2, 0.15f, 0.35f, -1f });
            }
        }

        // ── 16. Spawn position (centre of room [0][0]) ────────────────────────
        double spawnX = colStart[0] + roomW[0] / 2.0;
        double spawnY = rowStart[0] + roomH[0] / 2.0;

        // ── 17. Locked rooms — ~1/3 of non-spawn rooms are locked ─────────────
        // A door is locked when either of its two adjacent rooms is locked.
        boolean[] lockedRoom = new boolean[numRooms];
        for (int idx = 1; idx < numRooms; idx++)
            lockedRoom[idx] = rng.nextInt(3) == 0;

        java.util.Set<Integer> lockedDoorKeys = new java.util.HashSet<>();
        // H-corridor doors (connect room-col gi ↔ gi+1, same row r)
        // Doors touching the spawn room (index 0) are never locked.
        for (int gi = 0; gi < gapW.length; gi++) {
            int doorC = (colStart[gi] + roomW[gi] + colStart[gi + 1]) / 2;
            for (int r = 0; r < numRows; r++) {
                int left = r * numCols + gi, right = r * numCols + gi + 1;
                if (left == 0 || right == 0) continue;          // spawn-room door
                if (lockedRoom[left] || lockedRoom[right])
                    lockedDoorKeys.add(doorC * 10000 + hRow[r]);
            }
        }
        // V-corridor doors (connect room-row gi ↔ gi+1, same col c)
        for (int gi = 0; gi < gapH.length; gi++) {
            int doorR = (rowStart[gi] + roomH[gi] + rowStart[gi + 1]) / 2;
            for (int c = 0; c < numCols; c++) {
                int top = gi * numCols + c, bot = (gi + 1) * numCols + c;
                if (top == 0 || bot == 0) continue;             // spawn-room door
                if (lockedRoom[top] || lockedRoom[bot])
                    lockedDoorKeys.add(vCol[c] * 10000 + doorR);
            }
        }

        // ── 18. Assign keys — 2 of the spawn-room cabinets chosen at random ──
        java.util.Set<Integer> cabinetKeyIndices = new java.util.HashSet<>();
        java.util.List<Integer> spawnCabOrder = new java.util.ArrayList<>();
        for (int i = 0; i < SPAWN_CAB_COUNT; i++) spawnCabOrder.add(i);
        java.util.Collections.shuffle(spawnCabOrder, rng);
        cabinetKeyIndices.add(spawnCabOrder.get(0));
        cabinetKeyIndices.add(spawnCabOrder.get(1));

        return new GeneratedMap(grid, lights,
                tableList.toArray(new float[0][]),
                cabList.toArray(new float[0][]),
                spawnX, spawnY, rooms, lockedDoorKeys, cabinetKeyIndices, OUTER,
                hallwayEndpointList.toArray(new int[0][]));
    }

    /** Places a door tile (type 7) on an exterior wall if the cell is a plain solid wall.
     *  Returns true if a door was actually placed. */
    private static boolean addExteriorDoor(int[][] grid, int row, int col) {
        if (row > 0 && row < grid.length - 1 && col > 0 && col < grid[0].length - 1) {
            int v = grid[row][col];
            if (v != 0 && v != 7 && v != 8 && v != 5 && v != 9) {
                grid[row][col] = 7;
                return true;
            }
        }
        return false;
    }

    /**
     * Carves a hallway outward from an exterior door.
     * The centre tiles become floor (0); the perpendicular side tiles use the
     * adjacent room's wall type so the hallway matches the room's texture.
     * A light is emitted into {@code outLights} every 5 tiles along the hallway.
     * The last carved floor tile is recorded in {@code outEndpoints} as
     * {doorCol, doorRow, endCol, endRow} so IanGame can lock the door when the
     * player reaches the far end.
     *
     * @param dirR         row step direction (-1 = up, +1 = down, 0 = horizontal)
     * @param dirC         col step direction (-1 = left, +1 = right, 0 = vertical)
     * @param depth        number of tiles to carve (should equal OUTER)
     * @param outLights    list to append hallway light entries {x, y, intensity, radius}
     * @param outEndpoints list to append {doorCol, doorRow, endCol, endRow}
     */
    private static void carveExteriorHallway(int[][] grid, int doorRow, int doorCol,
                                             int dirR, int dirC, int depth, int wallType,
                                             List<float[]> outLights, List<int[]> outEndpoints,
                                             List<float[]> cabList, Random rng) {
        final int LIGHT_SPACING    = 5;
        final int SIDE_ROOM_START  = 6;   // first side room at this depth
        final int SIDE_ROOM_STEP   = 8;   // then every 8 tiles
        int perpR1 =  dirC, perpC1 = -dirR;   // one perpendicular axis
        int perpR2 = -dirC, perpC2 =  dirR;   // other perpendicular axis
        int lastR = doorRow, lastC = doorCol;
        int sideRoomCount = 0;
        for (int i = 1; i < depth; i++) {
            int r = doorRow + dirR * i;
            int c = doorCol + dirC * i;
            if (r <= 0 || r >= grid.length - 1 || c <= 0 || c >= grid[0].length - 1) break;
            grid[r][c] = 0; // centre = walkable floor
            lastR = r;
            lastC = c;
            int r1 = r + perpR1, c1 = c + perpC1;
            int r2 = r + perpR2, c2 = c + perpC2;
            if (r1 > 0 && r1 < grid.length - 1 && c1 > 0 && c1 < grid[0].length - 1
                    && grid[r1][c1] != 0 && grid[r1][c1] != 7)
                grid[r1][c1] = wallType;
            if (r2 > 0 && r2 < grid.length - 1 && c2 > 0 && c2 < grid[0].length - 1
                    && grid[r2][c2] != 0 && grid[r2][c2] != 7)
                grid[r2][c2] = wallType;
            // Place a dim light every LIGHT_SPACING tiles
            if (i % LIGHT_SPACING == 0) {
                outLights.add(new float[]{ c + 0.5f, r + 0.5f, 0.7f, 4.0f });
            }
            // Side rooms — alternate left/right wall every SIDE_ROOM_STEP tiles
            if (i >= SIDE_ROOM_START && (i - SIDE_ROOM_START) % SIDE_ROOM_STEP == 0) {
                boolean usePerp1 = (sideRoomCount % 2 == 0);
                int pr = usePerp1 ? perpR1 : perpR2;
                int pc = usePerp1 ? perpC1 : perpC2;
                carveSideRoom(grid, r, c, pr, pc, wallType, outLights, cabList, rng);
                sideRoomCount++;
            }
        }
        outEndpoints.add(new int[]{ doorCol, doorRow, lastC, lastR });
    }

    /**
     * Carves a small room branching off the hallway wall at (hallR, hallC)
     * in perpendicular direction (perpR, perpC).  The room uses the same wall
     * texture as the originating room so it looks like a continuation of it.
     */
    private static void carveSideRoom(int[][] grid, int hallR, int hallC,
                                      int perpR, int perpC, int wallType,
                                      List<float[]> outLights,
                                      List<float[]> cabList, Random rng) {
        // Room layout in the depth (perpendicular) direction:
        //   d=1             entrance row: door at w=0, wallType at |w|>0
        //   d=2..DEPTH+1    interior:     floor at |w|<HALF_W, wall at |w|==HALF_W
        //   d=DEPTH+2       far wall:     wallType for all w
        final int ROOM_DEPTH  = 4;
        final int ROOM_HALF_W = 2;
        int rows = grid.length, cols = grid[0].length;

        // Width axis runs along the hallway direction.
        // (perpR, perpC) is perpendicular to hallway; (perpC, -perpR) is the hallway axis.
        int wdR = perpC, wdC = -perpR;

        // Bounds-check every tile we plan to touch before modifying anything.
        for (int d = 1; d <= ROOM_DEPTH + 2; d++) {
            for (int w = -ROOM_HALF_W; w <= ROOM_HALF_W; w++) {
                int rr = hallR + perpR * d + wdR * w;
                int rc = hallC + perpC * d + wdC * w;
                if (rr <= 0 || rr >= rows - 1 || rc <= 0 || rc >= cols - 1) return;
            }
        }

        // ── d=1: entrance row ────────────────────────────────────────────────
        grid[hallR + perpR][hallC + perpC] = 7;  // door at centre
        for (int w = -ROOM_HALF_W; w <= ROOM_HALF_W; w++) {
            if (w == 0) continue;  // already a door
            int rr = hallR + perpR + wdR * w;
            int rc = hallC + perpC + wdC * w;
            if (grid[rr][rc] != 0 && grid[rr][rc] != 7) grid[rr][rc] = wallType;
        }

        // ── d=2..ROOM_DEPTH+1: interior ──────────────────────────────────────
        for (int d = 2; d <= ROOM_DEPTH + 1; d++) {
            for (int w = -ROOM_HALF_W; w <= ROOM_HALF_W; w++) {
                int rr = hallR + perpR * d + wdR * w;
                int rc = hallC + perpC * d + wdC * w;
                if (Math.abs(w) == ROOM_HALF_W) {
                    if (grid[rr][rc] != 0 && grid[rr][rc] != 7) grid[rr][rc] = wallType;
                } else {
                    grid[rr][rc] = 0;  // floor
                }
            }
        }

        // ── d=ROOM_DEPTH+2: far wall — door at centre, walls at sides ────────
        int farDoorR = hallR + perpR * (ROOM_DEPTH + 2);
        int farDoorC = hallC + perpC * (ROOM_DEPTH + 2);
        for (int w = -ROOM_HALF_W; w <= ROOM_HALF_W; w++) {
            int rr = farDoorR + wdR * w;
            int rc = farDoorC + wdC * w;
            if (w == 0) {
                grid[rr][rc] = 7;   // door
            } else {
                if (grid[rr][rc] != 0 && grid[rr][rc] != 7) grid[rr][rc] = wallType;
            }
        }

        // ── Corridor beyond the far door ──────────────────────────────────────
        // Carves in the same (perpR, perpC) direction until it either connects
        // to an existing floor tile or reaches the map border.
        int lastCorR = farDoorR, lastCorC = farDoorC;
        boolean connected = false;
        for (int j = 1; j < rows + cols; j++) {
            int r = farDoorR + perpR * j;
            int c = farDoorC + perpC * j;
            if (r <= 0 || r >= rows - 1 || c <= 0 || c >= cols - 1) break;
            if (grid[r][c] == 0) { connected = true; break; }  // hit existing floor
            grid[r][c] = 0;
            lastCorR = r; lastCorC = c;
            int r1 = r + wdR, c1 = c + wdC;
            int r2 = r - wdR, c2 = c - wdC;
            if (r1 > 0 && r1 < rows-1 && c1 > 0 && c1 < cols-1 && grid[r1][c1] != 0 && grid[r1][c1] != 7)
                grid[r1][c1] = wallType;
            if (r2 > 0 && r2 < rows-1 && c2 > 0 && c2 < cols-1 && grid[r2][c2] != 0 && grid[r2][c2] != 7)
                grid[r2][c2] = wallType;
            if (j % 5 == 0)
                outLights.add(new float[]{ c + 0.5f, r + 0.5f, 0.7f, 3.5f });
        }
        // End-cap wall only when the corridor didn't connect to anything
        if (!connected) {
            int ecR = lastCorR + perpR, ecC = lastCorC + perpC;
            for (int w = -1; w <= 1; w++) {
                int rr = ecR + wdR * w, rc = ecC + wdC * w;
                if (rr > 0 && rr < rows-1 && rc > 0 && rc < cols-1 && grid[rr][rc] != 0 && grid[rr][rc] != 7)
                    grid[rr][rc] = wallType;
            }
        }

        // Light in the centre of the room.
        int midD = (ROOM_DEPTH / 2) + 2;
        outLights.add(new float[]{
            (hallC + perpC * midD) + 0.5f,
            (hallR + perpR * midD) + 0.5f,
            0.85f, 3.5f
        });

        // Cabinets — placed against the side walls ~60% of the time.
        // Width axis (wdR, wdC) runs along the hallway direction.
        // frontNx is based on which column side the cabinet is on.
        if (rng.nextFloat() < 0.60f) {
            float inset = ROOM_HALF_W - 1.15f;   // 0.85 tiles from side wall
            // Cabinet on the positive-width side
            float c1x = hallC + perpC * midD + wdC * inset + 0.5f;
            float c1y = hallR + perpR * midD + wdR * inset + 0.5f;
            float nx1  = (wdC != 0) ? -Math.signum(wdC) : ((perpC != 0) ? -perpC : 1f);
            cabList.add(new float[]{ c1x, c1y, 0.15f, 0.35f, nx1 });
            // Second cabinet on the negative-width side (~50% of the time)
            if (rng.nextFloat() < 0.50f) {
                float c2x = hallC + perpC * midD - wdC * inset + 0.5f;
                float c2y = hallR + perpR * midD - wdR * inset + 0.5f;
                float nx2  = (wdC != 0) ? Math.signum(wdC) : ((perpC != 0) ? perpC : -1f);
                cabList.add(new float[]{ c2x, c2y, 0.15f, 0.35f, nx2 });
            }
        }
    }

    /** Places an emergency exit tile (type 9) if the cell is a plain solid wall. */
    private static void placeExit(int[][] grid, int row, int col) {
        if (row > 0 && row < grid.length - 1 && col > 0 && col < grid[0].length - 1) {
            int v = grid[row][col];
            if (v != 0 && v != 7 && v != 8 && v != 5 && v != 9)
                grid[row][col] = 9;
        }
    }

    /** Places a window tile if the cell is a plain wall tile (not floor, door, or frame). */
    private static void addWindow(int[][] grid, int row, int col) {
        if (row > 0 && row < grid.length - 1 && col > 0 && col < grid[0].length - 1
                && grid[row][col] != 0 && grid[row][col] != 7 && grid[row][col] != 5)
            grid[row][col] = 8;
    }

    /** Replaces a solid wall tile with a door-frame tile (type 5). */
    private static void setFrame(int[][] grid, int row, int col, int mapH, int mapW) {
        if (row > 0 && row < mapH - 1 && col > 0 && col < mapW - 1
                && grid[row][col] != 0 && grid[row][col] != 7 && grid[row][col] != 5)
            grid[row][col] = 5;
    }
}
