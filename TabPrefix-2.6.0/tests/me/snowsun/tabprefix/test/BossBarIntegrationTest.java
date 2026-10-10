package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.presentation.display.*;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Independent native API model checks ownership, personalized values and lifecycle behavior. */
public final class BossBarIntegrationTest {
  private static int checks;

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  private static void reject(Runnable action, String message) {
    try {
      action.run();
      throw new AssertionError("Accepted: " + message);
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  private static JsonObject bar(String id, String mode) {
    JsonObject b = new JsonObject();
    b.addProperty("id", id);
    b.addProperty("progressMode", mode);
    JsonObject text = new JsonObject();
    text.addProperty("text", "<gold>{player}</gold> · {progress}% · {remaining}s");
    b.add("line", text);
    return b;
  }

  private static DisplayDesign design(JsonObject... bars) {
    JsonObject d = DisplayDesign.defaults(false).json();
    d.addProperty("bossBarsEnabled", true);
    JsonArray list = new JsonArray();
    for (JsonObject bar : bars) list.add(bar);
    d.add("bossBars", list);
    return new DisplayDesign(d);
  }

  private static final class Recipient {
    final UUID id = UUID.randomUUID();
    final String name;
    String world = "world";
    boolean permission;
    final Player api;

    Recipient(String name) {
      this.name = name;
      World w =
          (World)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {World.class},
                  (p, m, a) -> m.getName().equals("getName") ? world : zero(m.getReturnType()));
      api =
          (Player)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {Player.class},
                  (p, m, a) -> {
                    switch (m.getName()) {
                      case "getName":
                        return name;
                      case "getUniqueId":
                        return id;
                      case "getWorld":
                        return w;
                      case "hasPermission":
                        return permission;
                      case "equals":
                        return p == a[0];
                      case "hashCode":
                        return System.identityHashCode(p);
                      default:
                        return zero(m.getReturnType());
                    }
                  });
    }
  }

  public static final class NativeBar {
    public String title;
    public BarColor color;
    public BarStyle style;
    public double progress = 1;
    public boolean visible = true;
    public int updates, attaches, removes;
    public final Set<BarFlag> flags = EnumSet.noneOf(BarFlag.class);
    public final List<Player> players = new ArrayList<>();
    public final BossBar api;

    public NativeBar(String title, BarColor color, BarStyle style) {
      this.title = title;
      this.color = color;
      this.style = style;
      api =
          (BossBar)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {BossBar.class},
                  (p, m, a) -> {
                    switch (m.getName()) {
                      case "getTitle":
                        return this.title;
                      case "getColor":
                        return this.color;
                      case "getStyle":
                        return this.style;
                      case "getProgress":
                        return progress;
                      case "isVisible":
                        return visible;
                      case "getPlayers":
                        return new ArrayList<>(players);
                      case "hasFlag":
                        return flags.contains(a[0]);
                      case "addFlag":
                        flags.add((BarFlag) a[0]);
                        updates++;
                        return null;
                      case "removeFlag":
                        flags.remove(a[0]);
                        updates++;
                        return null;
                      case "setTitle":
                        this.title = (String) a[0];
                        updates++;
                        return null;
                      case "setColor":
                        this.color = (BarColor) a[0];
                        updates++;
                        return null;
                      case "setStyle":
                        this.style = (BarStyle) a[0];
                        updates++;
                        return null;
                      case "setProgress":
                        double value = (double) a[0];
                        if (!Double.isFinite(value) || value < 0 || value > 1)
                          throw new AssertionError("Invalid native fill");
                        progress = value;
                        updates++;
                        return null;
                      case "setVisible":
                        visible = (boolean) a[0];
                        updates++;
                        return null;
                      case "addPlayer":
                        if (!players.contains(a[0])) players.add((Player) a[0]);
                        attaches++;
                        return null;
                      case "removeAll":
                        players.clear();
                        removes++;
                        return null;
                      default:
                        return zero(m.getReturnType());
                    }
                  });
    }
  }

  private static Object zero(Class<?> t) {
    if (!t.isPrimitive()) return null;
    if (t == boolean.class) return false;
    if (t == int.class) return 0;
    if (t == long.class) return 0L;
    if (t == double.class) return 0d;
    if (t == float.class) return 0f;
    return null;
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.load(root.resolve("resources/config.yml").toFile());
    TextRenderer renderer =
        new TextRenderer(new PluginSettings(yaml, root.resolve(".build/test-data/boss-model")));
    BiFunction<DisplayDesign.Line, Map<String, String>, String> text =
        (line, vars) -> renderer.legacy(renderer.display(line.text, vars));
    PlayerDisplaySettings prefs = new PlayerDisplaySettings(new JsonObject());
    JsonObject legacy = DisplayDesign.defaults(false).json();
    legacy.remove("bossBars");
    legacy.remove("bossBarsEnabled");
    legacy.remove("bossBarMode");
    legacy.remove("bossBarRotateTicks");
    check(
        !new DisplayDesign(legacy).bossBarsEnabled && new DisplayDesign(legacy).bossBars.isEmpty(),
        "2.1 documents load without activating new bars");
    check(prefs.bossBars, "Old personal settings keep default boss-bar visibility");
    JsonObject manual = bar("welcome", "STATIC");
    manual.addProperty("progress", 37.5);
    DisplayDesign d = design(manual, bar("health", "HEALTH"));
    check(
        d.json().toString().equals(new DisplayDesign(d.json()).json().toString()),
        "Full boss document round trip");
    try {
      d.bossBars.clear();
      throw new AssertionError("Mutable bars");
    } catch (UnsupportedOperationException expected) {
      checks++;
    }
    JsonObject duplicate = d.json();
    duplicate.getAsJsonArray("bossBars").add(manual);
    reject(() -> new DisplayDesign(duplicate), "Duplicate IDs");
    JsonObject tooMany = d.json();
    for (int i = 0; i < 7; i++) tooMany.getAsJsonArray("bossBars").add(bar("extra" + i, "STATIC"));
    reject(() -> new DisplayDesign(tooMany), "More than 8 bars");
    for (String key : Arrays.asList("color", "style", "progressMode", "progressSource")) {
      JsonObject bad = bar("bad", "STATIC");
      bad.addProperty(key, "UNKNOWN");
      reject(() -> design(bad), key);
    }
    for (String key :
        Arrays.asList("enabled", "loop", "hideAfter", "darkenSky", "playMusic", "createFog")) {
      JsonObject bad = bar("bad", "STATIC");
      bad.addProperty(key, "true");
      reject(() -> design(bad), "Strict " + key);
    }
    for (double n : new double[] {-1, 101, Double.NaN, Double.POSITIVE_INFINITY}) {
      JsonObject bad = bar("bad", "STATIC");
      bad.addProperty("progress", n);
      reject(() -> design(bad), "Invalid percent");
    }
    JsonObject badMax = bar("bad", "CUSTOM");
    badMax.addProperty("progressMax", 0);
    reject(() -> design(badMax), "Zero denominator");
    JsonObject badTime = bar("bad", "DRAIN");
    badTime.addProperty("durationTicks", 19);
    reject(() -> design(badTime), "Timer duration floor");
    JsonObject badPermission = bar("bad", "STATIC");
    badPermission.addProperty("permission", "not a permission");
    reject(() -> design(badPermission), "Invalid permission");
    JsonObject badWorld = bar("bad", "STATIC");
    JsonArray worlds = new JsonArray();
    worlds.add("world\nother");
    badWorld.add("worlds", worlds);
    reject(() -> design(badWorld), "Control characters in worlds");
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("player", "Alice");
    vars.put("health", "7.5");
    vars.put("max_health", "30");
    vars.put("food", "12");
    vars.put("experience", "42.5");
    vars.put("online", "20");
    vars.put("max", "100");
    vars.put("level", "250");
    check(BossBarProgress.value(d.bossBars.get(0), vars, 0) == .375, "Fractional manual fill");
    check(
        BossBarProgress.value(d.bossBars.get(1), vars, 0) == .25,
        "Health scales with actual max health");
    check(
        BossBarProgress.value(design(bar("food", "FOOD")).bossBars.get(0), vars, 0) == .6,
        "Food fill");
    check(
        BossBarProgress.value(design(bar("xp", "EXPERIENCE")).bossBars.get(0), vars, 0) == .425,
        "Experience fraction");
    check(
        BossBarProgress.value(design(bar("online", "ONLINE")).bossBars.get(0), vars, 0) == .2,
        "Online capacity fraction");
    JsonObject custom = bar("custom", "CUSTOM");
    custom.addProperty("progressSource", "level");
    custom.addProperty("progressMax", 100);
    check(
        BossBarProgress.value(design(custom).bossBars.get(0), vars, 0) == 1,
        "Custom over-capacity fill clamps safely");
    vars.put("level", "-10");
    check(
        BossBarProgress.value(design(custom).bossBars.get(0), vars, 0) == 0,
        "Negative custom fill clamps safely");
    vars.put("level", "NaN");
    check(
        BossBarProgress.value(design(custom).bossBars.get(0), vars, 0) == 0,
        "Non-finite dynamic value is safe");
    for (String mode : Arrays.asList("FILL", "DRAIN", "PULSE")) {
      JsonObject timed = bar("timer", mode);
      timed.addProperty("durationTicks", 40);
      timed.addProperty("loop", false);
      DisplayDesign.Boss b = design(timed).bossBars.get(0);
      check(
          BossBarProgress.value(b, vars, 20) == (mode.equals("PULSE") ? 1 : .5),
          mode + " midpoint");
      check(
          BossBarProgress.remaining(b, 0) == 2 && BossBarProgress.remaining(b, 40) == 0,
          mode + " countdown endpoints");
      check(
          BossBarProgress.value(b, vars, 100) == (mode.equals("FILL") ? 1 : 0),
          mode + " finite end state");
    }
    Recipient alice = new Recipient("Alice"), bob = new Recipient("Bob");
    List<NativeBar> created = new ArrayList<>();
    Server server =
        (Server)
            Proxy.newProxyInstance(
                BossBarIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (p, m, a) -> {
                  if (m.getName().equals("createBossBar")) {
                    NativeBar nativeBar =
                        new NativeBar((String) a[0], (BarColor) a[1], (BarStyle) a[2]);
                    created.add(nativeBar);
                    return nativeBar.api;
                  }
                  return zero(m.getReturnType());
                });
    NativeBar foreign = new NativeBar("Other plugin", BarColor.BLUE, BarStyle.SOLID);
    foreign.api.addPlayer(alice.api);
    BossBarView view = new BossBarView(server);
    view.render(alice.api, d, prefs, vars, 100, 1, text);
    Map<String, String> bobVars = new LinkedHashMap<>(vars);
    bobVars.put("player", "Bob");
    bobVars.put("health", "30");
    view.render(bob.api, d, prefs, bobVars, 100, 1, text);
    check(view.count() == 4 && created.size() == 4, "Separate native bars per recipient");
    check(
        created.get(0).title.contains("Alice") && created.get(2).title.contains("Bob"),
        "Personalized titles");
    check(created.get(1).progress == .25 && created.get(3).progress == 1, "Personalized health");
    int updates = created.get(0).updates, attaches = created.get(0).attaches;
    view.render(alice.api, d, prefs, vars, 101, 1, text);
    check(
        created.size() == 4
            && created.get(0).updates == updates
            && created.get(0).attaches == attaches,
        "Unchanged display sends no native updates");
    JsonObject options = d.json();
    JsonObject first = options.getAsJsonArray("bossBars").get(0).getAsJsonObject();
    first.addProperty("color", "GREEN");
    first.addProperty("style", "SEGMENTED_12");
    first.addProperty("darkenSky", true);
    first.addProperty("playMusic", true);
    first.addProperty("createFog", true);
    view.render(alice.api, new DisplayDesign(options), prefs, vars, 102, 2, text);
    check(
        created.size() == 4
            && created.get(0).color == BarColor.GREEN
            && created.get(0).style == BarStyle.SEGMENTED_12,
        "Live color and style reuse the native bar");
    check(created.get(0).flags.size() == 3, "All native effects applied");
    first.addProperty("playMusic", false);
    view.render(alice.api, new DisplayDesign(options), prefs, vars, 103, 3, text);
    check(
        !created.get(0).flags.contains(BarFlag.PLAY_BOSS_MUSIC) && created.get(0).flags.size() == 2,
        "Effects can be removed independently");
    JsonArray swapped = new JsonArray();
    swapped.add(options.getAsJsonArray("bossBars").get(1));
    swapped.add(first);
    options.add("bossBars", swapped);
    view.render(alice.api, new DisplayDesign(options), prefs, vars, 104, 4, text);
    check(
        created.size() == 4 && created.get(0).removes == 1 && created.get(1).removes == 1,
        "Reorder reattaches owned bars without replacing them");
    check(
        foreign.players.contains(alice.api) && foreign.removes == 0,
        "Foreign bars survive reorder");
    JsonObject restricted = bar("private", "STATIC");
    restricted.addProperty("permission", "example.vip");
    JsonArray allowedWorlds = new JsonArray();
    allowedWorlds.add("world");
    restricted.add("worlds", allowedWorlds);
    DisplayDesign restrictedDesign = design(restricted);
    check(
        BossBarView.frames(alice.api, restrictedDesign, prefs, vars, 0, text)
            .get(0)
            .reason
            .equals("PERMISSION"),
        "Permission restrictions in preview");
    alice.permission = true;
    view.render(alice.api, restrictedDesign, prefs, vars, 105, 5, text);
    check(view.count() == 3, "Authorized player gets restricted bar");
    alice.world = "world_nether";
    view.render(alice.api, restrictedDesign, prefs, vars, 106, 5, text);
    check(view.count() == 2, "World change removes ineligible bar");
    alice.world = "world";
    alice.permission = false;
    view.render(alice.api, restrictedDesign, prefs, vars, 107, 5, text);
    check(view.count() == 2, "Revoked permission does not retain a bar");
    JsonObject rotating = d.json();
    rotating.addProperty("bossBarMode", "ROTATE");
    rotating.addProperty("bossBarRotateTicks", 20);
    DisplayDesign rd = new DisplayDesign(rotating);
    List<BossBarView.Frame> frames = BossBarView.frames(alice.api, rd, prefs, vars, 20, text);
    check(!frames.get(0).active && frames.get(1).active, "Rotation follows configured interval");
    JsonObject noAnimation = new JsonObject();
    noAnimation.addProperty("animations", false);
    PlayerDisplaySettings still = new PlayerDisplaySettings(noAnimation);
    frames = BossBarView.frames(alice.api, rd, still, vars, 20, text);
    check(frames.get(0).active && !frames.get(1).active, "Animation opt-out freezes rotation");
    JsonObject timer = bar("timer", "DRAIN");
    timer.addProperty("durationTicks", 20);
    timer.addProperty("loop", false);
    timer.addProperty("hideAfter", true);
    DisplayDesign countdown = design(timer);
    view.render(alice.api, countdown, prefs, vars, 200, 6, text);
    check(view.count() == 3, "Timer begins on activation");
    view.render(alice.api, countdown, prefs, vars, 220, 6, text);
    check(view.count() == 2, "Finished timer hides");
    view.render(alice.api, countdown, prefs, vars, 260, 6, text);
    check(view.count() == 2, "Hidden finite timer does not restart itself");
    view.render(alice.api, countdown, prefs, vars, 300, 7, text);
    check(view.count() == 3, "Applying a new revision restarts the timer");
    frames = BossBarView.frames(alice.api, countdown, still, vars, 200, text);
    check(
        frames.get(0).active && frames.get(0).progress == 1,
        "Animation opt-out freezes timer at start");
    JsonObject hidden = new JsonObject();
    hidden.addProperty("bossBars", false);
    view.render(alice.api, countdown, new PlayerDisplaySettings(hidden), vars, 301, 7, text);
    check(view.count() == 2, "Personal opt-out affects only one recipient");
    view.forget(bob.id);
    check(view.count() == 0, "Quit removes recipient-owned bars");
    view.render(alice.api, d, prefs, vars, 400, 8, text);
    view.close();
    check(
        view.count() == 0 && created.stream().allMatch(b -> b.players.isEmpty()),
        "Shutdown clears all owned bars");
    check(
        foreign.players.contains(alice.api) && foreign.removes == 0,
        "Foreign bars survive shutdown");
    System.out.println(
        "PASS: "
            + checks
            + " boss bar checks: backwards compatibility, strict options, fill/timers, native"
            + " updates, rotation, conditions and cleanup.");
  }
}
