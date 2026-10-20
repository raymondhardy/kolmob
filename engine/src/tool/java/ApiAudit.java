import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/**
 * Lists java.* / javax.* methods and fields that the (remapped) core calls but that do not exist in
 * Android's platform API (android.jar). Those are runtime NoSuchMethodErrors waiting to happen, and
 * the desktop JVM cannot reveal them. Usage: ApiAudit core.jar android.jar
 */
public class ApiAudit {
  record Cls(String sup, List<String> ifaces, Set<String> members) {}

  static final Map<String, Cls> android = new HashMap<>();

  public static void main(String[] a) throws Exception {
    try (JarFile jf = new JarFile(a[1])) {
      for (var e = jf.entries(); e.hasMoreElements(); ) {
        JarEntry je = e.nextElement();
        if (!je.getName().endsWith(".class")) continue;
        ClassReader cr = new ClassReader(jf.getInputStream(je));
        Set<String> members = new HashSet<>();
        cr.accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(int ac, String n, String d, String s, String[] x) {
                members.add("m:" + n + d);
                return null;
              }

              @Override
              public FieldVisitor visitField(int ac, String n, String d, String s, Object v) {
                members.add("f:" + n);
                return null;
              }
            },
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        android.put(cr.getClassName(), new Cls(cr.getSuperName(), Arrays.asList(cr.getInterfaces()), members));
      }
    }
    Map<String, Set<String>> missing = M;
    Set<String> missingClasses = MC;
    try (JarFile jf = new JarFile(a[0])) {
      for (var e = jf.entries(); e.hasMoreElements(); ) {
        JarEntry je = e.nextElement();
        if (!je.getName().endsWith(".class")) continue;
        ClassReader cr = new ClassReader(jf.getInputStream(je));
        String user = cr.getClassName();
        cr.accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(int ac, String n, String d, String s, String[] x) {
                return new MethodVisitor(Opcodes.ASM9) {
                  @Override
                  public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                    check(user, owner, "m:" + name + desc, name + desc);
                  }

                  @Override
                  public void visitFieldInsn(int op, String owner, String name, String desc) {
                    check(user, owner, "f:" + name, name);
                  }
                };
              }
            },
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      }
    }
    System.out.println("== java.*/javax.* classes referenced but absent from android.jar ==");
    missingClasses.forEach(System.out::println);
    System.out.println("== members referenced but absent from android.jar (callers in parentheses) ==");
    missing.forEach((k, v) -> System.out.println(k + "   (" + v.size() + (v.size() <= 3 ? ": " + String.join(", ", v) : " classes") + ")"));
    System.out.println("TOTAL missing members: " + missing.size() + ", missing classes: " + missingClasses.size());

    // (helper state captured via static fields)
  }

  static final Map<String, Set<String>> MISSING_REF = null;

  static void check(String user, String owner, String key, String pretty) {
    if (owner.startsWith("[")) return;
    if (!(owner.startsWith("java/") || owner.startsWith("javax/"))) return;
    if (owner.startsWith("javax/swing") || owner.startsWith("java/awt") ) return;
    results(user, owner, key, pretty);
  }

  static Map<String, Set<String>> missingRef() { return null; }

  static final Map<String, Set<String>> M = new TreeMap<>();
  static final Set<String> MC = new TreeSet<>();

  static void results(String user, String owner, String key, String pretty) {
    Cls c = android.get(owner);
    if (c == null) {
      MC.add(owner.replace('/', '.'));
      return;
    }
    if (!resolves(owner, key, new HashSet<>())) {
      M.computeIfAbsent(owner.replace('/', '.') + "." + pretty, k -> new TreeSet<>()).add(user.substring(user.lastIndexOf('/') + 1));
    }
  }

  static boolean resolves(String owner, String key, Set<String> seen) {
    if (owner == null || !seen.add(owner)) return false;
    Cls c = android.get(owner);
    if (c == null) return owner.equals("java/lang/Object") ;
    if (c.members().contains(key)) return true;
    if (resolves(c.sup(), key, seen)) return true;
    for (String i : c.ifaces()) if (resolves(i, key, seen)) return true;
    return false;
  }
}
