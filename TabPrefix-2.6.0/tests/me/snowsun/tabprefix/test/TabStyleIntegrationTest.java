package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.luckperms.LuckPermsBridge;
import me.snowsun.tabprefix.presentation.display.TabFormatting;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Production sorting/formatting and cached LuckPerms reads against independent API fixtures. */
public final class TabStyleIntegrationTest {
  private static int checks;

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  private static void reject(Runnable work, String message) {
    try {
      work.run();
      throw new AssertionError(message);
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  private static String plain(String text) {
    return text.replaceAll("§[0-9a-fklmnorxA-FKLMNORX]", "");
  }

  private static JsonObject role(String group, String mode, String text) {
    JsonObject role = new JsonObject(), suffix = new JsonObject();
    role.addProperty("group", group);
    role.addProperty("suffixMode", mode);
    role.addProperty("nameColor", "#93efc4");
    suffix.addProperty("text", text);
    role.add("suffix", suffix);
    return role;
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              if (m.getName().equals("equals")) return p == a[0];
              if (m.getName().equals("hashCode")) return System.identityHashCode(p);
              return handler.invoke(p, m, a);
            });
  }

  private static Player player(String name, String world, UUID id) {
    World w = proxy(World.class, (p, m, a) -> m.getName().equals("getName") ? world : null);
    return proxy(
        Player.class,
        (p, m, a) -> {
          switch (m.getName()) {
            case "getName":
              return name;
            case "getUniqueId":
              return id;
            case "getWorld":
              return w;
            default:
              return null;
          }
        });
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]);
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.load(project.resolve("resources/config.yml").toFile());
    TextRenderer renderer =
        new TextRenderer(new PluginSettings(yaml, project.resolve(".build/test-data")));
    documents();
    suffixes(renderer);
    sorting();
    luckPerms();
    System.out.println(
        "PASS: "
            + checks
            + " TAB style checks: suffix modes, rich formatting, validation, all sorting modes and"
            + " cached LuckPerms metadata.");
  }

  private static void documents() {
    JsonObject old = DisplayDesign.defaults(false).json();
    for (String key :
        Arrays.asList(
            "groupStyles",
            "tabPlayerFormat",
            "tabFormatEnabled",
            "nativeSortEnabled",
            "sortReverse",
            "suffixEnabled",
            "luckSuffixFallback")) old.remove(key);
    DisplayDesign upgraded = new DisplayDesign(old);
    check(
        !upgraded.tabFormatEnabled && !upgraded.nativeSortEnabled && upgraded.groupStyles.isEmpty(),
        "older displays keep their existing ordinary TAB until enabled");
    check(
        upgraded.suffixEnabled
            && upgraded.luckSuffixFallback
            && upgraded.tabPlayerFormat.text.equals("{prefix}{display_name}{suffix}"),
        "new optional settings receive safe defaults");
    PlayerDisplaySettings prefs = new PlayerDisplaySettings(new JsonObject());
    check(prefs.suffixes && prefs.sorting, "older personal settings enable new features");
    for (String field : Arrays.asList("suffixes", "sorting")) {
      JsonObject bad = new JsonObject();
      bad.addProperty(field, "false");
      reject(() -> new PlayerDisplaySettings(bad), "personal option requires a boolean");
    }
    JsonArray roles = new JsonArray();
    roles.add(role("VIP", "CUSTOM", "★"));
    JsonObject doc = upgraded.json();
    doc.add("groupStyles", roles);
    DisplayDesign d = new DisplayDesign(doc);
    check(
        d.groupStyle("vip").group.equals("vip") && d.groupStyle("VIP") != null,
        "group lookup is normalized");
    check(
        d.json().toString().equals(new DisplayDesign(d.json()).json().toString()),
        "group style round trip retains all settings");
    roles.add(role("vip", "CUSTOM", "Other"));
    reject(() -> new DisplayDesign(doc), "duplicate normalized group styles rejected");
    for (String name : Arrays.asList("", "with space", "<red>", "../group"))
      reject(
          () -> new DisplayDesign.GroupStyle(role(name, "CUSTOM", "★")),
          "invalid group name rejected");
    JsonObject color = role("vip", "CUSTOM", "★");
    color.addProperty("nameColor", "<red>");
    reject(() -> new DisplayDesign.GroupStyle(color), "name color is typed data, never markup");
    reject(
        () -> new DisplayDesign.GroupStyle(role("vip", "OTHER", "★")),
        "unknown suffix mode rejected");
    reject(
        () -> new DisplayDesign.GroupStyle(role("vip", "CUSTOM", "bad\nline")),
        "multiline suffix rejected");
    JsonArray tooMany = new JsonArray();
    for (int i = 0; i < 65; i++) tooMany.add(role("group" + i, "NONE", ""));
    doc.add("groupStyles", tooMany);
    reject(() -> new DisplayDesign(doc), "group list bounded at 64");
    try {
      d.groupStyles.clear();
      throw new AssertionError("Mutable group styles");
    } catch (UnsupportedOperationException expected) {
      checks++;
    }
  }

  private static void suffixes(TextRenderer r) {
    DisplayDesign d = DisplayDesign.defaults(false);
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("player", "<red>literal</red>");
    vars.put("group", "vip");
    vars.put("world", "{player}");
    vars.put("prefix", "§6[VIP] ");
    DisplayDesign.GroupStyle custom =
        new DisplayDesign.GroupStyle(role("vip", "CUSTOM", "<gold>★ {group}</gold>"));
    String suffix = TabFormatting.suffix(d, custom, "§bLP", vars, r, 0, true, true);
    check(
        plain(suffix).equals(" ★ vip") && suffix.contains("§6"),
        "custom suffix preserves color and adds one separating space");
    check(
        plain(
                TabFormatting.suffix(
                    d,
                    new DisplayDesign.GroupStyle(role("vip", "CUSTOM", " <aqua>Badge</aqua>")),
                    "",
                    vars,
                    r,
                    0,
                    true,
                    true))
            .equals(" Badge"),
        "intentional leading space is preserved");
    check(
        TabFormatting.suffix(
                d,
                new DisplayDesign.GroupStyle(role("vip", "CUSTOM", "<red></red>")),
                "",
                vars,
                r,
                0,
                true,
                true)
            .isEmpty(),
        "formatting-only suffix emits no separator");
    check(
        TabFormatting.suffix(d, custom, "LP", vars, r, 0, false, true).isEmpty(),
        "personal suffix opt-out respected");
    JsonObject disabled = d.json();
    disabled.addProperty("suffixEnabled", false);
    check(
        TabFormatting.suffix(new DisplayDesign(disabled), custom, "LP", vars, r, 0, true, true)
            .isEmpty(),
        "server suffix switch respected");
    check(
        plain(TabFormatting.suffix(d, null, "§bLP", vars, r, 0, true, true)).equals(" LP"),
        "unlisted groups use cached LuckPerms suffix");
    disabled = d.json();
    disabled.addProperty("luckSuffixFallback", false);
    check(
        TabFormatting.suffix(new DisplayDesign(disabled), null, "LP", vars, r, 0, true, true)
            .isEmpty(),
        "unlisted LuckPerms suffix fallback can be disabled");
    check(
        TabFormatting.suffix(
                d,
                new DisplayDesign.GroupStyle(role("vip", "NONE", "Should not appear")),
                "LP",
                vars,
                r,
                0,
                true,
                true)
            .isEmpty(),
        "NONE hides both custom and LuckPerms suffix");
    check(
        plain(
                TabFormatting.suffix(
                    d,
                    new DisplayDesign.GroupStyle(role("vip", "LUCKPERMS", "Custom")),
                    "§aLP",
                    vars,
                    r,
                    0,
                    true,
                    true))
            .equals(" LP"),
        "explicit LuckPerms mode ignores custom text");
    JsonObject frames = role("vip", "CUSTOM", "");
    JsonObject l = frames.getAsJsonObject("suffix");
    l.addProperty("animation", "FRAMES");
    l.addProperty("speed", 2);
    JsonArray a = new JsonArray();
    a.add("<green>ONE</green>");
    a.add("<red>TWO</red>");
    l.add("frames", a);
    DisplayDesign.GroupStyle animated = new DisplayDesign.GroupStyle(frames);
    check(
        plain(TabFormatting.suffix(d, animated, "", vars, r, 2, true, true)).equals(" TWO"),
        "suffix frames use server ticks");
    check(
        plain(TabFormatting.suffix(d, animated, "", vars, r, 2, true, false)).equals(" ONE"),
        "personal animation opt-out holds first suffix frame");
    JsonObject scroll = role("vip", "CUSTOM", "<aqua>ABCDEFGHIJ</aqua>");
    l = scroll.getAsJsonObject("suffix");
    l.addProperty("animation", "SCROLL");
    l.addProperty("width", 4);
    l.addProperty("speed", 2);
    check(
        plain(
                TabFormatting.suffix(
                    d, new DisplayDesign.GroupStyle(scroll), "", vars, r, 2, true, true))
            .equals(" BCDE"),
        "scrolling suffix uses the configured window");
    vars.put("suffix", suffix);
    vars.put("display_name", TabFormatting.name(vars.get("player"), custom, r));
    String name = TabFormatting.line(d.tabPlayerFormat, vars, r, 0, true);
    check(
        plain(name).equals("[VIP] <red>literal</red> ★ vip"),
        "player format composes prefix, literal name and suffix once");
    check(
        name.contains("§x§9§3§e§f§c§4"),
        "custom RGB name color retained through typed placeholder");
    vars.put("suffix", "§b<click:run_command:'/op test'>literal</click>");
    check(
        r.plain(r.display("<white>{suffix}</white>", vars)).contains("<click:"),
        "suffix metadata cannot inject MiniMessage actions");
    check(
        r.plain(r.display("<white>{world}</white>", vars)).equals("{player}"),
        "world values do not recursively expand");
    vars.put("tab_name", "§aReady ★");
    check(
        r.legacy(r.display("{tab_name}", vars)).contains("§a"),
        "layout template accepts preformatted TAB name");
  }

  private static void sorting() {
    List<Player> players = new ArrayList<>();
    String[] names = {"Beta", "alpha", "Charlie", "delta", "Echo"},
        groups = {"vip", "admin", "default", "guest", "admin"},
        worlds = {"nether", "world", "world", "nether", "world"};
    int[] weights = {20, 80, 0, 0, 80}, pings = {90, 40, 10, 40, 40};
    Map<Player, Integer> index = new IdentityHashMap<>();
    for (int i = 0; i < names.length; i++) {
      Player p = player(names[i], worlds[i], new UUID(0, i + 1));
      players.add(p);
      index.put(p, i);
    }
    JsonObject base = DisplayDesign.defaults(false).json();
    JsonArray priority = new JsonArray();
    priority.add(role("vip", "NONE", ""));
    priority.add(role("admin", "NONE", ""));
    base.add("groupStyles", priority);
    String[] modes = {"NAME", "GROUP", "PRIORITY", "WEIGHT", "PING", "WORLD"},
        expected =
            {
              "alpha,Beta,Charlie,delta,Echo",
              "alpha,Echo,Charlie,delta,Beta",
              "Beta,alpha,Echo,Charlie,delta",
              "alpha,Echo,Beta,Charlie,delta",
              "Charlie,alpha,delta,Echo,Beta",
              "Beta,delta,alpha,Charlie,Echo"
            };
    for (int m = 0; m < modes.length; m++) {
      base.addProperty("sort", modes[m]);
      base.addProperty("sortReverse", false);
      DisplayDesign d = new DisplayDesign(base);
      List<Player> ordered = new ArrayList<>(players);
      Collections.reverse(ordered);
      ordered.sort(
          TabFormatting.order(
              d, p -> groups[index.get(p)], p -> weights[index.get(p)], p -> pings[index.get(p)]));
      check(
          join(ordered).equals(expected[m]),
          "deterministic " + modes[m] + " sorting with names for ties");
      base.addProperty("sortReverse", true);
      ordered.sort(
          TabFormatting.order(
              new DisplayDesign(base),
              p -> groups[index.get(p)],
              p -> weights[index.get(p)],
              p -> pings[index.get(p)]));
      String[] reversed = expected[m].split(",");
      List<String> rev = Arrays.asList(reversed);
      Collections.reverse(rev);
      check(join(ordered).equals(String.join(",", rev)), "reverse " + modes[m] + " sorting");
    }
    Player p1 = player("Same", "world", new UUID(0, 2)),
        p2 = player("same", "world", new UUID(0, 1));
    base.addProperty("sort", "NAME");
    base.addProperty("sortReverse", false);
    check(
        TabFormatting.order(new DisplayDesign(base), p -> "default", p -> 0, p -> 0).compare(p1, p2)
            > 0,
        "UUID tie-break prevents unstable identical-name ordering");
  }

  private static String join(List<Player> players) {
    List<String> names = new ArrayList<>();
    for (Player player : players) names.add(player.getName());
    return String.join(",", names);
  }

  private static void luckPerms() throws Exception {
    Group low = group("vip", 20), high = group("admin", 80);
    Map<String, Group> groups = new HashMap<>();
    groups.put("vip", low);
    groups.put("admin", high);
    QueryOptions options = proxy(QueryOptions.class, (p, m, a) -> null);
    AtomicReference<Object> queried = new AtomicReference<>();
    CachedMetaData meta =
        proxy(
            CachedMetaData.class,
            (p, m, a) ->
                m.getName().equals("getPrefix")
                    ? "&6[VIP] "
                    : m.getName().equals("getSuffix") ? "&b✦" : null);
    Object cache =
        proxy(
            User.class.getMethod("getCachedData").getReturnType(),
            (p, m, a) -> {
              if (m.getName().equals("getMetaData")) {
                queried.set(a[0]);
                return meta;
              }
              throw new AssertionError("Unexpected cache operation: " + m);
            });
    User user =
        proxy(
            User.class,
            (p, m, a) -> {
              switch (m.getName()) {
                case "getPrimaryGroup":
                  return "vip";
                case "getInheritedGroups":
                  return new HashSet<>(groups.values());
                case "getCachedData":
                  return cache;
                default:
                  throw new AssertionError("Unexpected user operation: " + m);
              }
            });
    Object users =
        proxy(
            LuckPerms.class.getMethod("getUserManager").getReturnType(),
            (p, m, a) -> {
              if (m.getName().equals("getUser")) return user;
              throw new AssertionError("Permission I/O attempted: " + m);
            });
    Object groupManager =
        proxy(
            LuckPerms.class.getMethod("getGroupManager").getReturnType(),
            (p, m, a) -> {
              if (m.getName().equals("getGroup")) return groups.get(a[0]);
              if (m.getName().equals("getLoadedGroups")) return new HashSet<>(groups.values());
              throw new AssertionError("Permission I/O attempted: " + m);
            });
    Object context =
        proxy(LuckPerms.class.getMethod("getContextManager").getReturnType(), (p, m, a) -> options);
    LuckPerms api =
        proxy(
            LuckPerms.class,
            (p, m, a) -> {
              switch (m.getName()) {
                case "getUserManager":
                  return users;
                case "getGroupManager":
                  return groupManager;
                case "getContextManager":
                  return context;
                default:
                  throw new AssertionError("Unexpected API operation: " + m);
              }
            });
    LuckPermsBridge bridge = new LuckPermsBridge(null, api);
    Player player = player("Alex", "world", new UUID(0, 1));
    PlayerIdentity identity = bridge.identity(player, true);
    check(
        identity.group.equals("vip")
            && identity.groupWeight == 20
            && identity.luckPermsSuffix.equals("&b✦"),
        "primary group supplies its weight and cached suffix");
    check(queried.get() == options, "suffix reads use the player's current query context");
    identity = bridge.identity(player, false);
    check(
        identity.group.equals("admin") && identity.groupWeight == 80,
        "highest inherited group supplies its matching weight");
    check(
        bridge.groupMetadata().get(0).getAsJsonObject().get("group").getAsString().equals("admin"),
        "loaded group metadata ordered by weight without permission I/O");
    groups.remove("vip");
    identity = bridge.identity(player, true);
    check(identity.groupWeight == 0, "missing cached group has a stable zero-weight fallback");
    bridge.close();
  }

  private static Group group(String name, int weight) {
    return proxy(
        Group.class,
        (p, m, a) ->
            m.getName().equals("getName")
                ? name
                : m.getName().equals("getWeight") ? OptionalInt.of(weight) : null);
  }
}
