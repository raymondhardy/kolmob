import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/**
 * Generates Android shims for the Swing/AWT classes the (remapped) core references.
 *
 * <p>ART rejects (hard-fails) any class that calls a method which does not exist on an existing
 * class, while references to completely missing classes only fail lazily when executed. So every
 * shim must contain exactly the members the core refers to. This tool scans all member references
 * in the remapped core jar and writes shim sources with those members.
 *
 * <p>Usage: GenShims remapped-core.jar out-dir hand-written-src-dir
 */
public class GenShims {
  static final String[] SHIM_PREFIXES = {
    "javax/swing/", "com/kolmob/awt/", "javax/imageio/", "javax/accessibility/", "javax/print/", "javax/sound/"
  };

  record Ref(String name, String desc, boolean isStatic, boolean isField) {}

  static boolean isShim(String owner) {
    for (String p : SHIM_PREFIXES) if (owner.startsWith(p)) return true;
    return false;
  }

  static String real(String internal) {
    return internal.startsWith("com/kolmob/awt/") ? "java/awt/" + internal.substring(15) : internal;
  }

  static final Map<String, Set<Ref>> refs = new TreeMap<>();
  static final Map<String, String> superOf = new HashMap<>();
  static final Set<String> coreClasses = new HashSet<>();
  static final Map<String, Set<String>> declared = new HashMap<>();
  static final Set<String> typesNeeded = new TreeSet<>();
  static final Set<String> memberLess = new TreeSet<>();
  static final Set<String> handSet = new HashSet<>();

  public static void main(String[] a) throws Exception {
    Path jar = Paths.get(a[0]), out = Paths.get(a[1]), hand = Paths.get(a[2]);
    // pass 1: hierarchy of core classes
    try (JarFile jf = new JarFile(jar.toFile())) {
      for (var e = jf.entries(); e.hasMoreElements(); ) {
        JarEntry je = e.nextElement();
        if (!je.getName().endsWith(".class")) continue;
        ClassReader cr = new ClassReader(jf.getInputStream(je));
        coreClasses.add(cr.getClassName());
        superOf.put(cr.getClassName(), cr.getSuperName());
        final Set<String> d = declared.computeIfAbsent(cr.getClassName(), k -> new HashSet<>());
        cr.accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(int ac, String n, String ds, String sg, String[] ex) {
                d.add("m:" + n + ds);
                return null;
              }

              @Override
              public FieldVisitor visitField(int ac, String n, String ds, String sg, Object v) {
                d.add("f:" + n);
                return null;
              }
            },
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      }
      // pass 2: member refs
      for (var e = jf.entries(); e.hasMoreElements(); ) {
        JarEntry je = e.nextElement();
        if (!je.getName().endsWith(".class")) continue;
        new ClassReader(jf.getInputStream(je)).accept(new Collector(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      }
    }
    // hand-written shims (skipped, but checked)
    Set<String> handWritten = new HashSet<>();
    if (Files.exists(hand))
      try (var s = Files.walk(hand)) {
        s.filter(p -> p.toString().endsWith(".java")).forEach(p -> handWritten.add(hand.relativize(p).toString().replace(".java", "").replace(File.separatorChar, '/')));
      }
    handSet.addAll(handWritten);
    // close over descriptor types and the JDK supertypes of every shim
    Deque<String> work = new ArrayDeque<>(typesNeeded);
    Set<String> done = new HashSet<>();
    while (!work.isEmpty()) {
      String t = work.pop();
      if (!done.add(t)) continue;
      refs.computeIfAbsent(t, k -> new LinkedHashSet<>());
      Class<?> rc = null;
      try {
        rc = Class.forName(real(t).replace('/', '.'), false, GenShims.class.getClassLoader());
      } catch (Throwable ignore) {
      }
      if (rc == null) continue;
      List<Class<?>> sups = new ArrayList<>();
      if (rc.getSuperclass() != null) sups.add(rc.getSuperclass());
      sups.addAll(Arrays.asList(rc.getInterfaces()));
      for (Class<?> sc : sups) {
        String in = mapName(sc.getName()).replace('.', '/');
        if (isShim(in)) work.push(in);
      }
      Class<?> outer = rc.getDeclaringClass();
      if (outer != null) {
        String in = mapName(outer.getName()).replace('.', '/');
        if (isShim(in)) work.push(in);
      }
    }
    Files.createDirectories(out);
    int n = 0;
    for (var en : refs.entrySet()) {
      String owner = en.getKey();
      if (handWritten.contains(owner)) continue;
      write(out, owner, en.getValue());
      n++;
    }
    System.out.println("generated " + n + " shim classes into " + out + " (skipped " + handWritten.size() + " hand-written)");
    // report members hand-written shims lack
    for (String h : handWritten) {
      Set<Ref> rs = refs.get(h);
      if (rs == null) continue;
      System.out.println("CHECK hand-written " + h + " must provide: " + rs);
    }
  }

  static class Collector extends ClassVisitor {
    Collector() {
      super(Opcodes.ASM9);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
      for (Type t : Type.getArgumentTypes(desc)) note(t);
      note(Type.getReturnType(desc));
      return new MethodVisitor(Opcodes.ASM9) {
        @Override
        public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
          add(owner, new Ref(n, d, op == Opcodes.INVOKESTATIC, false));
        }

        @Override
        public void visitFieldInsn(int op, String owner, String n, String d) {
          add(owner, new Ref(n, d, op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC, true));
        }

        @Override
        public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
          for (Object o : args)
            if (o instanceof Handle h)
              add(h.getOwner(), new Ref(h.getName(), h.getDesc(), h.getTag() == Opcodes.H_INVOKESTATIC, h.getTag() <= Opcodes.H_PUTSTATIC));
        }

        @Override
        public void visitTypeInsn(int op, String type) {
          if (isShim(type.replace("[", "").replace("L", ""))) noteInternal(type);
        }

        @Override
        public void visitLdcInsn(Object v) {
          if (v instanceof Type t) note(t);
        }
      };
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String sig, Object v) {
      note(Type.getType(desc));
      return null;
    }

    @Override
    public void visit(int version, int access, String name, String sig, String sup, String[] itf) {
      if (sup != null && isShim(sup)) noteInternal(sup);
      if (itf != null) for (String i : itf) if (isShim(i)) noteInternal(i);
    }
  }

  static void note(Type t) {
    if (t.getSort() == Type.ARRAY) t = t.getElementType();
    if (t.getSort() == Type.OBJECT) noteInternal(t.getInternalName());
  }

  static void noteInternal(String internal) {
    if (internal.startsWith("[")) return;
    if (isShim(internal)) typesNeeded.add(internal);
  }

  static void add(String owner, Ref r) {
    if (owner.startsWith("[")) return;
    String target = owner;
    if (!isShim(owner)) {
      // inherited call through a core/stub class: attach to the first shim ancestor
      String c = owner;
      int guard = 0;
      String key = (r.isField ? "f:" + r.name : "m:" + r.name + r.desc);
      while (c != null && !isShim(c) && coreClasses.contains(c) && guard++ < 50) {
        if (declared.getOrDefault(c, Set.of()).contains(key)) return; // declared in core, not inherited from a shim
        c = superOf.get(c);
      }
      if (c == null || !isShim(c)) return;
      target = c;
    }
    refs.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(r);
    for (Type t : Type.getArgumentTypes(r.desc.startsWith("(") ? r.desc : "()V")) note(t);
    if (r.desc.startsWith("(")) note(Type.getReturnType(r.desc)); else note(Type.getType(r.desc));
  }

  // ---- source generation ----
  static String jt(Type t) {
    return switch (t.getSort()) {
      case Type.VOID -> "void";
      case Type.BOOLEAN -> "boolean";
      case Type.CHAR -> "char";
      case Type.BYTE -> "byte";
      case Type.SHORT -> "short";
      case Type.INT -> "int";
      case Type.FLOAT -> "float";
      case Type.LONG -> "long";
      case Type.DOUBLE -> "double";
      case Type.ARRAY -> jt(t.getElementType()) + "[]".repeat(t.getDimensions());
      default -> mapName(t.getClassName()).replace('$', '.');
    };
  }

  static String dflt(Type t) {
    return switch (t.getSort()) {
      case Type.VOID -> "";
      case Type.BOOLEAN -> "return false;";
      case Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> "return 0;";
      case Type.FLOAT -> "return 0f;";
      case Type.LONG -> "return 0L;";
      case Type.DOUBLE -> "return 0d;";
      case Type.ARRAY -> "return new " + jt(t.getElementType()) + "[0]".repeat(1) + (t.getDimensions() > 1 ? "[0]".repeat(t.getDimensions() - 1) : "") + ";";
      default -> "return null;";
    };
  }

  static void write(Path out, String owner, Set<Ref> members) throws IOException {
    String pkg = owner.contains("/") ? owner.substring(0, owner.lastIndexOf('/')).replace('/', '.') : "";
    String full = owner;
    boolean nested = owner.contains("$");
    String top = nested ? owner.substring(0, owner.indexOf('$')) : owner;
    if (nested) return; // nested types are emitted inside their top-level class below
    String simple = top.substring(top.lastIndexOf('/') + 1);
    Class<?> real = null;
    try {
      real = Class.forName(real(top).replace('/', '.'), false, GenShims.class.getClassLoader());
    } catch (Throwable ignore) {
    }
    StringBuilder sb = new StringBuilder();
    sb.append("// GENERATED by engine/src/tool/java/GenShims.java: Android shim with exactly the members the core references.\n");
    sb.append("package ").append(pkg).append(";\n\n");
    emitType(sb, top, simple, real, members, refs, "", false);
    Path f = out.resolve(top + ".java");
    Files.createDirectories(f.getParent());
    Files.writeString(f, sb.toString());
  }

  static String mapName(String javaName) {
    return javaName.startsWith("java.awt.") ? "com.kolmob.awt." + javaName.substring(9) : javaName;
  }

  static boolean available(Class<?> t) {
    while (t.isArray()) t = t.getComponentType();
    if (t.isPrimitive()) return true;
    String n = mapName(t.getName());
    if (n.startsWith("java.lang.") || n.startsWith("java.io.") || n.startsWith("java.util.")) return true;
    String in = n.replace('.', '/');
    int d = in.indexOf('$');
    String top = d < 0 ? in : in.substring(0, d);
    return refs.containsKey(top) || handSet.contains(top);
  }

  static boolean usable(Class<?> c) {
    if (c == null) return false;
    String n = mapName(c.getName());
    if (n.startsWith("java.")) return n.startsWith("java.lang.") || n.startsWith("java.io.") || n.startsWith("java.util.");
    return n.startsWith("javax.swing.") || n.startsWith("javax.imageio.") || n.startsWith("javax.accessibility.") || n.startsWith("javax.print.") || n.startsWith("javax.sound.") || n.startsWith("com.kolmob.awt.");
  }

  static void emitType(StringBuilder sb, String internal, String simple, Class<?> real, Set<Ref> members, Map<String, Set<Ref>> all, String ind, boolean isNested) {
    boolean iface = real != null && real.isInterface() && !real.isAnnotation();
    boolean isEnum = real != null && real.isEnum();
    boolean isAbstract = real != null && !iface && Modifier.isAbstract(real.getModifiers());
    String decl = ind + "public " + (isNested ? "static " : "");
    if (isEnum) {
      decl += "enum " + simple;
    } else if (iface) {
      decl += "interface " + simple;
    } else {
      decl += (isAbstract ? "abstract " : "") + "class " + simple;
      if (real != null && real.getSuperclass() != null && usable(real.getSuperclass()) && real.getSuperclass() != Object.class)
        decl += " extends " + mapName(real.getSuperclass().getName()).replace('$', '.');
    }
    if (!isEnum && real != null) {
      List<String> is = new ArrayList<>();
      for (Class<?> i : real.getInterfaces()) if (usable(i)) is.add(mapName(i.getName()).replace('$', '.'));
      if (!is.isEmpty()) decl += (iface ? " extends " : " implements ") + String.join(", ", is);
    }
    sb.append(decl).append(" {\n");
    if (isEnum) {
      List<String> names = new ArrayList<>();
      for (Object c : real.getEnumConstants()) names.add(((Enum<?>) c).name());
      sb.append(ind).append("  ").append(String.join(", ", names)).append(";\n");
    }
    Set<String> seen = new HashSet<>();
    boolean hasCtor = false;
    for (Ref r : members) {
      String key = r.isField ? "f:" + r.name : r.name + r.desc.substring(0, r.desc.indexOf(')') + 1) + r.isStatic;
      if (!seen.add(key)) {
        if (!r.isField) System.out.println("WARN same-params different return type dropped: " + internal + "." + r.name + r.desc);
        continue;
      }
      if (r.isField) {
        if (isEnum) continue;
        Type t = Type.getType(r.desc);
        sb.append(ind).append("  public ").append(r.isStatic ? "static " : "").append(jt(t)).append(' ').append(r.name).append(" = ").append(t.getSort() == Type.BOOLEAN ? "false" : (t.getSort() >= Type.CHAR && t.getSort() <= Type.DOUBLE) ? "0" : "null").append(";\n");
        continue;
      }
      Type[] args = Type.getArgumentTypes(r.desc);
      StringBuilder ps = new StringBuilder();
      for (int i = 0; i < args.length; i++) ps.append(i > 0 ? ", " : "").append(jt(args[i])).append(" p").append(i);
      if (r.name.equals("<init>")) {
        if (iface || isEnum) continue;
        hasCtor = true;
        sb.append(ind).append("  public ").append(simple).append('(').append(ps).append(") { ").append(superCall(real)).append(" }\n");
        continue;
      }
      if (r.name.equals("<clinit>") || (isEnum && (r.name.equals("values") || r.name.equals("valueOf")))) continue;
      Type ret = Type.getReturnType(r.desc);
      if (iface && !r.isStatic) {
        sb.append(ind).append("  ").append(jt(ret)).append(' ').append(r.name).append('(').append(ps).append(");\n");
      } else {
        sb.append(ind).append("  public ").append(r.isStatic ? "static " : "").append(jt(ret)).append(' ').append(r.name).append('(').append(ps).append(") { ").append(dflt(ret)).append(" }\n");
      }
    }
    emitInheritedAbstracts(sb, real, seen, ind);
    if (!iface && !isEnum && !hasCtor) {
      // keep a default constructor so subclasses (stubs) can call super()
      sb.append(ind).append("  public ").append(simple).append("() { ").append(superCall(real)).append(" }\n");
    }
    // nested types referenced as Outer$Inner
    for (var en : all.entrySet()) {
      String k = en.getKey();
      if (k.startsWith(internal + "$") && k.indexOf('$', internal.length() + 1) < 0) {
        String ns = k.substring(internal.length() + 1);
        Class<?> nr = null;
        try {
          nr = Class.forName(real(k).replace('/', '.'), false, GenShims.class.getClassLoader());
        } catch (Throwable ignore) {
        }
        emitType(sb, k, ns, nr, en.getValue(), all, ind + "  ", true);
      }
    }
    sb.append(ind).append("}\n");
  }

  /** A super(...) call for supers that have no usable no-arg constructor (e.g. EventObject, Color). */
  static String superCall(Class<?> real) {
    if (real == null || real.isInterface()) return "";
    Class<?> sup = real.getSuperclass();
    if (sup == null || sup == Object.class || !usable(sup)) return "";
    String sin = mapName(sup.getName()).replace('.', '/');
    boolean generated = refs.containsKey(sin) && !handSet.contains(sin);
    if (generated) return "";
    for (var c : sup.getDeclaredConstructors()) {
      int m = c.getModifiers();
      if ((m & (Modifier.PUBLIC | Modifier.PROTECTED)) == 0) continue;
      if (c.getParameterCount() == 0) return "";
    }
    java.lang.reflect.Constructor<?> best = null;
    for (var c : sup.getDeclaredConstructors()) {
      int m = c.getModifiers();
      if ((m & (Modifier.PUBLIC | Modifier.PROTECTED)) == 0) continue;
      boolean ok = true;
      for (Class<?> pt : c.getParameterTypes()) if (!pt.isPrimitive() && pt != Object.class && pt != String.class) ok = false;
      if (ok && (best == null || c.getParameterCount() < best.getParameterCount())) best = c;
    }
    if (best == null) return "";
    StringBuilder sb = new StringBuilder("super(");
    Class<?>[] pts = best.getParameterTypes();
    for (int i = 0; i < pts.length; i++) {
      if (i > 0) sb.append(", ");
      Class<?> pt = pts[i];
      sb.append(pt == boolean.class ? "false" : pt.isPrimitive() ? "0" : "(" + pt.getName() + ") null");
    }
    return sb.append(");").toString();
  }

  /** Concrete bodies for abstract interface methods the generated class would otherwise miss. */
  static void emitInheritedAbstracts(StringBuilder sb, Class<?> real, Set<String> seen, String ind) {
    if (real == null || real.isInterface() || real.isEnum() || Modifier.isAbstract(real.getModifiers())) return;
    Set<Class<?>> all = new LinkedHashSet<>();
    for (Class<?> c = real; c != null; c = c.getSuperclass()) collectIfaces(c, all);
    for (Class<?> iface : all) {
      if (!usable(iface)) continue;
      for (var m : iface.getMethods()) {
        if (!Modifier.isAbstract(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
        boolean ok = available(m.getReturnType());
        for (Class<?> pt : m.getParameterTypes()) ok &= available(pt);
        if (!ok) continue;
        String desc = org.objectweb.asm.Type.getMethodDescriptor(m).replace("Ljava/awt/", "Lcom/kolmob/awt/");
        if (!seen.add(m.getName() + desc.substring(0, desc.indexOf(')') + 1) + false)) continue;
        // skip when a (concrete) superclass shim already provides it
        StringBuilder ps = new StringBuilder();
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) ps.append(i > 0 ? ", " : "").append(jt(org.objectweb.asm.Type.getType(pts[i]))).append(" p").append(i);
        Type ret = Type.getReturnType(desc);
        sb.append(ind).append("  public ").append(jt(ret)).append(' ').append(m.getName()).append('(').append(ps).append(") { ").append(dflt(ret)).append(" }\n");
      }
    }
  }

  static void collectIfaces(Class<?> c, Set<Class<?>> out) {
    for (Class<?> i : c.getInterfaces()) {
      if (out.add(i)) collectIfaces(i, out);
    }
  }
}
