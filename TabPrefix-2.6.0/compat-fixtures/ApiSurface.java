import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;

/** Derived test API surfaces, explicitly not an actual 1.12 or 26.x server/API distribution. */
public final class ApiSurface {
  public static void main(String[] args) throws Exception {
    if (args[0].equals("derive")) derive(Paths.get(args[1]), Paths.get(args[2]), args[3]);
    else verify(Paths.get(args[1]));
  }

  private static void derive(Path input, Path output, String version) throws Exception {
    boolean old = version.equals("old");
    try (JarFile jar = new JarFile(input.toFile());
        JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry e = entries.nextElement();
        if (e.isDirectory()) continue;
        byte[] bytes;
        try (InputStream in = jar.getInputStream(e)) {
          bytes = read(in);
        }
        if (e.getName().equals("org/bukkit/entity/Player.class")
            || old && e.getName().equals("org/bukkit/scoreboard/Scoreboard.class")) {
          ClassReader r = new ClassReader(bytes);
          ClassWriter w = new ClassWriter(0);
          r.accept(
              new ClassVisitor(Opcodes.ASM9, w) {
                public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                  if (old
                      && e.getName().endsWith("Player.class")
                      && (name.equals("getPing")
                          || name.contains("PlayerListHeader")
                          || name.contains("PlayerListFooter"))) return null;
                  if (old
                      && name.equals("registerNewObjective")
                      && Type.getArgumentTypes(descriptor).length > 2) return null;
                  return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                public void visitEnd() {
                  if (!old) {
                    super.visitMethod(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                            "addResourcePack",
                            "(Ljava/util/UUID;Ljava/lang/String;[BLjava/lang/String;Z)V",
                            null,
                            null)
                        .visitEnd();
                    super.visitMethod(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                            "removeResourcePack",
                            "(Ljava/util/UUID;)V",
                            null,
                            null)
                        .visitEnd();
                  }
                  super.visitEnd();
                }
              },
              0);
          bytes = w.toByteArray();
        }
        out.putNextEntry(new JarEntry(e.getName()));
        out.write(bytes);
        out.closeEntry();
      }
    }
  }

  private static final Set<String> checked = new HashSet<>();

  private static void verify(Path plugin) throws Exception {
    try (JarFile jar = new JarFile(plugin.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry e = entries.nextElement();
        if (!e.getName().startsWith("me/snowsun/tabprefix/")
            || e.getName().contains("/internal/")
            || !e.getName().endsWith(".class")) continue;
        byte[] bytes;
        try (InputStream in = jar.getInputStream(e)) {
          bytes = read(in);
        }
        new ClassReader(bytes)
            .accept(
                new ClassVisitor(Opcodes.ASM9) {
                  public MethodVisitor visitMethod(
                      int access,
                      String name,
                      String descriptor,
                      String signature,
                      String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                      public void visitMethodInsn(
                          int opcode, String owner, String name, String desc, boolean iface) {
                        method(owner, name, desc);
                      }

                      public void visitFieldInsn(
                          int opcode, String owner, String name, String desc) {
                        field(owner, name, desc);
                      }

                      public void visitInvokeDynamicInsn(
                          String name, String desc, Handle bootstrap, Object... args) {
                        for (Object o : args)
                          if (o instanceof Handle) {
                            Handle h = (Handle) o;
                            method(h.getOwner(), h.getName(), h.getDesc());
                          }
                      }
                    };
                  }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      }
    }
    System.out.println(
        "PASS: "
            + checked.size()
            + " distinct Bukkit method/field references resolve against the derived API surface");
  }

  private static void method(String owner, String name, String desc) {
    if (!owner.startsWith("org/bukkit/")) return;
    String id = owner + "." + name + desc;
    if (!checked.add(id)) return;
    try {
      Class<?> type =
          Class.forName(owner.replace('/', '.'), false, ApiSurface.class.getClassLoader());
      Type[] arguments = Type.getArgumentTypes(desc);
      Class<?>[] params = new Class<?>[arguments.length];
      for (int i = 0; i < params.length; i++) params[i] = resolve(arguments[i]);
      if (name.equals("<init>")) {
        type.getDeclaredConstructor(params);
        return;
      }
      for (Class<?> c = type; c != null; c = c.getSuperclass())
        try {
          Method m = c.getDeclaredMethod(name, params);
          if (!Type.getMethodDescriptor(m).equals(desc))
            throw new NoSuchMethodException("return descriptor");
          return;
        } catch (NoSuchMethodException ignored) {
        }
      Method m = type.getMethod(name, params);
      if (!Type.getMethodDescriptor(m).equals(desc))
        throw new NoSuchMethodException("return descriptor");
    } catch (Exception | LinkageError e) {
      throw new AssertionError("Unresolvable Bukkit reference: " + id, e);
    }
  }

  private static void field(String owner, String name, String desc) {
    if (!owner.startsWith("org/bukkit/")) return;
    String id = owner + "." + name + desc;
    if (!checked.add(id)) return;
    try {
      Class<?> type =
          Class.forName(owner.replace('/', '.'), false, ApiSurface.class.getClassLoader());
      Field f = type.getField(name);
      if (!Type.getDescriptor(f.getType()).equals(desc))
        throw new NoSuchFieldException("descriptor");
    } catch (Exception | LinkageError e) {
      throw new AssertionError("Unresolvable Bukkit field: " + id, e);
    }
  }

  private static Class<?> resolve(Type t) throws Exception {
    switch (t.getSort()) {
      case Type.BOOLEAN:
        return boolean.class;
      case Type.BYTE:
        return byte.class;
      case Type.CHAR:
        return char.class;
      case Type.SHORT:
        return short.class;
      case Type.INT:
        return int.class;
      case Type.LONG:
        return long.class;
      case Type.FLOAT:
        return float.class;
      case Type.DOUBLE:
        return double.class;
      case Type.VOID:
        return void.class;
      default:
        return Class.forName(
            t.getSort() == Type.ARRAY ? t.getDescriptor().replace('/', '.') : t.getClassName(),
            false,
            ApiSurface.class.getClassLoader());
    }
  }

  private static byte[] read(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    return out.toByteArray();
  }
}
