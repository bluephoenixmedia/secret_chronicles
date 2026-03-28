package com.iangame.player;

/**
 * Represents the player's position, viewing direction, and camera plane.
 *
 * <p>Direction and camera plane are stored as 2D vectors (world-space XY) rather
 * than a single angle to avoid trigonometry on every ray and stay in sync with
 * the DDA algorithm used by {@link com.iangame.engine.RayCaster}.
 *
 * <p>Coordinate system: X is east, Y is south. The default spawn faces north
 * (dirX = 0, dirY = -1). The camera plane is always perpendicular to the
 * direction vector; its length controls the horizontal FOV (~66° at 0.66).
 */
public class Player {

    // ── Position ─────────────────────────────────────────────────────────────

    /** World-space X position (column). */
    public double x;
    /** World-space Y position (row). */
    public double y;

    // ── View direction (unit vector) ─────────────────────────────────────────

    public double dirX = 0.0;
    public double dirY = -1.0;   // facing north by default

    // ── Camera plane (perpendicular to dir, length = tan(FOV/2)) ─────────────

    public double planeX = 0.66;  // ~66° horizontal FOV
    public double planeY = 0.0;

    // ── Tuning ────────────────────────────────────────────────────────────────

    public double moveSpeed   = 3.5;  // world units per second
    public double rotateSpeed = 2.0;  // radians per second

    // ── Constructor ───────────────────────────────────────────────────────────

    public Player(double startX, double startY) {
        this.x = startX;
        this.y = startY;
    }

    // ── Movement helpers ──────────────────────────────────────────────────────

    /**
     * Moves the player forward or backward along the current direction.
     *
     * @param delta   +1 = forward, -1 = backward
     * @param dt      elapsed time in seconds
     * @param map     collision grid (used to block movement into walls)
     */
    public void move(double delta, double dt, int[][] map) {
        double speed = moveSpeed * delta * dt;
        double newX = x + dirX * speed;
        double newY = y + dirY * speed;

        if (map[(int) y][(int) newX] == 0) x = newX;
        if (map[(int) newY][(int) x] == 0) y = newY;
    }

    /**
     * Rotates the player (and their camera plane) by the given signed speed.
     *
     * @param delta   +1 = clockwise (right), -1 = counter-clockwise (left)
     * @param dt      elapsed time in seconds
     */
    public void rotate(double delta, double dt) {
        rotateByAngle(-rotateSpeed * delta * dt);
    }

    /**
     * Rotates the player (and their camera plane) by an exact angle in radians.
     * Negative values turn right (clockwise), positive values turn left.
     * Used for mouse-look where the raw delta is already in radians.
     *
     * @param radians  rotation angle in radians
     */
    public void rotateByAngle(double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        double oldDirX = dirX;
        dirX = dirX * cos - dirY * sin;
        dirY = oldDirX * sin + dirY * cos;

        double oldPlaneX = planeX;
        planeX = planeX * cos - planeY * sin;
        planeY = oldPlaneX * sin + planeY * cos;
    }
}
