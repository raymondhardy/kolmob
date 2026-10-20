import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/**
 * Rewrites references to java/awt/** into com/kolmob/awt/** and java/net/http/** into com/kolmob/http/** in a jar. Android apps may not define
 * java.* classes, and the real java.awt does not exist there, so the core is remapped to shims.
 * Usage: RemapAwt in.jar out.jar
 */
public class RemapAwt {
  public static void main(String[] args) throws Exception {
    Remapper remapper =
        new Remapper() {
          @Override
          public String map(String name) {
            if (name.startsWith("java/awt/")) return "com/kolmob/awt/" + name.substring(9);
            if (name.startsWith("java/net/http/")) return "com/kolmob/http/" + name.substring(14);
            return name;
          }
        };
    Path in = Paths.get(args[0]), out = Paths.get(args[1]);
    Files.createDirectories(out.toAbsolutePath().getParent());
    int changed = 0, total = 0;
    try (JarFile jf = new JarFile(in.toFile());
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(out.toFile()))) {
      var entries = jf.entries();
      while (entries.hasMoreElements()) {
        JarEntry e = entries.nextElement();
        if (e.isDirectory() || e.getName().equals("META-INF/MANIFEST.MF")) continue;
        byte[] data = jf.getInputStream(e).readAllBytes();
        if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) {
          total++;
          ClassReader cr = new ClassReader(data);
          ClassWriter cw = new ClassWriter(0);
          cr.accept(new ClassRemapper(cw, remapper), 0);
          byte[] outBytes = cw.toByteArray();
          if (!java.util.Arrays.equals(outBytes, data)) changed++;
          data = outBytes;
        }
        jos.putNextEntry(new JarEntry(e.getName()));
        jos.write(data);
        jos.closeEntry();
      }
    }
    System.out.println("remapped " + changed + " of " + total + " classes -> " + out);
  }
}
