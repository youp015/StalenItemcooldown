package nl.stalensmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.*;

public class StalenSMPCooldown extends JavaPlugin implements Listener, CommandExecutor {

    private static final String PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&6StalenCooldown&8] &r");

    public static class ItemRule {
        public int cooldown;
        public List<String> action;

        public ItemRule() {}

        public ItemRule(int cooldown, List<String> action) {
            this.cooldown = cooldown;
            this.action = action;
        }

        public boolean hasAction(String act) {
            return action != null && action.stream().anyMatch(a -> a.equalsIgnoreCase(act));
        }
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<Material, ItemRule> rules = new EnumMap<>(Material.class);
    private final Map<UUID, Map<Material, Long>> activeCooldowns = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pendingRestores = new HashMap<>();
    private final Map<UUID, Long> messageCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        loadOrInitConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("stalencooldown")).setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::processPendingRestores, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, List<ItemStack>> entry : pendingRestores.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null && p.isValid()) {
                for (ItemStack item : entry.getValue()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), item);
                }
            }
        }
        pendingRestores.clear();
        activeCooldowns.clear();
        messageCooldowns.clear();
    }

    public void loadOrInitConfig() {
        rules.clear();

        File folder = new File("plugins/ItemCooldown");
        File configFile = new File(folder, "config.json");

        if (!configFile.exists()) {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            configFile = new File(getDataFolder(), "config.json");
            if (!configFile.exists()) {
                saveResource("config.json", false);
            }
        }

        try (FileReader reader = new FileReader(configFile)) {
            Type type = new TypeToken<Map<String, ItemRule>>() {}.getType();
            Map<String, ItemRule> rawData = gson.fromJson(reader, type);

            if (rawData != null) {
                for (Map.Entry<String, ItemRule> entry : rawData.entrySet()) {
                    Material mat = Material.matchMaterial(entry.getKey().toUpperCase());
                    if (mat != null) {
                        rules.put(mat, entry.getValue());
                    } else {
                        getLogger().warning("Onbekend item genegeerd in config.json: " + entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            getLogger().severe("Fout bij het laden van config.json: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("stalencooldown.admin")) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Geen permissie.");
                return true;
            }
            loadOrInitConfig();
            sender.sendMessage(PREFIX + ChatColor.GREEN + "config.json succesvol herladen!");
            return true;
        }
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "Gebruik: /" + label + " reload");
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvPDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        if (event.getDamager() instanceof EnderPearl) {
            return;
        }

        Player attacker = null;
        if (event.getDamager() instanceof Player directAttacker) {
            attacker = directAttacker;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }

        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        startCombatCooldowns(victim);
        startCombatCooldowns(attacker);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        activeCooldowns.remove(player.getUniqueId());
        messageCooldowns.remove(player.getUniqueId());
    }

    private void startCombatCooldowns(Player player) {
        if (player.hasPermission("stalencooldown.bypass")) return;
        if (rules.isEmpty()) return;

        long now = System.currentTimeMillis();
        Map<Material, Long> playerCds = activeCooldowns.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(Material.class));

        for (Map.Entry<Material, ItemRule> entry : rules.entrySet()) {
            Material mat = entry.getKey();
            ItemRule rule = entry.getValue();

            playerCds.put(mat, now + (rule.cooldown * 1000L));
            player.setCooldown(mat, rule.cooldown * 20);

            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    long remainingMs = getRemainingMillis(player.getUniqueId(), mat);
                    if (remainingMs > 0) {
                        int ticks = (int) Math.max(1, (remainingMs * 20L) / 1000L);
                        player.setCooldown(mat, ticks);
                    }
                }
            });

            if (rule.hasAction("equip")) {
                handleForceUnequip(player, mat);
            }
        }
    }

    private void handleForceUnequip(Player player, Material targetMaterial) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        boolean changed = false;

        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece != null && piece.getType() == targetMaterial) {
                armor[i] = null;
                changed = true;
                storeOrHoldItem(player, piece);
            }
        }

        if (changed) {
            inv.setArmorContents(armor);
            String name = targetMaterial.name().toLowerCase().replace('_', ' ');
            player.sendMessage(PREFIX + ChatColor.RED + "Je " + ChatColor.YELLOW + name + ChatColor.RED + " is uitgetrokken wegens combat!");
        }
    }

    private void storeOrHoldItem(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            pendingRestores.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).addAll(overflow.values());
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Je inventory was vol; je armor wordt tijdelijk veilig bewaard.");
        }
    }

    private void processPendingRestores() {
        if (pendingRestores.isEmpty()) return;

        Iterator<Map.Entry<UUID, List<ItemStack>>> it = pendingRestores.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<ItemStack>> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            List<ItemStack> pending = entry.getValue();
            Iterator<ItemStack> itemIt = pending.iterator();

            while (itemIt.hasNext()) {
                ItemStack item = itemIt.next();
                HashMap<Integer, ItemStack> remaining = player.getInventory().addItem(item);
                if (remaining.isEmpty()) {
                    itemIt.remove();
                    String name = item.getType().name().toLowerCase().replace('_', ' ');
                    player.sendMessage(PREFIX + ChatColor.GREEN + "Opgeslagen item hersteld naar je inventory: " + ChatColor.YELLOW + name);
                } else {
                    item.setAmount(remaining.get(0).getAmount());
                    break;
                }
            }

            if (pending.isEmpty()) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.hasPermission("stalencooldown.bypass")) return;

        if (event.isGliding() && isActionBlocked(player, Material.ELYTRA, "use")) {
            event.setCancelled(true);
            sendBlockedMessage(player, Material.ELYTRA);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRiptide(PlayerRiptideEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("stalencooldown.bypass")) return;

        if (isActionBlocked(player, Material.TRIDENT, "use")) {
            sendBlockedMessage(player, Material.TRIDENT);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) return;
        if (player.hasPermission("stalencooldown.bypass")) return;

        Material mat = null;
        if (projectile instanceof EnderPearl) {
            mat = Material.ENDER_PEARL;
        } else if (projectile instanceof Trident) {
            mat = Material.TRIDENT;
        }

        if (mat != null && isActionBlocked(player, mat, "use")) {
            event.setCancelled(true);
            sendBlockedMessage(player, mat);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("stalencooldown.bypass")) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        Material mat = item.getType();
        if (!isOnCooldown(player.getUniqueId(), mat)) return;

        ItemRule rule = rules.get(mat);
        if (rule == null) return;

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            boolean blockUse = rule.hasAction("use");
            boolean blockEquip = rule.hasAction("equip") && isEquipable(mat);

            if (blockUse || blockEquip) {
                event.setCancelled(true);
                sendBlockedMessage(player, mat);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.hasPermission("stalencooldown.bypass")) return;

        Material mat = event.getItem().getItemStack().getType();
        if (isActionBlocked(player, mat, "pickup")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.hasPermission("stalencooldown.bypass")) return;

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        InventoryType topType = event.getView().getTopInventory().getType();
        boolean isContainerView = isContainerInventory(topType);

        if (isContainerView) {
            if (event.getClickedInventory() != null && event.getClickedInventory() == event.getView().getTopInventory()) {
                if (current != null && isActionBlocked(player, current.getType(), "containers")) {
                    event.setCancelled(true);
                    sendBlockedMessage(player, current.getType());
                    return;
                }
            }

            if (event.isShiftClick() && event.getClickedInventory() == player.getInventory()) {
                if (current != null && isActionBlocked(player, current.getType(), "containers")) {
                    event.setCancelled(true);
                    sendBlockedMessage(player, current.getType());
                    return;
                }
            }

            if (event.getClickedInventory() == event.getView().getTopInventory()) {
                if (cursor != null && isActionBlocked(player, cursor.getType(), "containers")) {
                    event.setCancelled(true);
                    sendBlockedMessage(player, cursor.getType());
                    return;
                }
            }
        }

        if (current != null && current.getType() == Material.BUNDLE) {
            if (cursor != null && isActionBlocked(player, cursor.getType(), "containers")) {
                event.setCancelled(true);
                sendBlockedMessage(player, cursor.getType());
                return;
            }
        }
        if (cursor != null && cursor.getType() == Material.BUNDLE) {
            if (current != null && isActionBlocked(player, current.getType(), "containers")) {
                event.setCancelled(true);
                sendBlockedMessage(player, current.getType());
                return;
            }
        }

        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            if (cursor != null && isActionBlocked(player, cursor.getType(), "equip")) {
                event.setCancelled(true);
                sendBlockedMessage(player, cursor.getType());
                return;
            }
            if (event.getClick() == ClickType.NUMBER_KEY) {
                ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
                if (hotbar != null && isActionBlocked(player, hotbar.getType(), "equip")) {
                    event.setCancelled(true);
                    sendBlockedMessage(player, hotbar.getType());
                    return;
                }
            }
        }

        if (event.isShiftClick() && current != null && isEquipable(current.getType())) {
            if (isActionBlocked(player, current.getType(), "equip")) {
                event.setCancelled(true);
                sendBlockedMessage(player, current.getType());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.hasPermission("stalencooldown.bypass")) return;

        ItemStack dragged = event.getOldCursor();
        if (dragged == null || dragged.getType() == Material.AIR) return;

        Material mat = dragged.getType();
        if (!isActionBlocked(player, mat, "containers")) return;

        boolean touchesTop = false;
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                touchesTop = true;
                break;
            }
        }

        if (touchesTop && isContainerInventory(event.getView().getTopInventory().getType())) {
            event.setCancelled(true);
            sendBlockedMessage(player, mat);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Map<Material, Long> playerCds = activeCooldowns.get(player.getUniqueId());
        if (playerCds == null || playerCds.isEmpty()) return;

        for (Map.Entry<Material, Long> entry : playerCds.entrySet()) {
            long remainingMs = entry.getValue() - System.currentTimeMillis();
            if (remainingMs > 0) {
                int ticks = (int) Math.max(1, (remainingMs * 20L) / 1000L);
                player.setCooldown(entry.getKey(), ticks);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activeCooldowns.remove(event.getPlayer().getUniqueId());
        messageCooldowns.remove(event.getPlayer().getUniqueId());
    }

    private boolean isActionBlocked(Player player, Material mat, String action) {
        if (player.hasPermission("stalencooldown.bypass")) return false;
        if (!isOnCooldown(player.getUniqueId(), mat)) return false;
        ItemRule rule = rules.get(mat);
        return rule != null && rule.hasAction(action);
    }

    private boolean isOnCooldown(UUID uuid, Material mat) {
        Map<Material, Long> playerCds = activeCooldowns.get(uuid);
        if (playerCds == null || !playerCds.containsKey(mat)) return false;

        long expiry = playerCds.get(mat);
        if (System.currentTimeMillis() >= expiry) {
            playerCds.remove(mat);
            return false;
        }
        return true;
    }

    private long getRemainingMillis(UUID uuid, Material mat) {
        Map<Material, Long> playerCds = activeCooldowns.get(uuid);
        if (playerCds == null || !playerCds.containsKey(mat)) return 0;
        return Math.max(0, playerCds.get(mat) - System.currentTimeMillis());
    }

    private int getRemainingSeconds(UUID uuid, Material mat) {
        long diff = getRemainingMillis(uuid, mat);
        return (int) Math.max(0, Math.ceil(diff / 1000.0));
    }

    private boolean isContainerInventory(InventoryType type) {
        return type == InventoryType.CHEST
                || type == InventoryType.BARREL
                || type == InventoryType.SHULKER_BOX
                || type == InventoryType.DISPENSER
                || type == InventoryType.DROPPER
                || type == InventoryType.HOPPER
                || type == InventoryType.ENDER_CHEST;
    }

    private boolean isEquipable(Material mat) {
        String name = mat.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || mat == Material.ELYTRA;
    }

    private void sendBlockedMessage(Player player, Material mat) {
        long now = System.currentTimeMillis();
        Long last = messageCooldowns.get(player.getUniqueId());
        if (last != null && (now - last) < 5000L) {
            return;
        }
        messageCooldowns.put(player.getUniqueId(), now);

        int seconds = getRemainingSeconds(player.getUniqueId(), mat);
        String name = mat.name().toLowerCase().replace('_', ' ');
        player.sendMessage(PREFIX + ChatColor.RED + "Je kunt " + ChatColor.YELLOW + name + ChatColor.RED + " niet gebruiken voor nog " + ChatColor.YELLOW + seconds + "s" + ChatColor.RED + ".");
    }
}
