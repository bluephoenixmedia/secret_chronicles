package com.iangame.world;

/**
 * Holds the 2D tile grid that defines the game world.
 *
 * <p>Grid values:
 * <ul>
 *   <li>0 — empty / walkable</li>
 *   <li>1 — red brick (outer walls &amp; structural separators)</li>
 *   <li>2 — grey stone</li>
 *   <li>3 — green moss</li>
 *   <li>4 — white marble</li>
 *   <li>5 — dark wood (door frames)</li>
 *   <li>6 — terracotta</li>
 * </ul>
 *
 * <p>The outer border must always be walled (non-zero) to prevent rays from
 * escaping the map.
 */
public class GameMap {

    // ── Default map (36×36) ───────────────────────────────────────────────────
    //
    // Nine rooms of three different sizes in a 3×3 grid, connected by 2-tile-wide
    // corridors.  Structural gaps between rooms are filled with type-1 brick.
    //
    // Column bands  │  Row bands
    //  band 0: c1-8  (w=8, interior 6)    band 0: r1-8  (h=8, interior 6)
    //  gap:    c9-12                       gap:    r9-12
    //  band 1: c13-24 (w=12, interior 10) band 1: r13-22 (h=10, interior 8)
    //  gap:    c25-27                      gap:    r23-25
    //  band 2: c28-34 (w=7,  interior 5)  band 2: r26-34 (h=9,  interior 7)
    //
    // Room colours:
    //  (0,0) grey(2)   (0,1) terracotta(6)  (0,2) moss(3)
    //  (1,0) white(4)  (1,1) terracotta(6)  (1,2) grey(2)
    //  (2,0) moss(3)   (2,1) white(4)       (2,2) terracotta(6)
    //
    // H-corridors at rows 4-5, 17-18, 30-31.
    // V-corridors at cols 4-5, 18-19, 31-32.
    // Door frames (type 5) are the wall tiles immediately flanking each opening.
    // Player spawns at (2.5, 2.5) — interior of room (0,0).

    private static final int[][] DEFAULT_MAP = {
        // row 0 — outer border
        {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1},
        // row 1 — top walls: grey(c1-8) | terracotta(c13-24) | moss(c28-34)
        {1,2,2,2,2,2,2,2,2,1,1,1,1,6,6,6,6,6,6,6,6,6,6,6,6,1,1,1,3,3,3,3,3,3,3,1},
        // row 2 — interior (no door frames yet)
        {1,2,0,0,0,0,0,0,2,1,1,1,1,6,0,0,0,0,0,0,0,0,0,0,6,1,1,1,3,0,0,0,0,0,3,1},
        // row 3 — interior + door frames flanking H-corridor (rows 4-5)
        //         c8, c13, c24, c28 → type 5
        {1,2,0,0,0,0,0,0,5,1,1,1,1,5,0,0,0,0,0,0,0,0,0,0,5,1,1,1,5,0,0,0,0,0,3,1},
        // rows 4-5 — H-corridor through row band 0
        {1,2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,3,1},
        {1,2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,3,1},
        // row 6 — interior + door frames flanking H-corridor (rows 4-5)
        {1,2,0,0,0,0,0,0,5,1,1,1,1,5,0,0,0,0,0,0,0,0,0,0,5,1,1,1,5,0,0,0,0,0,3,1},
        // row 7 — interior
        {1,2,0,0,0,0,0,0,2,1,1,1,1,6,0,0,0,0,0,0,0,0,0,0,6,1,1,1,3,0,0,0,0,0,3,1},
        // row 8 — bottom walls + V-corridor doors (cols 4-5, 18-19, 31-32)
        //         door frames at c3,c6 | c17,c20 | c30,c33
        {1,2,2,5,0,0,5,2,2,1,1,1,1,6,6,6,6,5,0,0,5,6,6,6,6,1,1,1,3,3,5,0,0,5,3,1},
        // rows 9-12 — V-corridor band; passage at cols 4-5, 18-19, 31-32
        {1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1},
        {1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1},
        {1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1},
        {1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1},
        // row 13 — top walls + V-corridor doors + door frames
        //          white(c1-8) | terracotta(c13-24) | grey(c28-34)
        {1,4,4,5,0,0,5,4,4,1,1,1,1,6,6,6,6,5,0,0,5,6,6,6,6,1,1,1,2,2,5,0,0,5,2,1},
        // rows 14-15 — interior
        {1,4,0,0,0,0,0,0,4,1,1,1,1,6,0,0,0,0,0,0,0,0,0,0,6,1,1,1,2,0,0,0,0,0,2,1},
        {1,4,0,0,0,0,0,0,4,1,1,1,1,6,0,0,0,0,0,0,0,0,0,0,6,1,1,1,2,0,0,0,0,0,2,1},
        // row 16 — interior + door frames flanking H-corridor (rows 17-18)
        {1,4,0,0,0,0,0,0,5,1,1,1,1,5,0,0,0,0,0,0,0,0,0,0,5,1,1,1,5,0,0,0,0,0,2,1},
        // rows 17-18 — H-corridor through row band 1
        {1,4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,2,1},
        {1,4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,2,1},
        // row 19 — interior + door frames flanking H-corridor (rows 17-18)
        {1,4,0,0,0,0,0,0,5,1,1,1,1,5,0,0,0,0,0,0,0,0,0,0,5,1,1,1,5,0,0,0,0,0,2,1},
        // rows 20-21 — interior
        {1,4,0,0,0,0,0,0,4,1,1,1,1,6,0,0,0,0,0,0,0,0,0,0,6,1,1,1,2,0,0,0,0,0,2,1},
        {1,4,0,0,0,0,0,0,4,1,1,1,1,6,0,0,0,0,0,0,0,0,0,0,6,1,1,1,2,0,0,0,0,0,2,1},
        // row 22 — bottom walls + V-corridor doors + door frames
        {1,4,4,5,0,0,5,4,4,1,1,1,1,6,6,6,6,5,0,0,5,6,6,6,6,1,1,1,2,2,5,0,0,5,2,1},
        // rows 23-25 — V-corridor band
        {1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1},
        {1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1},
        {1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,1,1,1,1},
        // row 26 — top walls + V-corridor doors + door frames
        //          moss(c1-8) | white(c13-24) | terracotta(c28-34)
        {1,3,3,5,0,0,5,3,3,1,1,1,1,4,4,4,4,5,0,0,5,4,4,4,4,1,1,1,6,6,5,0,0,5,6,1},
        // rows 27-28 — interior
        {1,3,0,0,0,0,0,0,3,1,1,1,1,4,0,0,0,0,0,0,0,0,0,0,4,1,1,1,6,0,0,0,0,0,6,1},
        {1,3,0,0,0,0,0,0,3,1,1,1,1,4,0,0,0,0,0,0,0,0,0,0,4,1,1,1,6,0,0,0,0,0,6,1},
        // row 29 — interior + door frames flanking H-corridor (rows 30-31)
        {1,3,0,0,0,0,0,0,5,1,1,1,1,5,0,0,0,0,0,0,0,0,0,0,5,1,1,1,5,0,0,0,0,0,6,1},
        // rows 30-31 — H-corridor through row band 2
        {1,3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,6,1},
        {1,3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,6,1},
        // row 32 — interior + door frames flanking H-corridor (rows 30-31)
        {1,3,0,0,0,0,0,0,5,1,1,1,1,5,0,0,0,0,0,0,0,0,0,0,5,1,1,1,5,0,0,0,0,0,6,1},
        // row 33 — interior
        {1,3,0,0,0,0,0,0,3,1,1,1,1,4,0,0,0,0,0,0,0,0,0,0,4,1,1,1,6,0,0,0,0,0,6,1},
        // row 34 — bottom walls
        {1,3,3,3,3,3,3,3,3,1,1,1,1,4,4,4,4,4,4,4,4,4,4,4,4,1,1,1,6,6,6,6,6,6,6,1},
        // row 35 — outer border
        {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1},
    };

    // ── Wall colour table (RGBA8888) ──────────────────────────────────────────
    // Index matches the grid value (0 = unused placeholder).

    private static final int[] WALL_COLOURS = {
        0x000000FF,   // 0 - empty       (unused)
        0xAA2222FF,   // 1 - red brick   (outer walls & structural separators)
        0x888888FF,   // 2 - grey stone
        0x226622FF,   // 3 - green moss
        0xDDDDDDFF,   // 4 - white marble
        0x5C3317FF,   // 5 - dark wood   (door frames)
        0xBB5533FF,   // 6 - terracotta
    };

    // ── Fields ────────────────────────────────────────────────────────────────

    private final int[][] grid;
    public  final int width;
    public  final int height;

    // ── Constructors ──────────────────────────────────────────────────────────

    public GameMap() {
        this(DEFAULT_MAP);
    }

    public GameMap(int[][] grid) {
        this.grid   = grid;
        this.height = grid.length;
        this.width  = grid.length > 0 ? grid[0].length : 0;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Returns the tile type at (col, row), or -1 if out of bounds. */
    public int getCell(int col, int row) {
        if (col < 0 || col >= width || row < 0 || row >= height) return -1;
        return grid[row][col];
    }

    /** Returns true when the given world-space tile is a solid wall. */
    public boolean isWall(int col, int row) {
        return getCell(col, row) > 0;
    }

    /**
     * Returns the RGBA8888 base colour for a wall type.
     * Darker (shadowed) variants are computed in the renderer.
     */
    public int getWallColour(int wallType) {
        if (wallType <= 0 || wallType >= WALL_COLOURS.length) return 0xFF00FFFF;
        return WALL_COLOURS[wallType];
    }

    /** Exposes the raw grid for minimap rendering. */
    public int[][] getGrid() {
        return grid;
    }
}
