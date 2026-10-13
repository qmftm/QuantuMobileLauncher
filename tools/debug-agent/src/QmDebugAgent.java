import java.lang.instrument.Instrumentation;
import java.util.Map;

/**
 * Diagnostic java agent: prints the stack of every thread (Render thread first) a few times after start, to the game
 * log, so a black screen can be told apart from a hung render thread. Built by tools/debug-agent/build.sh.
 */
public final class QmDebugAgent {
    public static void premain(String args, Instrumentation inst) {
        Thread t = new Thread(() -> {
            long[] waits = {20000, 20000, 30000};
            for (long w : waits) {
                try { Thread.sleep(w); } catch (InterruptedException e) { return; }
                dump();
            }
        }, "qm-debug-agent");
        t.setDaemon(true);
        t.start();
    }

    private static void dump() {
        StringBuilder sb = new StringBuilder("\n[qm-debug] ===== thread dump =====\n");
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (int pass = 0; pass < 2; pass++) {
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                boolean render = e.getKey().getName().equals("Render thread");
                if ((pass == 0) != render) continue;
                sb.append("[qm-debug] \"").append(e.getKey().getName()).append("\" ").append(e.getKey().getState()).append('\n');
                StackTraceElement[] st = e.getValue();
                for (int i = 0; i < st.length && i < (render ? 40 : 12); i++) sb.append("[qm-debug]     at ").append(st[i]).append('\n');
            }
        }
        System.out.println(sb);
    }
}
