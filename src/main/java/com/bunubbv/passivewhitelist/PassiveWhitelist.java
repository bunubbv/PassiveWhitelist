package com.bunubbv.passivewhitelist;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PassiveWhitelist extends JavaPlugin implements Listener, TabExecutor {

    private String answer;
    private String question;
    private String welcomeMessage;
    private String correctMessage;
    private String incorrectMessage;
    private String kickMessage;
    private int kickDelay;

    private final Set<UUID> verified = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Location> frozen = new HashMap<>();
    private final Map<UUID, BukkitRunnable> kickTasks = new HashMap<>();

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        loadSettings();
        loadUsers();

        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("psw")).setExecutor(this);
        Objects.requireNonNull(getCommand("psw")).setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        saveUsers();

        for (BukkitRunnable task : kickTasks.values()) {
            task.cancel();
        }

        kickTasks.clear();
    }

    private void loadSettings() {
        FileConfiguration config = getConfig();

        answer = config.getString("answer");
        question = config.getString("question");
        welcomeMessage = config.getString("welcomeMessage");
        correctMessage = config.getString("correctMessage");
        incorrectMessage = config.getString("incorrectMessage");
        kickMessage = config.getString("kickMessage");
        kickDelay = config.getInt("kickDelay");
    }

    private void loadUsers() {
        File folder = getDataFolder();

        if (!folder.exists() && !folder.mkdirs()) {
            getLogger().severe("Failed to create directory: " + folder.getAbsolutePath());
            return;
        }

        dataFile = new File(folder, "users.yml");

        if (!dataFile.exists()) {
            try {
                if (!dataFile.createNewFile()) {
                    getLogger().severe("Failed to create users.yml.");
                    return;
                }
            } catch (IOException e) {
                getLogger().severe("Failed to create users.yml: " + e.getMessage());
                return;
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        verified.clear();
        names.clear();

        for (String value : dataConfig.getStringList("users")) {
            try {
                UUID uuid = UUID.fromString(value);

                verified.add(uuid);
                cacheName(uuid);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID found: " + value);
            }
        }
    }

    private void saveUsers() {
        if (dataConfig == null || dataFile == null) {
            return;
        }

        List<String> users = new ArrayList<>();

        for (UUID uuid : verified) {
            users.add(uuid.toString());
        }

        users.sort(String.CASE_INSENSITIVE_ORDER);

        dataConfig.set("users", users);

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save users.yml: " + e.getMessage());
        }
    }

    private void cacheName(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        String name = player.getName();

        if (name != null) {
            names.put(uuid, name);
        }
    }

    private boolean verified(Player player) {
        return verified.contains(player.getUniqueId());
    }

    private boolean verified(UUID uuid) {
        return verified.contains(uuid);
    }

    private void block(Player player, Cancellable event) {
        if (!verified(player)) {
            event.setCancelled(true);
        }
    }

    private String text(String message) {
        if (message == null) {
            return "";
        }

        return legacy.serialize(mm.deserialize(message));
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(text(message));
    }

    private void hideAll(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                player.hidePlayer(this, other);
            }
        }
    }

    private void showAll(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                player.showPlayer(this, other);
            }
        }
    }

    private void freeze(Player player) {
        frozen.put(player.getUniqueId(), player.getLocation());
    }

    private void unfreeze(Player player) {
        frozen.remove(player.getUniqueId());
    }

    private void cancelKick(UUID uuid) {
        BukkitRunnable task = kickTasks.remove(uuid);

        if (task != null) {
            task.cancel();
        }
    }

    private void challenge(Player player) {
        UUID uuid = player.getUniqueId();

        cancelKick(uuid);
        hideAll(player);
        freeze(player);

        send(player, welcomeMessage);
        send(player, question);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && !verified(player)) {
                    player.kickPlayer(text(kickMessage));
                }
            }
        };

        task.runTaskLater(this, 20L * 60 * kickDelay);
        kickTasks.put(uuid, task);
    }

    private void accept(Player player) {
        UUID uuid = player.getUniqueId();

        verified.add(uuid);
        names.put(uuid, player.getName());

        cancelKick(uuid);
        showAll(player);
        unfreeze(player);
        saveUsers();

        send(player, correctMessage);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (verified(player)) {
            names.put(player.getUniqueId(), player.getName());
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> challenge(player));
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (verified(player)) {
            return;
        }

        event.setCancelled(true);

        if (answer != null && event.getMessage().equalsIgnoreCase(answer)) {
            Bukkit.getScheduler().runTask(this, () -> accept(player));
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> send(player, incorrectMessage));
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location location = frozen.get(player.getUniqueId());

        if (location == null || event.getTo() == null) {
            return;
        }

        if (!event.getTo().getBlock().getLocation().equals(location.getBlock().getLocation())) {
            event.setTo(location);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!verified(player)) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this, player::closeInventory);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            block(player, event);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            block(player, event);
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            block(player, event);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler
    public void onEntityDamageByPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            block(player, event);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            block(player, event);
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }

        if (!verified(player)) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            block(player, event);
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler
    public void onArmorStandInteract(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand) {
            block(event.getPlayer(), event);
        }
    }

    @EventHandler
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            block(player, event);
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            Command command,
            @NotNull String label,
            String @NotNull [] args) {

        if (!command.getName().equalsIgnoreCase("psw")) {
            return false;
        }

        if (args.length == 0) {
            usage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload":
                return reload(sender);

            case "bypass":
                return bypass(sender, args);

            case "revoke":
                return revoke(sender, args);

            default:
                usage(sender);
                return true;
        }
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("psw.reload")) {
            deny(sender);
            return true;
        }

        reloadConfig();
        loadSettings();

        send(sender, "<green>Config reloaded successfully!</green>");
        return true;
    }

    private boolean bypass(CommandSender sender, String[] args) {
        if (!sender.hasPermission("psw.bypass")) {
            deny(sender);
            return true;
        }

        if (args.length < 2) {
            send(sender, "/psw bypass <player>");
            return true;
        }

        OfflinePlayer target = player(args[1]);
        UUID uuid = target.getUniqueId();
        String name = nameOf(uuid, target.getName(), args[1]);

        if (verified(uuid)) {
            send(sender, "<red>Player " + name + " is already verified.</red>");
            return true;
        }

        verified.add(uuid);

        if (target.getName() != null) {
            names.put(uuid, target.getName());
        }

        Player online = Bukkit.getPlayer(uuid);

        if (online != null) {
            names.put(uuid, online.getName());
            cancelKick(uuid);
            showAll(online);
            unfreeze(online);
        }

        saveUsers();

        send(sender, "<green>Player " + name + " is now verified.</green>");
        return true;
    }

    private boolean revoke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("psw.revoke")) {
            deny(sender);
            return true;
        }

        if (args.length < 2) {
            send(sender, "/psw revoke <player>");
            return true;
        }

        UUID uuid = findUser(args[1]);

        if (uuid == null) {
            send(sender, "<red>Player " + args[1] + " is not verified.</red>");
            return true;
        }

        String name = nameOf(uuid, null, args[1]);

        verified.remove(uuid);
        names.remove(uuid);
        saveUsers();

        Player online = Bukkit.getPlayer(uuid);

        if (online != null) {
            challenge(online);
        }

        send(sender, "<yellow>Player " + name + " is no longer verified.</yellow>");
        return true;
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer player(String name) {
        return Bukkit.getOfflinePlayer(name);
    }

    private UUID findUser(String name) {
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }

        OfflinePlayer player = player(name);
        UUID uuid = player.getUniqueId();

        return verified(uuid) ? uuid : null;
    }

    private String nameOf(UUID uuid, String found, String fallback) {
        Player online = Bukkit.getPlayer(uuid);

        if (online != null) {
            return online.getName();
        }

        if (found != null) {
            return found;
        }

        String cached = names.get(uuid);

        if (cached != null) {
            return cached;
        }

        return fallback;
    }

    private void usage(CommandSender sender) {
        send(sender, "/psw <bypass|reload|revoke>");
    }

    private void deny(CommandSender sender) {
        send(sender, "<red>You don't have permission to use this command.</red>");
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            Command command,
            @NotNull String alias,
            String @NotNull [] args) {

        if (!command.getName().equalsIgnoreCase("psw")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return completeSub(sender, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("bypass") && sender.hasPermission("psw.bypass")) {
                return completeBypass(args[1]);
            }

            if (sub.equals("revoke") && sender.hasPermission("psw.revoke")) {
                return completeRevoke(args[1]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> completeSub(CommandSender sender, String input) {
        List<String> result = new ArrayList<>();

        addSub(result, sender, input, "bypass", "psw.bypass");
        addSub(result, sender, input, "reload", "psw.reload");
        addSub(result, sender, input, "revoke", "psw.revoke");

        return result;
    }

    private void addSub(
            List<String> result,
            CommandSender sender,
            String input,
            String sub,
            String permission) {

        if (!sender.hasPermission(permission)) {
            return;
        }

        if (sub.startsWith(input.toLowerCase(Locale.ROOT))) {
            result.add(sub);
        }
    }

    private List<String> completeBypass(String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (verified(player)) {
                continue;
            }

            String name = player.getName();

            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(name);
            }
        }

        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private List<String> completeRevoke(String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();

        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (!verified(entry.getKey())) {
                continue;
            }

            String name = entry.getValue();

            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(name);
            }
        }

        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }
}
