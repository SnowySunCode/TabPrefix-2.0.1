package me.snowsun.tabprefix.test;

import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.application.port.CoreControl;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.persistence.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;
import me.snowsun.tabprefix.infrastructure.web.*;
import me.snowsun.tabprefix.presentation.command.FeatureCommands;
import me.snowsun.tabprefix.presentation.message.MessageService;
import me.snowsun.tabprefix.util.AtomicFiles;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Deterministic subnet cases plus real occupied-port HTTP, SQLite, packs and clickable replies. */
public final class NetworkIntegrationTest {
  private static final UUID OWNER = UUID.randomUUID(), OTHER = UUID.randomUUID();
  private static final Logger LOG = Logger.getLogger("TabPrefix-NetworkTest");
  private static int checks;

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    checks++;
  }

  private static InetAddress ip(String host) throws Exception {
    return InetAddress.getByName(host);
  }

  private static LocalNetwork.Address address(String name, String host, int bits, boolean virtual)
      throws Exception {
    return new LocalNetwork.Address(name, name, ip(host), bits, virtual);
  }

  private static YamlConfiguration yaml(Path project) throws Exception {
    YamlConfiguration y = new YamlConfiguration();
    y.load(project.resolve("resources/config.yml").toFile());
    y.set("web.public-host", "");
    y.set("web.public-base-url", "");
    y.set("resource-pack.public-base-url", "");
    y.set("web.bind-address", "0.0.0.0");
    return y;
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]);
    Path root = Files.createTempDirectory(project.resolve(".build/test-data"), "network-");
    try {
      selection(project, root);
      binding(project, root);
      System.out.println(
          "PASS: "
              + checks
              + " network checks. LAN/subnets/IPv6, address changes, occupied port, actual pack"
              + " URLs, per-session Origin, diagnostics and native clicks.");
    } finally {
      AtomicFiles.deleteTree(root);
    }
  }

  private static void selection(Path project, Path root) throws Exception {
    LocalNetwork.Address wifi = address("Wi-Fi", "192.168.1.10", 24, false);
    LocalNetwork.Address cable = address("Ethernet", "10.1.2.10", 24, false);
    LocalNetwork.Address docker = address("docker0", "172.17.0.1", 16, true);
    LocalNetwork.Address loop = address("loopback", "127.0.0.1", 8, false);
    LocalNetwork.Address v6 = address("IPv6", "fd12:3456:789a::10", 64, false);
    List<LocalNetwork.Address> list = Arrays.asList(docker, loop, v6, cable, wifi);
    check(
        LocalNetwork.best(list, ip("192.168.1.55"), "10.1.2.10") == wifi,
        "Player subnet beats unrelated server-ip");
    check(LocalNetwork.best(list, ip("10.1.2.99"), "") == cable, "Ethernet peer chooses Ethernet");
    check(
        LocalNetwork.best(Arrays.asList(docker, wifi, loop), null, "") == wifi,
        "Physical LAN beats container and loopback");
    check(
        LocalNetwork.best(list, ip("127.0.0.2"), "") == loop,
        "Same-computer connection uses loopback");
    check(
        LocalNetwork.best(list, ip("fd12:3456:789a::99"), "") == v6,
        "IPv6 peer chooses its IPv6 subnet");
    check(
        !wifi.contains(ip("192.168.2.55")) && !wifi.contains(ip("::1")),
        "Unrelated networks/families do not match");
    check(
        address("/25", "192.168.1.10", 25, false).contains(ip("192.168.1.127"))
            && !address("/25", "192.168.1.10", 25, false).contains(ip("192.168.1.128")),
        "Subnet bit boundary is respected");
    check(
        !address("unknown", "192.168.1.10", -1, false).contains(ip("192.168.1.11")),
        "Unknown mask is an exact-host match");
    check(WebAddresses.http("::1", 8765).equals("http://[::1]:8765"), "IPv6 URL brackets");
    check(
        WebAddresses.origin("https://prefix.example/base").equals("https://prefix.example"),
        "Reverse proxy path is not part of browser Origin");

    AtomicLong clock = new AtomicLong(10000);
    AtomicReference<List<LocalNetwork.Address>> interfaces = new AtomicReference<>(list);
    YamlConfiguration y = yaml(project);
    PluginSettings settings = new PluginSettings(y, root);
    WebAddresses book = new WebAddresses(settings.features, "", interfaces::get, clock::get);
    book.bound(new InetSocketAddress("0.0.0.0", 9876));
    check(
        book.base(OWNER, ip("192.168.1.55")).equals("http://192.168.1.10:9876"),
        "URLs use actual port rather than config preference");
    check(book.select(OWNER, "10.1.2.10"), "Existing server address can be selected");
    check(
        !book.select(OWNER, "attacker.example") && !book.select(OWNER, "192.168.1.99"),
        "Arbitrary hosts and non-server IPs cannot be selected");
    check(book.base(OWNER, ip("192.168.1.55")).contains("10.1.2.10"), "Manual choice wins");
    check(
        book.base(OTHER, ip("192.168.1.55")).contains("192.168.1.10"),
        "Choice is isolated to one player");
    interfaces.set(Arrays.asList(wifi, loop));
    clock.addAndGet(4000);
    check(
        book.base(OWNER, ip("192.168.1.55")).contains("192.168.1.10"),
        "Removed interface drops the stale manual choice");
    LocalNetwork.Address moved = address("Wi-Fi", "192.168.1.20", 24, false);
    interfaces.set(Arrays.asList(moved, loop));
    clock.addAndGet(4000);
    check(
        book.base(OWNER, ip("192.168.1.55")).contains("192.168.1.20"),
        "New links follow a DHCP address change");
    interfaces.set(list);
    clock.addAndGet(4000);
    book.select(OWNER, "10.1.2.10");
    clock.addAndGet(2 * 60 * 60 * 1000L + 1);
    check(
        book.base(OWNER, ip("192.168.1.55")).contains("192.168.1.10"), "Temporary choices expire");
    book.select(OWNER, "10.1.2.10");
    book.automatic(OWNER);
    check(book.base(OWNER, ip("192.168.1.55")).contains("192.168.1.10"), "Auto resets choice");
    book.bound(new InetSocketAddress("127.0.0.1", 12345));
    check(
        book.available().size() == 1 && !book.select(OWNER, "192.168.1.10"),
        "Loopback binding does not advertise unreachable LAN addresses");
    check(book.base(null, null).equals("http://127.0.0.1:12345"), "Bound-only fallback is usable");

    y.set("web.public-base-url", "https://prefix.example/base");
    PluginSettings proxy = new PluginSettings(y, root);
    WebAddresses external = new WebAddresses(proxy.features, "", interfaces::get, clock::get);
    check(
        external.base(OWNER, ip("192.168.1.55")).equals("https://prefix.example/base")
            && !external.automaticPort(),
        "Explicit proxy URL and fixed upstream port preserved");
    y.set("web.port", 0);
    boolean invalid = false;
    try {
      new PluginSettings(y, root);
    } catch (IllegalArgumentException expected) {
      invalid = true;
    }
    check(invalid, "Random port with explicit public URL rejected early");
    y.set("web.public-base-url", "");
    y.set("web.port", 8765);
    y.set("web.auto-port", false);
    check(
        !new WebAddresses(new PluginSettings(y, root).features, "").automaticPort(),
        "Fixed port mode can be requested");
    check(!LocalNetwork.scan().isEmpty(), "Live interface discovery has at least loopback");
    WebAddresses legacy = WebAddresses.fixed(settings.features, "http://[::1]:0/base");
    legacy.bound(new InetSocketAddress("::1", 7654));
    check(
        legacy.base(null, null).equals("http://[::1]:7654/base"),
        "Compatibility URL with port zero follows actual binding without losing its path");
  }

  private static final class Fixture implements AutoCloseable {
    final PluginSettings settings;
    final WebAddresses addresses;
    final SqliteDatabase db;
    final PrefixService texts;
    final GraphicService graphics;
    final ResourcePackService packs;
    final EditorServer editor;

    Fixture(YamlConfiguration y, Path root) throws Exception {
      settings = new PluginSettings(y, root);
      List<LocalNetwork.Address> local =
          Arrays.asList(
              address("loopback", "127.0.0.1", 8, false),
              address("loopback alias", "127.0.0.2", 8, false));
      addresses = new WebAddresses(settings.features, "", () -> local, System::currentTimeMillis);
      db = new SqliteDatabase(settings.databaseFile, LOG);
      db.initialize().get();
      texts = new PrefixService(new SqliteGroupPrefixRepository(db));
      texts.initialize().get();
      graphics = new GraphicService(new SqliteGraphicRepository(db), texts, settings.features);
      graphics.initialize().get();
      MediaStore media = new MediaStore(settings);
      PackBuilder builder =
          new PackBuilder(
              settings, media, MinecraftVersion.parse("1.16.5"), () -> addresses.base(null, null));
      packs = new ResourcePackService(builder, graphics, (event, values) -> {});
      editor =
          new EditorServer(
              settings, graphics, texts, media, packs, (event, values) -> {}, addresses, LOG);
    }

    public void close() {
      editor.close();
      packs.close();
      texts.close();
      db.close();
    }
  }

  private static void binding(Path project, Path root) throws Exception {
    YamlConfiguration y = yaml(project);
    try (ServerSocket occupied = new ServerSocket(0)) {
      int preferred = occupied.getLocalPort();
      y.set("web.port", preferred);
      try (Fixture f = new Fixture(y, root.resolve("automatic"))) {
        f.editor.start();
        int actual = f.addresses.port();
        check(
            f.editor.running() && actual > 0 && actual != preferred,
            "Occupied preferred port automatically falls back");
        f.editor.start();
        check(f.addresses.port() == actual, "Repeated start does not leak a second listener");
        String base = "http://127.0.0.1:" + actual;
        check(request(base + "/health", null, null, null) == 200, "Real HTTP on selected port");
        f.packs.initialize().get();
        PackRevision pack = f.packs.rebuild().get();
        check(pack.url.startsWith(base + "/packs/"), "Pack build uses bound port");
        check(request(pack.url, null, null, null) == 200, "Generated pack downloads on bound port");
        check(f.addresses.select(OWNER, "127.0.0.2"), "Alternative registered address selected");
        String link = f.editor.open(OWNER, "Alex", "vip", ip("127.0.0.1"));
        String token = link.substring(link.indexOf('#') + 1);
        check(
            link.startsWith("http://127.0.0.2:" + actual + "/editor#"),
            "Editor link follows player selection and actual port");
        byte[] save =
            "{\"image\":false,\"textOverride\":true,\"text\":\"[LAN]\"}"
                .getBytes(StandardCharsets.UTF_8);
        check(
            request(base + "/api/save", token, "http://127.0.0.2:" + actual, save) == 200,
            "Selected LAN session Origin permits a real save");
        check(
            request(base + "/api/save", token, base, save) == 403,
            "A different LAN origin cannot mutate this session");
        check(
            request(base + "/api/save", token, "https://evil.example", save) == 403,
            "Untrusted Origin remains blocked");
        String otherLink = f.editor.open(OTHER, "Other", "vip", ip("127.0.0.1"));
        String otherToken = otherLink.substring(otherLink.indexOf('#') + 1);
        check(
            request(base + "/api/save", otherToken, base, save) == 200,
            "Concurrent player sessions can use different origins safely");
        f.editor.open(OWNER, "Alex", "vip", ip("127.0.0.1"));
        check(
            request(base + "/api/session", token, null, null) == 401,
            "Changing link revokes the previous token");
        check(
            f.addresses
                .packUrl(OWNER, ip("127.0.0.1"), pack)
                .startsWith("http://127.0.0.2:" + actual + "/packs/"),
            "Delivered pack URL shares the selected player address");
        check(
            f.editor.diagnostic().contains("state=listening")
                && f.editor.diagnostic().contains("port=" + actual)
                && !f.editor.diagnostic().contains(token),
            "Diagnostics identify bind state without tokens");
        commands(project, f);
        f.editor.close();
        check(
            !f.editor.running() && f.editor.state().equals("stopped"),
            "Close clears listening state");
        try (ServerSocket released = new ServerSocket(actual)) {
          check(released.isBound(), "Close releases the selected port");
        }
      }
      y.set("web.auto-port", false);
      try (Fixture f = new Fixture(y, root.resolve("fixed"))) {
        boolean failed = false;
        try {
          f.editor.start();
        } catch (BindException expected) {
          failed = true;
        }
        check(
            failed && !f.editor.running() && f.editor.state().equals("bind-failed"),
            "Fixed-port collision reports a real failure, not ready");
        check(
            !f.editor.failure().isEmpty() && f.editor.diagnostic().contains("error="),
            "Bind error is retained for commands/doctor");
        occupied.close();
        f.editor.start();
        check(
            f.editor.running() && f.addresses.port() == preferred,
            "Failed startup can retry once the fixed port is free");
      }
    }
    y.set("web.auto-port", true);
    y.set("web.port", 0);
    try (Fixture f = new Fixture(y, root.resolve("random"))) {
      f.editor.start();
      check(
          f.addresses.port() > 0 && !f.editor.open(OWNER, "Alex", "vip").contains(":0/"),
          "OS-assigned port never leaks a port-zero editor URL");
    }
    y.set("web.enabled", false);
    try (Fixture f = new Fixture(y, root.resolve("disabled"))) {
      f.editor.start();
      check(
          !f.editor.running() && f.editor.state().equals("disabled"),
          "Disabled editor has a distinct diagnostic state");
    }
  }

  private static int request(String url, String token, String origin, byte[] body)
      throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(3000);
    c.setReadTimeout(5000);
    if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
    if (origin != null) c.setRequestProperty("Origin", origin);
    if (body != null) {
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      try (OutputStream out = c.getOutputStream()) {
        out.write(body);
      }
    }
    try {
      int status = c.getResponseCode();
      InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
      if (in != null)
        try (InputStream stream = in) {
          while (stream.read() != -1) {}
        }
      return status;
    } finally {
      c.disconnect();
    }
  }

  private static void commands(Path project, Fixture f) throws Exception {
    YamlConfiguration catalog = new YamlConfiguration();
    catalog.load(project.resolve("resources/messages.yml").toFile());
    Map<String, String> text = new HashMap<>();
    catalog
        .getValues(true)
        .forEach(
            (k, v) -> {
              if (!(v instanceof ConfigurationSection)) text.put(k, (String) v);
            });
    CoreControl starting =
        new CoreControl() {
          public boolean ready() {
            return false;
          }

          public RuntimeStatus status() {
            return new RuntimeStatus("2.0.2", "1.16.5", "test", false, 0);
          }

          public void reload() {}

          public void prefixesChanged() {}

          public void error(String action, Throwable error) {
            throw new AssertionError(error);
          }
        };
    try (MessageService messages =
        new MessageService(LOG, new ConfigurationSnapshot(f.settings, text))) {
      FeatureCommands commands =
          new FeatureCommands(
              null,
              starting,
              f.graphics,
              f.texts,
              null,
              f.editor,
              f.packs,
              null,
              null,
              messages,
              Runnable::run,
              () -> f.settings);
      Sender player = new Sender();
      check(
          commands.handle(player.player, "linkwifi", new String[] {"linkwifi"}),
          "linkwifi diagnostics are available before core readiness");
      check(
          player.clicks.stream()
              .anyMatch(
                  e ->
                      e.getAction() == ClickEvent.Action.RUN_COMMAND
                          && e.getValue().equals("/tabprefix:lptab linkwifi 127.0.0.1")),
          "Interface row contains a native typed clickable selection");
      check(
          player.lines.stream().anyMatch(s -> s.contains("Wi-Fi names")),
          "UI identifies interfaces accurately without pretending to scan SSIDs");
      List<String> completion = new ArrayList<>();
      commands.complete(player.player, new String[] {"linkwifi", ""}, completion);
      check(
          completion.contains("auto") && completion.contains("127.0.0.1"),
          "Completion offers current registered IPs and automatic mode");
      player.allowed = false;
      player.lines.clear();
      player.clicks.clear();
      commands.handle(player.player, "linkwifi", new String[] {"linkwifi"});
      check(
          player.lines.size() == 1
              && player.clicks.isEmpty()
              && player.lines.get(0).contains("permission"),
          "Unauthorised users receive no interface list");
    }
  }

  private static final class Sender {
    final List<String> lines = new ArrayList<>();
    final List<ClickEvent> clicks = new ArrayList<>();
    boolean allowed = true;
    final Player player;

    Sender() {
      Player.Spigot bridge =
          new Player.Spigot() {
            public void sendMessage(ChatMessageType type, BaseComponent... components) {
              lines.add(BaseComponent.toPlainText(components));
              for (BaseComponent component : components) collect(component);
            }
          };
      player =
          (Player)
              Proxy.newProxyInstance(
                  NetworkIntegrationTest.class.getClassLoader(),
                  new Class<?>[] {Player.class},
                  (proxy, method, args) -> {
                    switch (method.getName()) {
                      case "spigot":
                        return bridge;
                      case "sendMessage":
                        lines.add((String) args[args.length - 1]);
                        return null;
                      case "isOnline":
                        return true;
                      case "hasPermission":
                        return allowed;
                      case "getUniqueId":
                        return OWNER;
                      case "getName":
                        return "NetworkTest";
                      case "getAddress":
                        return new InetSocketAddress("127.0.0.1", 25565);
                      case "hashCode":
                        return System.identityHashCode(proxy);
                      case "equals":
                        return proxy == args[0];
                      default:
                        return method.getReturnType() == boolean.class ? false : null;
                    }
                  });
    }

    void collect(BaseComponent c) {
      if (c.getClickEvent() != null) clicks.add(c.getClickEvent());
      if (c.getExtra() != null) for (BaseComponent child : c.getExtra()) collect(child);
    }
  }
}
