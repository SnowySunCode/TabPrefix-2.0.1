package me.snowsun.tabprefix.infrastructure.bukkit;

import java.lang.reflect.*;
import me.snowsun.tabprefix.domain.MinecraftVersion;
import org.bukkit.scoreboard.Objective;

public final class SidebarNumbers {
  private final boolean supported;
  private String status;

  public SidebarNumbers(MinecraftVersion v) {
    supported = v.compareTo(MinecraftVersion.parse("1.20.3")) >= 0;
    status = supported ? "waiting" : "client-before-1.20.3";
  }

  public void hide(Objective objective) {
    if (!supported) return;
    ClassLoader loader = objective.getClass().getClassLoader();
    try {
      Class<?> type =
          Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat", false, loader);
      objective
          .getClass()
          .getMethod("numberFormat", type)
          .invoke(objective, type.getMethod("blank").invoke(null));
      status = "blank/Paper";
      return;
    } catch (ReflectiveOperationException | LinkageError ignored) {
    }
    try {
      Object handle = objective.getClass().getMethod("getHandle").invoke(objective);
      Class<?> blank = null;
      for (String name : new String[] {"BlankFormat", "BlankNumberFormat", "NumberFormatBlank"})
        try {
          blank = Class.forName("net.minecraft.network.chat.numbers." + name, false, loader);
          break;
        } catch (ClassNotFoundException ignored) {
        }
      if (blank == null) throw new ClassNotFoundException();
      Object value = null;
      for (Field f : blank.getDeclaredFields())
        if (Modifier.isStatic(f.getModifiers()) && blank.isAssignableFrom(f.getType())) {
          f.setAccessible(true);
          value = f.get(null);
          break;
        }
      if (value == null) throw new NoSuchFieldException();
      for (Method m : handle.getClass().getMethods())
        if (m.getReturnType() == void.class
            && m.getParameterTypes().length == 1
            && m.getParameterTypes()[0].isInstance(value)) {
          m.invoke(handle, value);
          status = "blank/native";
          return;
        }
      throw new NoSuchMethodException();
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      status = "adapter-unavailable";
    }
  }

  public String status() {
    return status;
  }
}
