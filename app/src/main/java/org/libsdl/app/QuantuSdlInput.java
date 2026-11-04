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

    /**
     * Forgets the keyboard's connection to SDL's hidden text field. Without this, after the keyboard was hidden the
     * field still holds focus, so showing it again does not reconnect the keyboard and typing goes nowhere.
     * Call on the UI thread, before hiding or showing the keyboard.
     */
    public static void resetTextField() {
        SDLDummyEdit edit = SDLActivity.mTextEdit;
        if (edit == null) return;
        try {
            edit.clearFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                edit.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.restartInput(edit);
        } catch (Throwable ignored) {
            // best effort: the keyboard still opens
        }
    }
}
