package dev.posedmcp.xposed;

/**
 * A class loader that asks several others, in order, and takes the first answer.
 *
 * <p>Needed in system_server, where the loader the framework hands this module is
 * not the one that process's own code was loaded with. Measured on the OnePlus
 * with LSPosed 1.10.2: through the framework's loader,
 * {@code Class.forName("com.android.server.am.ActivityManagerService")} fails —
 * even though that class is in {@code services.jar} and running in this very
 * process — while the thread's context loader resolves it, along with everything
 * in {@code oplus-services.jar}. Anything aimed at OPPO's own AMS extensions has
 * to be resolved through that one, and the framework's loader is still the right
 * one for everything this module already hooks.
 *
 * <p>First match wins, so anything both loaders can see is still loaded by the
 * framework's — this only adds a way to find the rest. Nothing is cached here:
 * a class loader that remembered a miss could pin one failed lookup for the life
 * of the process, which on system_server means until reboot.
 */
final class LayeredLoader extends ClassLoader {

    private final ClassLoader[] loaders;

    LayeredLoader(ClassLoader... loaders) {
        // Parent null on purpose: the parents are the array, and the default
        // parent (the boot loader) would silently answer before them.
        super(null);
        this.loaders = loaders;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                Class<?> found = loader.loadClass(name);
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            } catch (ClassNotFoundException ignored) {
                // Try the next: seeing different things is the whole point.
            }
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("posedmcp-layered[");
        for (int i = 0; i < loaders.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(loaders[i]);
        }
        return sb.append(']').toString();
    }
}
