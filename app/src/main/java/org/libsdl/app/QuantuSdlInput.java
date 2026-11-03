package org.libsdl.app;

/**
 * QuantuMobile launcher glue (not part of upstream SDL): lets the launcher's own touch overlay talk to SDL through
 * package-private pieces of the upstream Java layer.
 */
public final class QuantuSdlInput {
    private QuantuSdlInput() {}

    /** True while the game has captured the mouse (SDL relative mouse mode), i.e. mouse-look is active. */
    public static boolean isRelativeMouse() {
        try {
            return SDLActivity.getMotionListener().inRelativeMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Delivers typed characters the way the on-screen keyboard does (SDL text input events). */
    public static void commitText(String text) {
        SDLInputConnection.nativeCommitText(text, 1);
    }
}
