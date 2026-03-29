package com.iangame.engine;

import com.iangame.world.GameMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the state and animation of every interactive door in the map.
 *
 * <p>Doors are identified by their tile coordinates.  Each door is a small state
 * machine: CLOSED → OPENING → OPEN → (auto-close timer) → CLOSING → CLOSED.
 * The {@code openAmount} field (0 = fully shut, 1 = fully open) drives both the
 * DDA visibility test in {@link RayCaster} and the player collision check.
 */
public class DoorManager {

    // ── Door state ────────────────────────────────────────────────────────────

    public enum DoorState { CLOSED, OPENING, OPEN, CLOSING }

    private static final float OPEN_SPEED   = 1.5f;  // fraction / second
    private static final float CLOSE_DELAY  = 3.5f;  // seconds before auto-close

    private static final class Door {
        DoorState state      = DoorState.CLOSED;
        float     openAmount = 0f;
        float     closeTimer = 0f;
    }

    // Key: col * 10000 + row  (handles any reasonable map size)
    private final Map<Integer, Door> doors = new HashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    /** Scans {@code map} for tile type 7 and registers a door at each position. */
    public DoorManager(GameMap map) {
        for (int row = 0; row < map.height; row++) {
            for (int col = 0; col < map.width; col++) {
                if (map.getCell(col, row) == 7) {
                    doors.put(key(col, row), new Door());
                }
            }
        }
    }

    // ── Public query API ──────────────────────────────────────────────────────

    public boolean hasDoor(int col, int row) {
        return doors.containsKey(key(col, row));
    }

    /** 0 = fully closed, 1 = fully open. */
    public float getOpenAmount(int col, int row) {
        Door d = doors.get(key(col, row));
        return d == null ? 0f : d.openAmount;
    }

    /** True when the door is open enough for the player to walk through. */
    public boolean isPassable(int col, int row) {
        Door d = doors.get(key(col, row));
        return d != null && d.openAmount > 0.5f;
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    /** Toggles the door at (col, row) between opening and closing. */
    public void interact(int col, int row) {
        Door d = doors.get(key(col, row));
        if (d == null) return;
        if (d.state == DoorState.CLOSED || d.state == DoorState.CLOSING) {
            d.state = DoorState.OPENING;
        } else {
            d.state = DoorState.CLOSING;
        }
    }

    /**
     * Looks for a door within reach in front of the player and interacts with it.
     *
     * @param px    player world X (column)
     * @param py    player world Y (row)
     * @param dirX  player facing direction X
     * @param dirY  player facing direction Y
     */
    public void tryInteract(double px, double py, double dirX, double dirY) {
        for (float dist = 0.4f; dist <= 1.6f; dist += 0.4f) {
            int col = (int)(px + dirX * dist);
            int row = (int)(py + dirY * dist);
            if (hasDoor(col, row)) {
                interact(col, row);
                return;
            }
        }
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public void update(float dt) {
        for (Door d : doors.values()) {
            switch (d.state) {
                case OPENING:
                    d.openAmount = Math.min(1f, d.openAmount + OPEN_SPEED * dt);
                    if (d.openAmount >= 1f) {
                        d.state      = DoorState.OPEN;
                        d.closeTimer = CLOSE_DELAY;
                    }
                    break;
                case OPEN:
                    d.closeTimer -= dt;
                    if (d.closeTimer <= 0f) {
                        d.state = DoorState.CLOSING;
                    }
                    break;
                case CLOSING:
                    d.openAmount = Math.max(0f, d.openAmount - OPEN_SPEED * dt);
                    if (d.openAmount <= 0f) {
                        d.state = DoorState.CLOSED;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static int key(int col, int row) {
        return col * 10000 + row;
    }
}
