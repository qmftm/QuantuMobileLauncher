import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

/**
 * Diagnostics for "Services are unavailable" (the game cannot reach Mojang's session / discovery servers): shortly after
 * start, and again a minute later, resolves and fetches the same hosts the game needs and prints what happens, in the game
 * log. Daemon thread; never throws.
 */
final class QmNetProbe {
    private static final String[] URLS = {
            "https://discovery.minecraftservices.com/minecraft/client",
            "https://sessionserver.mojang.com/",
            "https://api.minecraftservices.com/",
            "https://api.modrinth.com/",
    };

    static void start() {
        Thread t = new Thread(() -> {
            long[] waits = {10000, 60000};
            for (long w : waits) {
                try { Thread.sleep(w); } catch (InterruptedException e) { return; }
                for (String u : URLS) probe(u);
            }
        }, "qm-net-probe");
        t.setDaemon(true);
        t.start();
    }

    private static void probe(String url) {
        StringBuilder sb = new StringBuilder("[qm-net] ").append(url);
        try {
            URL u = new URL(url);
            StringBuilder addrs = new StringBuilder();
            for (InetAddress a : InetAddress.getAllByName(u.getHost())) addrs.append(' ').append(a.getHostAddress());
            sb.append(" resolves:").append(addrs);
            long t0 = System.nanoTime();
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "QuantuMobile-probe");
            int code = c.getResponseCode();
            sb.append(" -> HTTP ").append(code).append(" in ").append((System.nanoTime() - t0) / 1000000).append(" ms");
        } catch (Throwable e) {
            sb.append(" FAILED: ").append(e);
            for (Throwable c = e.getCause(); c != null && c != c.getCause(); c = c.getCause()) sb.append(" <- ").append(c);
        }
        System.out.println(sb);
    }
}
