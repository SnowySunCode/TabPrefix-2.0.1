package me.snowsun.tabprefix.test;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.ResourcePackAccess;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;
import me.snowsun.tabprefix.presentation.display.PackDelivery;
import me.snowsun.tabprefix.presentation.message.MessageService;
import me.snowsun.tabprefix.util.AtomicFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Production delivery against old/no-ID and modern/multiple-pack event surfaces. */
public final class PackCompatibilityTest {
  private static int checks;

  private static void check(boolean b, String name) {
    if (!b) throw new AssertionError(name);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]),
        root = Files.createTempDirectory(project.resolve(".build/test-data"), "pack-api-");
    boolean modern = args[1].equals("new");
    Logger log = Logger.getLogger("Pack-API");
    log.setUseParentHandlers(false);
    try {
      YamlConfiguration yaml =
          YamlConfiguration.loadConfiguration(project.resolve("resources/config.yml").toFile());
      PluginSettings settings =
          new PluginSettings(yaml, root, MinecraftVersion.parse(modern ? "26.3" : "1.13.2"));
      UUID playerId = UUID.randomUUID(), assetId = UUID.randomUUID();
      List<String> methods = new ArrayList<>();
      List<UUID> added = new ArrayList<>(), removed = new ArrayList<>();
      Player player =
          (Player)
              Proxy.newProxyInstance(
                  PackCompatibilityTest.class.getClassLoader(),
                  new Class<?>[] {Player.class},
                  (p, m, a) -> {
                    if (m.getName().equals("getUniqueId")) return playerId;
                    if (m.getName().equals("getName")) return "Tester";
                    if (m.getName().equals("isOnline")) return true;
                    if (m.getName().equals("addResourcePack")) {
                      methods.add(m.getName());
                      added.add((UUID) a[0]);
                      return null;
                    }
                    if (m.getName().equals("setResourcePack")) {
                      methods.add(m.getName());
                      return null;
                    }
                    if (m.getName().equals("removeResourcePack")) {
                      removed.add((UUID) a[0]);
                      return null;
                    }
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                  });
      Map<String, String> messages = new HashMap<>();
      for (String key : new String[] {"accepted", "loaded", "declined", "failed-download"})
        messages.put("resource-pack." + key, key);
      MessageService replies =
          new MessageService(log, new ConfigurationSnapshot(settings, messages));
      PackBuilder builder = new PackBuilder(settings, null, settings.minecraft, "http://localhost");
      try (ResourcePackService packs = new ResourcePackService(builder, null, (key, v) -> {});
          PackDelivery delivery =
              new PackDelivery(null, packs, replies, (key, v) -> {}, settings)) {
        PackRevision a = revision('a', root, assetId, settings.minecraft),
            b = revision('b', root, assetId, settings.minecraft),
            c = revision('c', root, assetId, settings.minecraft);
        check(delivery.send(player, a) == PackRequestState.Offer.SEND, "first revision offered");
        check(
            methods.size() == 1
                && methods.get(0).equals(modern ? "addResourcePack" : "setResourcePack"),
            "API selected by capability");
        check(
            !delivery.has(playerId, assetId) && delivery.pending(playerId),
            "graphics hidden until loaded");
        if (modern) {
          check(added.get(0).equals(ResourcePackAccess.id(a)), "explicit revision ID");
          delivery.status(event(player, b, "SUCCESSFULLY_LOADED", true));
          check(
              !delivery.has(playerId, assetId) && delivery.pending(playerId),
              "foreign success ignored");
          delivery.status(event(player, a, "DOWNLOADED", true));
          check(delivery.pending(playerId), "download alone is not loaded");
        }
        delivery.status(event(player, a, "ACCEPTED", modern));
        check(delivery.pending(playerId), "accepted is not loaded");
        check(!delivery.canvas(playerId).present(), "canvas hidden until successful load");
        delivery.status(event(player, a, "SUCCESSFULLY_LOADED", modern));
        check(
            delivery.has(playerId, assetId) && !delivery.pending(playerId),
            "matching success unlocks graphics");
        check(delivery.canvas(playerId).present(), "matching success unlocks native canvas");
        check(delivery.send(player, b) == PackRequestState.Offer.SEND, "next revision offered");
        check(
            delivery.send(player, c) == PackRequestState.Offer.QUEUED && methods.size() == 2,
            "requests serialized on every API");
        if (modern) {
          delivery.status(event(player, a, "DISCARDED", true));
          check(delivery.pending(playerId), "foreign terminal failure ignored");
        }
        delivery.status(event(player, b, "FAILED_DOWNLOAD", modern));
        check(
            !delivery.pending(playerId) && !delivery.has(playerId, assetId),
            "failed load uses fallback and clears queue");
        check(!delivery.canvas(playerId).present(), "failed replacement clears canvas");
        if (modern) {
          check(
              removed.contains(ResourcePackAccess.id(a))
                  && removed.contains(ResourcePackAccess.id(b)),
              "failed replacement releases only owned packs");
          for (String status : new String[] {"INVALID_URL", "FAILED_RELOAD", "DISCARDED"}) {
            check(delivery.send(player, c) == PackRequestState.Offer.SEND, "retry after " + status);
            delivery.status(event(player, c, status, true));
            check(!delivery.pending(playerId), "modern terminal status handled: " + status);
          }
        }
        check(
            delivery.send(player, a) == PackRequestState.Offer.SEND, "restart offer after failure");
        delivery.status(event(player, a, "SUCCESSFULLY_LOADED", modern));
        delivery.send(player, b);
        delivery.send(player, c);
        int count = methods.size();
        delivery.status(event(player, b, "SUCCESSFULLY_LOADED", modern));
        check(
            delivery.pending(playerId) && methods.size() == count + 1,
            "queued newest revision sent after success");
        if (modern) {
          delivery.status(event(player, b, "SUCCESSFULLY_LOADED", true));
          check(
              delivery.pending(playerId), "late previous success cannot acknowledge newer request");
        }
        delivery.status(event(player, c, "SUCCESSFULLY_LOADED", modern));
        check(
            delivery.has(playerId, assetId) && !delivery.pending(playerId),
            "newest pack completes");
        if (modern) {
          check(
              removed.stream()
                  .allMatch(
                      id ->
                          id.equals(ResourcePackAccess.id(a))
                              || id.equals(ResourcePackAccess.id(b))
                              || id.equals(ResourcePackAccess.id(c))),
              "no foreign pack removed");
        }
        PluginSettings legacy = new PluginSettings(yaml, root, MinecraftVersion.parse("1.12.2"));
        delivery.reload(legacy);
        int before = methods.size();
        check(
            delivery.send(player, b) == PackRequestState.Offer.SAME && methods.size() == before,
            "1.12 rejects a foreign-format pack");
        check(
            !delivery.has(playerId, assetId),
            "1.12 keeps glyphs from a foreign-format pack hidden");
        check(!delivery.canvas(playerId).present(), "1.12 does not use foreign bitmap canvas");
        PackRevision unicode =
            new PackRevision(
                "dddddddddddddddddddddddddddddddddddddddd",
                "http://localhost/unicode",
                root.resolve("unicode.zip"),
                Collections.singleton(assetId),
                1,
                1,
                3,
                0);
        check(
            delivery.send(player, unicode) == PackRequestState.Offer.SEND,
            "1.12 sends Unicode pack");
        check(!delivery.has(playerId, assetId), "legacy graphics hidden before success");
        delivery.status(event(player, unicode, "SUCCESSFULLY_LOADED", modern));
        check(delivery.has(playerId, assetId), "legacy success unlocks image");
      } finally {
        replies.close();
      }
    } finally {
      AtomicFiles.deleteTree(root);
    }
    System.out.println("PASS: " + checks + " pack compatibility checks (" + args[1] + " API)");
  }

  private static PackRevision revision(
      char digit, Path root, UUID asset, MinecraftVersion version) {
    char[] chars = new char[40];
    Arrays.fill(chars, digit);
    String hash = new String(chars);
    return new PackRevision(
        hash,
        "http://localhost/" + hash,
        root.resolve(hash + ".zip"),
        Collections.singleton(asset),
        1,
        1,
        me.snowsun.tabprefix.domain.ResourcePackFormat.forVersion(version).major,
        0L,
        new NativeHudLayout(
            Collections.singletonMap(
                "test",
                new NativeHudLayout.Profile(
                    "test", "", NativeHudLayout.FIRST, 0, 8, new int[] {5}))));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static PlayerResourcePackStatusEvent event(
      Player player, PackRevision revision, String status, boolean modern) throws Exception {
    Class<?> enumType =
        Class.forName("org.bukkit.event.player.PlayerResourcePackStatusEvent$Status");
    Object value = Enum.valueOf((Class) enumType, status);
    return (PlayerResourcePackStatusEvent)
        (modern
            ? PlayerResourcePackStatusEvent.class
                .getConstructor(Player.class, UUID.class, enumType)
                .newInstance(player, ResourcePackAccess.id(revision), value)
            : PlayerResourcePackStatusEvent.class
                .getConstructor(Player.class, enumType)
                .newInstance(player, value));
  }
}
