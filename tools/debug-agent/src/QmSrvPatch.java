import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Makes "server.example.com" resolve through its SRV record (_minecraft._tcp.server.example.com) on Android.
 *
 * Minecraft looks SRV records up through JNDI's DNS provider, which finds its name servers in /etc/resolv.conf. Android has
 * no such file, so the lookup silently fails and the game connects to the default port 25565 even when the server lives on
 * another one. This agent swaps the body of ServerRedirectHandler.createDnsSrvRedirectHandler() for a handler
 * ([QmSrvRedirect]) that asks real DNS servers itself (the ones the launcher passes in: "dns=a,b").
 */
final class QmSrvPatch {
    static final String TARGET = "net/minecraft/client/multiplayer/resolver/ServerRedirectHandler";

    static void install(String args, Instrumentation inst) {
        QmSrvRedirect.configure(args);
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                if (!TARGET.equals(name)) return null;
                try {
                    byte[] out = patch(bytes);
                    System.out.println("[qm-srv] SRV lookup patched in " + name);
                    return out;
                } catch (Throwable t) {
                    System.out.println("[qm-srv] patch failed: " + t);
                    return null;
                }
            }
        });
    }

    static byte[] patch(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        ClassDesc handler = ClassDesc.of(TARGET.replace('/', '.'));
        ClassDesc redirect = ClassDesc.of("QmSrvRedirect");
        CodeTransform drop = (builder, element) -> { };
        CodeTransform replaceBody = drop.andThen(CodeTransform.endHandler(b -> b
                .ldc(handler)
                .invokestatic(redirect, "create", MethodTypeDesc.of(ClassDesc.of("java.lang.Object"), ClassDesc.of("java.lang.Class")))
                .checkcast(handler)
                .areturn()));
        return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
                m -> m.methodName().equalsString("createDnsSrvRedirectHandler"), replaceBody));
    }
}
