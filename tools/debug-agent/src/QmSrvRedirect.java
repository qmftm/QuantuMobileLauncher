import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** The replacement SRV handler (see [QmSrvPatch]): a hand-rolled DNS SRV query over UDP. */
public final class QmSrvRedirect {
    static final String FACTORY_KEY = "qm.srv.factory";

    private static volatile List<String> servers = List.of("1.1.1.1", "8.8.8.8");

    static void configure(String args) {
        if (args == null) return;
        for (String part : args.split(";")) {
            if (part.startsWith("dns=")) {
                List<String> list = new ArrayList<>();
                for (String s : part.substring(4).split(",")) if (!s.isBlank()) list.add(s.trim());
                if (!list.isEmpty()) {
                    // The launcher's own servers first, public ones as a fallback.
                    if (!list.contains("1.1.1.1")) list.add("1.1.1.1");
                    if (!list.contains("8.8.8.8")) list.add("8.8.8.8");
                    servers = list;
                }
            }
        }
    }

    /** Called from the rewritten ServerRedirectHandler.createDnsSrvRedirectHandler(). */
    public static Object create(Class<?> iface) {
        try {
            return build(iface);
        } catch (Throwable t) {
            // Never break the game's own server-address resolution: fall back to "no redirect".
            System.out.println("[qm-srv] handler failed, SRV lookups disabled: " + t);
            try { return iface.getField("EMPTY").get(null); } catch (Throwable e) { throw new IllegalStateException(e); }
        }
    }

    private static Object build(Class<?> iface) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
            if (!method.getName().equals("lookupRedirect")) {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return "QmSrvRedirect";
                }
            }
            Object addr = args[0];
            try {
                int port = (int) addr.getClass().getMethod("getPort").invoke(addr);
                if (port != 25565) return Optional.empty();
                String host = (String) addr.getClass().getMethod("getHost").invoke(addr);
                Target t = lookup(host);
                if (t == null) {
                    System.out.println("[qm-srv] " + host + ": no SRV record");
                    return Optional.empty();
                }
                System.out.println("[qm-srv] " + host + " -> " + t.host + ":" + t.port);
                return Optional.of(addr.getClass().getConstructor(String.class, int.class).newInstance(t.host, t.port));
            } catch (Throwable e) {
                System.out.println("[qm-srv] lookup failed: " + e);
                return Optional.empty();
            }
        });
    }

    static final class Target {
        final String host; final int port; final int priority; final int weight;
        Target(String host, int port, int priority, int weight) { this.host = host; this.port = port; this.priority = priority; this.weight = weight; }
    }

    static Target lookup(String host) {
        byte[] query = buildQuery("_minecraft._tcp." + host);
        for (String server : servers) {
            for (int attempt = 0; attempt < 1; attempt++) {
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setSoTimeout(1500);
                    socket.send(new DatagramPacket(query, query.length, InetAddress.getByName(server), 53));
                    byte[] buf = new byte[1500];
                    DatagramPacket reply = new DatagramPacket(buf, buf.length);
                    socket.receive(reply);
                    if (buf[0] != query[0] || buf[1] != query[1]) continue; // not our answer
                    return parse(buf, reply.getLength());
                } catch (Exception e) {
                    // try again / next server
                }
            }
        }
        return null;
    }

    static byte[] buildQuery(String name) {
        ByteBuffer b = ByteBuffer.allocate(512);
        b.putShort((short) ThreadLocalRandom.current().nextInt(0x10000));
        b.putShort((short) 0x0100); // recursion desired
        b.putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String label : name.split("\\.")) {
            byte[] l = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            b.put((byte) l.length).put(l);
        }
        b.put((byte) 0).putShort((short) 33).putShort((short) 1); // SRV, IN
        byte[] out = new byte[b.position()];
        b.flip(); b.get(out);
        return out;
    }

    /** The best SRV answer (lowest priority, then highest weight) in a DNS response, or null. */
    static Target parse(byte[] m, int len) {
        if (len < 12) return null;
        int rcode = m[3] & 0x0f;
        if (rcode != 0) return null;
        int qd = u16(m, 4), an = u16(m, 6);
        int pos = 12;
        for (int i = 0; i < qd; i++) { pos = skipName(m, pos) + 4; }
        Target best = null;
        for (int i = 0; i < an && pos < len; i++) {
            pos = skipName(m, pos);
            int type = u16(m, pos);
            int rdlen = u16(m, pos + 8);
            int rd = pos + 10;
            if (type == 33 && rdlen >= 7) {
                int prio = u16(m, rd), weight = u16(m, rd + 2), port = u16(m, rd + 4);
                String target = readName(m, rd + 6);
                if (!target.isEmpty() && port > 0 && (best == null || prio < best.priority || (prio == best.priority && weight > best.weight))) {
                    best = new Target(target, port, prio, weight);
                }
            }
            pos = rd + rdlen;
        }
        return best;
    }

    private static int u16(byte[] m, int p) { return ((m[p] & 0xff) << 8) | (m[p + 1] & 0xff); }

    private static int skipName(byte[] m, int p) {
        while (true) {
            int l = m[p] & 0xff;
            if (l == 0) return p + 1;
            if ((l & 0xc0) == 0xc0) return p + 2;
            p += l + 1;
        }
    }

    private static String readName(byte[] m, int p) {
        StringBuilder sb = new StringBuilder();
        int jumps = 0;
        while (jumps < 20) {
            int l = m[p] & 0xff;
            if (l == 0) break;
            if ((l & 0xc0) == 0xc0) { p = ((l & 0x3f) << 8) | (m[p + 1] & 0xff); jumps++; continue; }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(m, p + 1, l, java.nio.charset.StandardCharsets.US_ASCII));
            p += l + 1;
        }
        return sb.toString();
    }
}
