package me.snowsun.tabprefix.presentation.display;

import com.google.gson.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.snowsun.tabprefix.application.DisplayService;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.HeaderFooterAccess;
import me.snowsun.tabprefix.infrastructure.bukkit.VirtualTabPackets;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;

/** All Bukkit reads and display writes run on the main thread. HTTP only requests snapshots. */
public final class DisplayController implements Listener, AutoCloseable {
  private static final class Header {
    final String beforeHeader, beforeFooter;
    String header, footer;
    boolean ownsHeader, ownsFooter;

    Header(Player p, HeaderFooterAccess access) {
      beforeHeader = access.header(p);
      beforeFooter = access.footer(p);
    }
  }

  private final JavaPlugin plugin;
  private final DisplayService service;
  private final PrefixController prefixes;
  private final ScreenHudView screenHud = new ScreenHudView();
  private final SidebarView sidebar;
  private final boolean nativeCanvasSupported;
  private final HeaderFooterAccess headerFooter;
  private final NameTagView tags;
  private final DisplayBoards boards;
  private final VirtualTabPackets layout;
  private final BossBarView bossBars;
  private final NormalTabView normalTab;
  private final Map<UUID, Header> headers = new HashMap<>();
  private final AtomicBoolean dirty = new AtomicBoolean(true);
  private TextRenderer renderer;
  private BukkitTask task;
  private long tick;
  private int sidebarConflicts, tagConflicts, headerConflicts, sortConflicts;

  public DisplayController(
      JavaPlugin plugin,
      DisplayService service,
      PrefixController prefixes,
      PluginSettings settings) {
    this.plugin = plugin;
    this.service = service;
    this.prefixes = prefixes;
    nativeCanvasSupported = settings.minecraft.bitmapFonts();
    tags = new NameTagView(plugin, settings.minecraft);
    sidebar = new SidebarView(settings.minecraft);
    headerFooter = new HeaderFooterAccess(plugin.getLogger(), settings.minecraft);
    boards = new DisplayBoards(plugin.getServer());
    layout = new VirtualTabPackets(plugin);
    bossBars = new BossBarView(plugin.getServer());
    normalTab = new NormalTabView(plugin, settings.minecraft);
    renderer = new TextRenderer(settings);
    prefixes.managedDisplays();
    prefixes.animations(id -> service.preferences(id).animations);
    prefixes.displayChanged(() -> dirty.set(true));
    service.onChanged(
        () -> {
          dirty.set(true);
          prefixes.requestAll();
        });
  }

  public void reload(PluginSettings settings) {
    renderer = new TextRenderer(settings);
    dirty.set(true);
  }

  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::render, 2, 1);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    dirty.set(true);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    headers.remove(id);
    headerFooter.forget(id);
    layout.forget(id);
    bossBars.forget(id);
    screenHud.forget(id);
    normalTab.forget(id);
    tags.forget(id);
    boards.restore(e.getPlayer());
    boards.forget(id);
    dirty.set(true);
  }

  @EventHandler
  public void world(PlayerChangedWorldEvent e) {
    dirty.set(true);
  }

  @EventHandler
  public void mode(PlayerGameModeChangeEvent e) {
    dirty.set(true);
  }

  private List<Player> visible(Player viewer, DisplayDesign d) {
    List<Player> players = new ArrayList<>();
    for (Player p : plugin.getServer().getOnlinePlayers()) if (viewer.canSee(p)) players.add(p);
    if (service.preferences(viewer.getUniqueId()).sorting)
      players.sort(
          TabFormatting.order(
              d,
              p -> prefixes.group(p.getUniqueId()),
              p -> prefixes.weight(p.getUniqueId()),
              layout::ping));
    else
      players.sort(
          Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)
              .thenComparing(Player::getUniqueId));
    return players;
  }

  private Map<String, String> values(
      Player target, Player viewer, DisplayDesign d, int online, int overflow) {
    Map<String, String> v = new LinkedHashMap<>();
    v.put("player", target.getName());
    v.put("prefix", prefixes.prefix(target, viewer));
    v.put("group", prefixes.group(target.getUniqueId()));
    v.put("server", d.serverName);
    v.put("online", String.valueOf(online));
    v.put("max", String.valueOf(plugin.getServer().getMaxPlayers()));
    v.put("world", target.getWorld().getName());
    v.put("ping", String.valueOf(layout.ping(target)));
    LocalDateTime now = LocalDateTime.now();
    v.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
    v.put("date", now.format(DateTimeFormatter.ISO_LOCAL_DATE));
    v.put(
        "health",
        java.math.BigDecimal.valueOf(target.getHealth()).stripTrailingZeros().toPlainString());
    v.put(
        "max_health",
        java.math.BigDecimal.valueOf(target.getMaxHealth()).stripTrailingZeros().toPlainString());
    v.put("food", String.valueOf(target.getFoodLevel()));
    v.put(
        "experience",
        String.valueOf(Math.round(target.getExp() * 1000) / 10d).replaceAll("\\.0$", ""));
    v.put("level", String.valueOf(target.getLevel()));
    v.put("x", String.valueOf(target.getLocation().getBlockX()));
    v.put("y", String.valueOf(target.getLocation().getBlockY()));
    v.put("z", String.valueOf(target.getLocation().getBlockZ()));
    v.put("layout_overflow", String.valueOf(overflow));
    PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
    DisplayDesign.GroupStyle style = d.groupStyle(prefixes.group(target.getUniqueId()));
    v.put("suffix", "");
    v.put("tab_name", "");
    v.put("display_name", target.getName());
    v.put("weight", String.valueOf(prefixes.weight(target.getUniqueId())));
    v.put(
        "suffix",
        TabFormatting.suffix(
            d,
            style,
            prefixes.suffix(target.getUniqueId()),
            v,
            renderer,
            tick,
            prefs.suffixes,
            prefs.animations));
    v.put("display_name", TabFormatting.name(target.getName(), style, renderer));
    v.put("tab_name", line(d.tabPlayerFormat, v, prefs.animations));
    return v;
  }

  private String line(DisplayDesign.Line line, Map<String, String> variables, boolean animate) {
    String legacy = renderer.legacy(renderer.display(line.source(tick, animate), variables));
    return animate && line.animation.equals("SCROLL")
        ? DisplayAnimation.scroll(legacy, line.width, line.gap, tick / line.speed)
        : legacy;
  }

  private List<String> lines(
      List<DisplayDesign.Line> lines, Map<String, String> variables, boolean animate) {
    List<String> out = new ArrayList<>();
    for (DisplayDesign.Line l : lines) out.add(line(l, variables, animate));
    return out;
  }

  private void render() {
    tick++;
    DisplayDesign d = service.snapshot().design;
    boolean normalUpdate =
        dirty.getAndSet(false)
            || tick % d.refreshTicks == 0
            || tabAnimationDue(d)
            || prefixes.graphicalAnimationDue(tick);
    if (!normalUpdate) {
      boolean animated =
          d.screenEnabled
              || d.sidebarEnabled && !d.sidebarMode.equals("NATIVE")
              || d.bossBarsEnabled
                  && (d.bossBarMode.equals("ROTATE")
                      || d.bossBars.stream()
                          .anyMatch(
                              bar ->
                                  bar.enabled
                                      && (bar.timed() || !bar.line.animation.equals("NONE"))));
      if (!animated || tick % 2 != 0) return;
      for (Player viewer : plugin.getServer().getOnlinePlayers()) {
        try {
          PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
          List<Player> visible = visible(viewer, d);
          if (d.sidebarEnabled && !d.sidebarMode.equals("NATIVE"))
            header(
                viewer,
                d,
                prefs,
                values(viewer, viewer, d, visible.size(), overflow(d, visible.size())));
          screen(
              viewer,
              d,
              prefs,
              values(viewer, viewer, d, visible.size(), overflow(d, visible.size())));
          bossBars.render(
              viewer,
              d,
              prefs,
              values(viewer, viewer, d, visible.size(), overflow(d, visible.size())),
              tick,
              service.snapshot().revision,
              (source, vars) -> line(source, vars, prefs.animations));
        } catch (RuntimeException e) {
          plugin
              .getLogger()
              .warning("Boss bar update skipped: " + me.snowsun.tabprefix.util.Failures.message(e));
        }
      }
      return;
    }
    Set<Scoreboard> sideboards =
        Collections.newSetFromMap(new IdentityHashMap<Scoreboard, Boolean>());
    Map<Scoreboard, List<Player>> tagboards = new IdentityHashMap<>();
    sidebarConflicts = 0;
    tagConflicts = 0;
    headerConflicts = 0;
    sortConflicts = 0;
    for (Player viewer : plugin.getServer().getOnlinePlayers()) {
      try {
        PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
        List<Player> visible = visible(viewer, d);
        Map<String, String> variables =
            values(viewer, viewer, d, visible.size(), overflow(d, visible.size()));
        header(viewer, d, prefs, variables);
        screen(viewer, d, prefs, variables);
        bossBars.render(
            viewer,
            d,
            prefs,
            variables,
            tick,
            service.snapshot().revision,
            (source, vars) -> line(source, vars, prefs.animations));
        boolean side = d.sidebarEnabled && prefs.sidebar && d.sidebarMode.equals("NATIVE"),
            names = d.nameTagsEnabled && prefs.nameTags,
            order =
                d.nativeSortEnabled
                    && prefs.sorting
                    && (!d.layoutEnabled || !prefs.layout || !layout.available());
        Scoreboard board = boards.board(viewer, side || names || order);
        if (side) {
          if (boards.personal(board)) {
            if (sidebar.render(
                board,
                renderer.legacy(renderer.display(d.title, variables)),
                lines(d.sidebar, variables, prefs.animations))) sideboards.add(board);
            else sidebarConflicts++;
          } else sidebarConflicts++;
        }
        if (names || order) {
          if (boards.personal(board))
            tagboards.computeIfAbsent(board, key -> new ArrayList<>()).add(viewer);
          else {
            if (names) tagConflicts++;
            if (order) sortConflicts++;
          }
        }
        Map<Player, String> normal = new LinkedHashMap<>();
        for (Player target : visible) {
          Map<String, String> vars =
              values(target, viewer, d, visible.size(), overflow(d, visible.size()));
          normal.put(
              target,
              d.tabFormatEnabled
                  ? vars.get("tab_name")
                  : prefixes.tabActive()
                      ? prefixes.prefix(target, viewer) + "§r" + target.getName()
                      : normalTab.original(target));
        }
        normalTab.render(
            viewer,
            normal,
            target -> {
              Map<String, String> vars = values(target, target, d, visible.size(), 0);
              vars.put("prefix", prefixes.textPrefix(target.getUniqueId()));
              return d.tabFormatEnabled
                  ? line(d.tabPlayerFormat, vars, false)
                  : prefixes.tabActive()
                      ? prefixes.textPrefix(target.getUniqueId()) + "§r" + target.getName()
                      : normalTab.original(target);
            });
        if (d.layoutEnabled && prefs.layout)
          layout.render(viewer, cells(viewer, d, prefs, visible), visible, tick);
        else layout.restore(viewer, visible);
      } catch (RuntimeException e) {
        plugin
            .getLogger()
            .warning("Display update skipped: " + me.snowsun.tabprefix.util.Failures.message(e));
      }
    }
    sidebar.retain(sideboards);
    tags.retain(tagboards.keySet());
    for (Map.Entry<Scoreboard, List<Player>> entry : tagboards.entrySet()) {
      Player sample = entry.getValue().get(0);
      PlayerDisplaySettings prefs = service.preferences(sample.getUniqueId());
      boolean names = d.nameTagsEnabled && prefs.nameTags,
          order =
              d.nativeSortEnabled
                  && prefs.sorting
                  && (!d.layoutEnabled || !prefs.layout || !layout.available());
      List<Player> sorted = visible(sample, d);
      if (!order)
        sorted.sort(
            Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Player::getUniqueId));
      Map<UUID, Integer> positions = new HashMap<>();
      for (int i = 0; i < sorted.size(); i++) positions.put(sorted.get(i).getUniqueId(), i);
      for (Player target : plugin.getServer().getOnlinePlayers()) {
        boolean see = true;
        String prefix = null;
        for (Player viewer : entry.getValue()) {
          see &= viewer.canSee(target);
          String rendered = prefixes.prefix(target, viewer);
          if (prefix == null) prefix = rendered;
          else if (!prefix.equals(rendered)) prefix = prefixes.textPrefix(target.getUniqueId());
        }
        if (!see) {
          tags.remove(entry.getKey(), target.getUniqueId());
          continue;
        }
        if (entry.getKey().getEntryTeam(target.getName()) != null
            && !entry.getKey().getEntryTeam(target.getName()).getName().startsWith("tp")) {
          if (names) tagConflicts++;
          if (order) sortConflicts++;
          continue;
        }
        Map<String, String> vars = values(target, sample, d, sorted.size(), 0);
        vars.put("prefix", prefix == null ? "" : prefix);
        boolean applied =
            tags.apply(
                entry.getKey(),
                target,
                names ? renderer.legacy(renderer.display(d.nameTagPrefix, vars)) : "",
                names ? renderer.legacy(renderer.display(d.nameTagSuffix, vars)) : "",
                names ? d.visibility : "ALWAYS",
                names ? d.collision : "ALWAYS",
                positions.getOrDefault(target.getUniqueId(), 99999));
        if (!applied) {
          if (names) tagConflicts++;
          if (order) sortConflicts++;
        }
      }
    }
  }

  private boolean tabAnimationDue(DisplayDesign d) {
    if (tick % 2 != 0) return false;
    boolean images =
        d.layoutEnabled
            && d.slots.stream()
                .anyMatch(slot -> slot.kind.equals("IMAGE") && prefixes.imageAnimated(slot.image));
    boolean format = d.tabFormatEnabled && !d.tabPlayerFormat.animation.equals("NONE");
    boolean suffix =
        d.suffixEnabled
            && d.groupStyles.stream()
                .anyMatch(
                    style ->
                        style.suffixMode.equals("CUSTOM")
                            && !style.suffix.animation.equals("NONE"));
    if (!format && !suffix && !images) return false;
    for (Player viewer : plugin.getServer().getOnlinePlayers()) {
      PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
      if (prefs.animations && (format || suffix && prefs.suffixes || images && prefs.layout))
        return true;
    }
    return false;
  }

  private static int overflow(DisplayDesign d, int online) {
    Set<Integer> indexes = new HashSet<>();
    for (DisplayDesign.Slot slot : d.slots)
      if (slot.kind.equals("PLAYER") && slot.playerIndex <= online) indexes.add(slot.playerIndex);
    return Math.max(0, online - indexes.size());
  }

  private List<VirtualTabPackets.Cell> cells(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, List<Player> visible) {
    List<VirtualTabPackets.Cell> cells = new ArrayList<>();
    int overflow = overflow(d, visible.size());
    for (DisplayDesign.Slot slot : d.slots) {
      Player target =
          slot.kind.equals("PLAYER") && slot.playerIndex <= visible.size()
              ? visible.get(slot.playerIndex - 1)
              : null;
      String text =
          slot.kind.equals("EMPTY") || (slot.kind.equals("PLAYER") && target == null)
              ? ""
              : line(
                  slot.line,
                  values(target == null ? viewer : target, viewer, d, visible.size(), overflow),
                  prefs.animations);
      if (slot.kind.equals("IMAGE"))
        text = prefixes.screenImage(slot.image, viewer, tick, prefs.animations, text);
      cells.add(new VirtualTabPackets.Cell(text, target));
    }
    return cells;
  }

  private void header(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, Map<String, String> variables) {
    boolean fallback = sidebarFallback(viewer, d, prefs);
    boolean enabled = prefs.headerFooter && (d.headerEnabled || d.footerEnabled) || fallback;
    if (!enabled) {
      restoreHeader(viewer);
      return;
    }
    Header h = headers.get(viewer.getUniqueId());
    if (h == null) {
      h = new Header(viewer, headerFooter);
      headers.put(viewer.getUniqueId(), h);
    }
    if (d.headerEnabled && prefs.headerFooter) {
      if (h.ownsHeader && !Objects.equals(headerFooter.header(viewer), h.header)) headerConflicts++;
      else {
        String top = String.join("\n", lines(d.header, variables, prefs.animations));
        if (!Objects.equals(top, headerFooter.header(viewer))) headerFooter.header(viewer, top);
        h.header = top;
        h.ownsHeader = true;
      }
    } else if (h.ownsHeader) {
      if (Objects.equals(headerFooter.header(viewer), h.header))
        headerFooter.header(viewer, h.beforeHeader);
      h.ownsHeader = false;
    }
    if (d.footerEnabled && prefs.headerFooter || fallback) {
      if (h.ownsFooter && !Objects.equals(headerFooter.footer(viewer), h.footer)) headerConflicts++;
      else {
        String bottom =
            d.footerEnabled && prefs.headerFooter
                ? String.join("\n", lines(d.footer, variables, prefs.animations))
                : "";
        if (fallback) {
          String panel =
              renderer.legacy(renderer.display(d.title, variables))
                  + "\n"
                  + String.join("\n", lines(d.sidebar, variables, prefs.animations));
          bottom += (bottom.isEmpty() ? "" : "\n\n") + panel;
        }
        if (!Objects.equals(bottom, headerFooter.footer(viewer)))
          headerFooter.footer(viewer, bottom);
        h.footer = bottom;
        h.ownsFooter = true;
      }
    } else if (h.ownsFooter) {
      if (Objects.equals(headerFooter.footer(viewer), h.footer))
        headerFooter.footer(viewer, h.beforeFooter);
      h.ownsFooter = false;
    }
  }

  private void restoreHeader(Player p) {
    Header h = headers.remove(p.getUniqueId());
    if (h == null) return;
    if (h.ownsHeader && Objects.equals(headerFooter.header(p), h.header))
      headerFooter.header(p, h.beforeHeader);
    if (h.ownsFooter && Objects.equals(headerFooter.footer(p), h.footer))
      headerFooter.footer(p, h.beforeFooter);
  }

  /**
   * An authenticated read-only preview uses the same renderer and visibility rules as the server.
   */
  public JsonObject preview(Player viewer, DisplayDesign d, long previewTick) {
    long before = tick;
    tick = previewTick;
    try {
      List<Player> visible = visible(viewer, d);
      PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
      Map<String, String> vars =
          values(viewer, viewer, d, visible.size(), overflow(d, visible.size()));
      JsonObject j = new JsonObject();
      j.add("header", jsonLines(lines(d.header, vars, prefs.animations)));
      j.add("footer", jsonLines(lines(d.footer, vars, prefs.animations)));
      j.add("sidebar", jsonLines(lines(d.sidebar, vars, prefs.animations)));
      JsonObject hud = new JsonObject();
      for (Map.Entry<String, String> e : screenText(viewer, d, prefs, vars).entrySet())
        hud.add(e.getKey(), rich(e.getValue()));
      j.add("screen", hud);
      j.addProperty(
          "scoreNumbers",
          d.sidebarMode.equals("NATIVE") ? sidebar.numberStatus() : "scoreless-panel");
      j.addProperty(
          "sidebarPlacement",
          d.sidebarMode.equals("NATIVE")
              ? "SIDEBAR"
              : sidebarFallback(viewer, d, prefs) ? "TAB_FOOTER" : "FONT_CANVAS");
      j.addProperty("canvasLoaded", prefixes.canvas(viewer).present());
      JsonArray elements = new JsonArray();
      for (DisplayDesign.Screen item : d.screen) {
        JsonObject row = new JsonObject();
        row.addProperty("id", item.id);
        row.addProperty("anchor", item.anchor);
        row.addProperty("x", item.x);
        row.addProperty("y", item.y);
        row.addProperty("height", item.height);
        row.addProperty("reason", screenReason(viewer, d, prefs, item));
        row.add("text", rich(line(item.line, vars, prefs.animations)));
        elements.add(row);
      }
      j.add("screenElements", elements);
      JsonArray bars = new JsonArray();
      for (BossBarView.Frame frame :
          BossBarView.frames(
              viewer,
              d,
              prefs,
              vars,
              previewTick,
              (source, values) -> line(source, values, prefs.animations))) {
        JsonObject bar = new JsonObject();
        bar.addProperty("id", frame.design.id);
        bar.add("title", rich(frame.title));
        bar.addProperty("color", frame.design.color);
        bar.addProperty("style", frame.design.style);
        bar.addProperty("progress", frame.progress);
        bar.addProperty("active", frame.active);
        bar.addProperty("reason", frame.reason);
        bars.add(bar);
      }
      j.add("bossBars", bars);
      j.add("title", rich(renderer.legacy(renderer.display(d.title, vars))));
      List<String> slots = new ArrayList<>();
      for (VirtualTabPackets.Cell cell : cells(viewer, d, prefs, visible)) slots.add(cell.text);
      j.add("slots", jsonLines(slots));
      List<String> players = new ArrayList<>();
      for (Player target : visible) {
        Map<String, String> variables =
            values(target, viewer, d, visible.size(), overflow(d, visible.size()));
        players.add(
            d.tabFormatEnabled
                ? variables.get("tab_name")
                : prefixes.tabActive()
                    ? prefixes.prefix(target, viewer) + "§r" + target.getName()
                    : normalTab.original(target));
      }
      j.add("players", jsonLines(players));
      j.add("playerFormat", rich(vars.get("tab_name")));
      JsonArray styles = new JsonArray();
      for (DisplayDesign.GroupStyle style : d.groupStyles) {
        Player example =
            visible.stream()
                .filter(p -> prefixes.group(p.getUniqueId()).equalsIgnoreCase(style.group))
                .findFirst()
                .orElse(viewer);
        Map<String, String> values = values(example, viewer, d, visible.size(), 0);
        values.put("group", style.group);
        String suffix =
            TabFormatting.suffix(
                d,
                style,
                prefixes.group(example.getUniqueId()).equalsIgnoreCase(style.group)
                    ? prefixes.suffix(example.getUniqueId())
                    : "",
                values,
                renderer,
                tick,
                prefs.suffixes,
                prefs.animations);
        JsonObject row = new JsonObject();
        row.addProperty("group", style.group);
        row.add("suffix", rich(suffix));
        row.add("name", rich(TabFormatting.name(example.getName(), style, renderer)));
        styles.add(row);
      }
      j.add("groupStyles", styles);
      j.add(
          "nameTag",
          rich(
              renderer.legacy(renderer.display(d.nameTagPrefix, vars))
                  + "§r"
                  + viewer.getName()
                  + renderer.legacy(renderer.display(d.nameTagSuffix, vars))));
      j.addProperty("online", visible.size());
      j.addProperty("overflow", overflow(d, visible.size()));
      j.addProperty("layoutAvailable", layout.available());
      j.addProperty("sidebarConflicts", sidebarConflicts);
      j.addProperty("nameTagConflicts", tagConflicts);
      j.addProperty("headerConflicts", headerConflicts);
      j.addProperty("sortConflicts", sortConflicts);
      return j;
    } finally {
      tick = before;
    }
  }

  private static final LegacyComponentSerializer LEGACY =
      LegacyComponentSerializer.builder()
          .character('§')
          .hexColors()
          .useUnusualXRepeatedCharacterHexFormat()
          .build();

  private static JsonElement rich(String text) {
    return new JsonParser()
        .parse(GsonComponentSerializer.gson().serialize(LEGACY.deserialize(text)));
  }

  private static JsonArray jsonLines(List<String> lines) {
    JsonArray a = new JsonArray();
    for (String s : lines) a.add(rich(s));
    return a;
  }

  public String diagnostic() {
    return "displayRevision="
        + service.snapshot().revision
        + ", fixedTab="
        + layout.available()
        + ", sidebarMode="
        + service.snapshot().design.sidebarMode
        + ", nativeCanvasSupported="
        + nativeCanvasSupported
        + ", scoreNumbers="
        + (service.snapshot().design.sidebarMode.equals("NATIVE")
            ? sidebar.numberStatus()
            : "scoreless-panel")
        + ", sidebarConflicts="
        + sidebarConflicts
        + ", nametagConflicts="
        + tagConflicts
        + ", headerConflicts="
        + headerConflicts
        + ", bossBars="
        + bossBars.count()
        + ", sortConflicts="
        + sortConflicts
        + ", "
        + layout.diagnostic()
        + ", "
        + normalTab.diagnostic()
        + ", "
        + headerFooter.diagnostic();
  }

  public void close() {
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    sidebar.close();
    tags.close();
    bossBars.close();
    screenHud.close();
    normalTab.close();
    boards.close();
    for (Player p : plugin.getServer().getOnlinePlayers()) {
      restoreHeader(p);
      layout.restore(p, visible(p, service.snapshot().design));
    }
    headers.clear();
    headerFooter.clear();
  }

  private String screenReason(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, DisplayDesign.Screen item) {
    if (!d.screenEnabled) return "SERVER_DISABLED";
    if (!prefs.screen) return "PERSONAL_DISABLED";
    if (!item.enabled) return "DISABLED";
    if (!item.permission.isEmpty() && !viewer.hasPermission(item.permission)) return "PERMISSION";
    if (!item.worlds.isEmpty() && !item.worlds.contains(viewer.getWorld().getName()))
      return "WORLD";
    return "";
  }

  private boolean sidebarFallback(Player viewer, DisplayDesign d, PlayerDisplaySettings prefs) {
    if (!d.sidebarEnabled || !prefs.sidebar || d.sidebarMode.equals("NATIVE")) return false;
    if (d.sidebarMode.equals("TAB")) return true;
    NativeHudLayout h = prefixes.canvas(viewer);
    if (h.get("sidebar:title", d.sidebarY, 8, "") == null) return true;
    for (int n = 0; n < d.sidebar.size(); n++)
      if (h.get("sidebar:" + n, d.sidebarY + 12 + n * 10, 8, "") == null) return true;
    return false;
  }

  private Map<String, String> screenText(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, Map<String, String> vars) {
    Map<String, String> text = new LinkedHashMap<>();
    for (DisplayDesign.Screen item : d.screen) {
      if (!screenReason(viewer, d, prefs, item).isEmpty()) continue;
      if (item.anchor.equals("FREE_XY")
          && prefixes
                  .canvas(viewer)
                  .get(
                      "screen:" + item.id,
                      item.y,
                      item.height,
                      item.image == null ? "" : item.image.toString())
              != null) continue;
      String value = line(item.line, vars, prefs.animations);
      if (item.kind.equals("IMAGE"))
        value = prefixes.screenImage(item.image, viewer, tick, prefs.animations, value);
      if (!value.isEmpty())
        text.merge(
            item.anchor.equals("FREE_XY") ? "ACTION_BAR" : item.anchor,
            value,
            (a, b) -> a + " §r" + b);
    }
    for (String anchor : Arrays.asList("TITLE", "SUBTITLE", "ACTION_BAR"))
      text.putIfAbsent(anchor, "");
    return text;
  }

  private void screen(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, Map<String, String> vars) {
    Map<String, String> text = screenText(viewer, d, prefs, vars);
    NativeHudLayout h = prefixes.canvas(viewer);
    StringBuilder canvas = new StringBuilder();
    for (DisplayDesign.Screen item : d.screen)
      if (item.anchor.equals("FREE_XY") && screenReason(viewer, d, prefs, item).isEmpty()) {
        NativeHudLayout.Profile p =
            h.get(
                "screen:" + item.id,
                item.y,
                item.height,
                item.image == null ? "" : item.image.toString());
        if (p == null) continue;
        NativeHudLayout.Encoded e;
        if (item.kind.equals("IMAGE")) {
          int frame = prefixes.imageFrame(item.image, tick, prefs.animations);
          if (frame >= p.frames()) continue;
          e = new NativeHudLayout.Encoded("§f" + p.glyph(frame), p.advance(frame));
        } else e = NativeHudLayout.encode(p, line(item.line, vars, prefs.animations));
        canvas.append(NativeHudLayout.position(e, item.x));
      }
    if (d.sidebarEnabled
        && prefs.sidebar
        && d.sidebarMode.equals("HUD")
        && !sidebarFallback(viewer, d, prefs)) {
      NativeHudLayout.Profile p = h.get("sidebar:title", d.sidebarY, 8, "");
      canvas.append(
          NativeHudLayout.position(
              NativeHudLayout.encode(p, renderer.legacy(renderer.display(d.title, vars))),
              d.sidebarX));
      for (int n = 0; n < d.sidebar.size(); n++) {
        p = h.get("sidebar:" + n, d.sidebarY + 12 + n * 10, 8, "");
        canvas.append(
            NativeHudLayout.position(
                NativeHudLayout.encode(p, line(d.sidebar.get(n), vars, prefs.animations)),
                d.sidebarX));
      }
    }
    String action = text.get("ACTION_BAR");
    if (canvas.length() > 0) {
      NativeHudLayout.Profile p = h.get("action", 0, 8, "");
      if (p != null && !action.isEmpty()) {
        NativeHudLayout.Encoded e = NativeHudLayout.encode(p, action);
        canvas.append(NativeHudLayout.position(e, -e.width / 2));
      }
      action = canvas.toString();
    }
    screenHud.render(viewer, text.get("TITLE"), text.get("SUBTITLE"), action, tick);
  }
}
