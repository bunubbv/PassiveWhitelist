package com.bunubbv.passivewhitelist;

import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class PassiveWhitelist extends JavaPlugin implements Listener, TabExecutor {

    private String answer;
    private String question;
    private String welcomeMessage;
    private String correctMessage;
    private String incorrectMessage;
    private String kickMessage;
    private int kickDelay;

    private final Set<UUID> authenticatedPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> frozenLocations = new HashMap<>();
    private final Map<UUID, BukkitRunnable> kickTasks = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();

    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        loadAuthenticatedPlayers();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("psw")).setExecutor(this);
        Objects.requireNonNull(getCommand("psw")).setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        saveAuthenticatedPlayers();
        kickTasks.values().forEach(BukkitRunnable::cancel);
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();

        answer              = config.getString("answer");
        question            = config.getString("question");
        welcomeMessage      = config.getString("welcomeMessage");
        correctMessage      = config.getString("correctMessage");
        incorrectMessage    = config.getString("incorrectMessage");
        kickMessage         = config.getString("kickMessage");
        kickDelay           = config.getInt("kickDelay");
    }

    private void loadAuthenticatedPlayers() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().severe("Failed to create directory: " + dataFolder.getAbsolutePath());
            return;
        }

        dataFile = new File(dataFolder, "users.yml");
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

        List<String> playerUUIDs = dataConfig.getStringList("users");
        for (String uuidString : playerUUIDs) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                authenticatedPlayers.add(uuid);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID found: " + uuidString);
            }
        }
    }

    private void saveAuthenticatedPlayers() {
        List<String> playerUUIDs = new ArrayList<>();
        for (UUID uuid : authenticatedPlayers) {
            playerUUIDs.add(uuid.toString());
        }
        dataConfig.set("users", playerUUIDs);
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save users.yml: " + e.getMessage());
        }
    }

    private void hidePlayersFromUnverified(Player player) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!authenticatedPlayers.contains(player.getUniqueId())) {
                player.hidePlayer(this, onlinePlayer);
            }
        }
    }

    private void showPlayersToVerified(Player player) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            player.showPlayer(this, onlinePlayer);
        }
    }

    private void blockIfNotVerified(Player p, Cancellable e) {
        if (authenticatedPlayers.contains(p.getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!authenticatedPlayers.contains(player.getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> {
                hidePlayersFromUnverified(player);
                freezePlayer(player);
            });

            Bukkit.getScheduler().runTaskLater(this, () -> {
                BukkitRunnable kickTask = showPlayerQuestion(player);

                long delayTicks = 20L * 60 * kickDelay;
                kickTask.runTaskLater(this, delayTicks);
                kickTasks.put(player.getUniqueId(), kickTask);
            }, 1L);
        }
    }

    private @NotNull BukkitRunnable showPlayerQuestion(Player player) {
        player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(mm.deserialize(welcomeMessage)));
        player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(mm.deserialize(question)));

        return new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && !authenticatedPlayers.contains(player.getUniqueId())) {
                    player.kickPlayer(
                            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                                    .serialize(mm.deserialize(kickMessage))
                    );
                }
            }
        };
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (authenticatedPlayers.contains(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();

        if (message.equalsIgnoreCase(answer)) {
            Bukkit.getScheduler().runTask(this, () -> {

                BukkitRunnable task = kickTasks.remove(player.getUniqueId());
                if (task != null) task.cancel();

                authenticatedPlayers.add(player.getUniqueId());
                showPlayersToVerified(player);
                saveAuthenticatedPlayers();
                unfreezePlayer(player);

                player.sendMessage(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                MiniMessage.miniMessage().deserialize(correctMessage)
                        )
                );
            });
        } else {
            Bukkit.getScheduler().runTask(this, () ->
                    player.sendMessage(
                            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                    MiniMessage.miniMessage().deserialize(incorrectMessage)
                            )
                    )
            );
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (frozenLocations.containsKey(player.getUniqueId())) {
            Location frozenLocation = frozenLocations.get(player.getUniqueId());
            if (!Objects.requireNonNull(event.getTo()).getBlock().getLocation().equals(frozenLocation.getBlock().getLocation())) {
                event.setTo(frozenLocation);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        blockIfNotVerified(event.getPlayer(), event);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        blockIfNotVerified(event.getPlayer(), event);
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        blockIfNotVerified(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        blockIfNotVerified(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        blockIfNotVerified(event.getPlayer(), event);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        if (!authenticatedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);

            Bukkit.getScheduler().runTask(this, player::closeInventory);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            blockIfNotVerified(player, event);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            blockIfNotVerified(player, event);
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            blockIfNotVerified(player, event);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        blockIfNotVerified(event.getPlayer(), event);
    }

    @EventHandler
    public void onEntityDamageByPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            blockIfNotVerified(player, event);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        blockIfNotVerified(event.getPlayer(), event);
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            blockIfNotVerified(player, event);
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getTarget() instanceof Player player)) return;

        if (!authenticatedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            blockIfNotVerified(player, event);
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        blockIfNotVerified(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerInteractWithArmorStand(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();

        if (!authenticatedPlayers.contains(player.getUniqueId())) {
            if (event.getRightClicked() instanceof ArmorStand) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void preventEntityBreaking(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            blockIfNotVerified(player, event);
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            Command command,
            @NotNull String label,
            String @NotNull [] args) {

        if (command.getName().equalsIgnoreCase("psw")) {

            if (args.length == 0) {
                sender.sendMessage("/psw <bypass|reload|revoke>");
                return true;
            }

            String subcommand = args[0].toLowerCase();

            if (args.length >= 2) {
                String targetName = args[1];
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                switch (subcommand) {
                    case "revoke":
                        if (!sender.hasPermission("psw.revoke")) {
                            sender.sendMessage(
                                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                        mm.deserialize("<red>You don't have permission to use this command.</red>")
                                    )
                            );
                            return true;
                        }

                        if (!authenticatedPlayers.contains(target.getUniqueId())) {
                            sender.sendMessage(
                                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                            mm.deserialize("<red>Player " + targetName + " is not verified.</red>")
                                    )
                            );
                            return true;
                        }

                        authenticatedPlayers.remove(target.getUniqueId());
                        saveAuthenticatedPlayers();
                        sender.sendMessage(
                                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                        mm.deserialize("<yellow>Player " + targetName + " is no longer verified.</yellow>")
                                )
                        );

                        if (target.isOnline()) {
                            Player onlinePlayer = target.getPlayer();
                            if (onlinePlayer != null) {
                                if (kickTasks.containsKey(onlinePlayer.getUniqueId())) {
                                    kickTasks.get(onlinePlayer.getUniqueId()).cancel();
                                    kickTasks.remove(onlinePlayer.getUniqueId());
                                }

                                hidePlayersFromUnverified(onlinePlayer);
                                freezePlayer(onlinePlayer);
                                BukkitRunnable kickTask = showPlayerQuestion(onlinePlayer);

                                kickTask.runTaskLater(this, 20L * 60 * kickDelay);
                                kickTasks.put(onlinePlayer.getUniqueId(), kickTask);
                            }
                        }
                        return true;

                    case "bypass":
                        if (!sender.hasPermission("psw.bypass")) {
                            sender.sendMessage(
                                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                            mm.deserialize("<red>You don't have permission to use this command.</red>")
                                    )
                            );
                            return true;
                        }

                        if (authenticatedPlayers.contains(target.getUniqueId())) {
                            sender.sendMessage(
                                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                            mm.deserialize("<red>Player " + targetName + " is already verified.</red>")
                                    )
                            );
                            return true;
                        }

                        authenticatedPlayers.add(target.getUniqueId());
                        saveAuthenticatedPlayers();

                        if (target.isOnline()) {
                            Player onlineTarget = target.getPlayer();
                            if (onlineTarget != null) {
                                showPlayersToVerified(onlineTarget);
                                unfreezePlayer(onlineTarget);
                            }
                        }

                        sender.sendMessage(
                                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                        mm.deserialize("<green>Player " + targetName + " is now verified.</green>")
                                )
                        );
                        return true;
                }
            }

            if (subcommand.equals("reload")) {
                if (!sender.hasPermission("psw.reload")) {

                    sender.sendMessage(
                            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                    mm.deserialize("<red>You don't have permission to use this command.</red>")
                            )
                    );
                    return true;
                }

                reloadConfig();
                loadConfigValues();
                sender.sendMessage(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                                mm.deserialize("<green>Config reloaded successfully!</green>")
                        )
                );
                return true;
            }

            sender.sendMessage("/psw <bypass|reload|revoke>");
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            Command command, @NotNull String alias,
            String @NotNull [] args) {

        if (command.getName().equalsIgnoreCase("psw")) {
            List<String> suggestions = new ArrayList<>();

            if (args.length == 1) {
                if ("bypass".startsWith(args[0].toLowerCase()) && sender.hasPermission("psw.bypass")) {
                    suggestions.add("bypass");
                }
                if ("reload".startsWith(args[0].toLowerCase()) && sender.hasPermission("psw.reload")) {
                    suggestions.add("reload");
                }
                if ("revoke".startsWith(args[0].toLowerCase()) && sender.hasPermission("psw.revoke")) {
                    suggestions.add("revoke");
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("bypass") && sender.hasPermission("psw.bypass")
                        || args[0].equalsIgnoreCase("revoke") && sender.hasPermission("psw.revoke")) {

                    for (UUID uuid : authenticatedPlayers) {
                        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                        if (player.getName() != null && player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                            suggestions.add(player.getName());
                        }
                    }
                }
            }

            return suggestions;
        }

        return Collections.emptyList();
    }

    private void freezePlayer(Player player) {
        frozenLocations.put(player.getUniqueId(), player.getLocation());
    }

    private void unfreezePlayer(Player player) {
        frozenLocations.remove(player.getUniqueId());
    }
}
