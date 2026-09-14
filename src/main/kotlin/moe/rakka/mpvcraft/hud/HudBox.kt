package moe.rakka.mpvcraft.hud

/**
 * A rectangle on screen the user can drag and resize. Coordinates are in real
 * window pixels, not GUI-scaled units, so the HUD stays put when the player
 * changes their GUI scale.
 */
class HudBox(
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
) {
    fun contains(mx: Float, my: Float): Boolean =
        mx >= x && mx <= x + width && my >= y && my <= y + height

    /** Keeps the box on screen, allowing a little overhang so it stays grabbable. */
    fun clampTo(screenWidth: Int, screenHeight: Int) {
        x = x.coerceIn(-width / 2, screenWidth - width / 2)
        y = y.coerceIn(0, (screenHeight - height / 2).coerceAtLeast(0))
    }
}
