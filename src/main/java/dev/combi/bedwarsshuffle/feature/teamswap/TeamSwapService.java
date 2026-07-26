package dev.combi.bedwarsshuffle.feature.teamswap;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import dev.combi.bedwarsshuffle.config.TeamSwapConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Periodically swaps players between teams in pairs: A takes B's team and ends up standing
 * exactly where B was, while B takes A's team and A's position.
 *
 * <p>Beds and upgrades in MBedwars belong to the (arena, team-color) pair, not to the
 * player — so once {@link Arena#setPlayerTeam} relinks a player to a new team, that
 * player automatically owns the new team's bed and upgrade state. No bed/upgrade copying
 * is performed here.</p>
 *
 * <p><b>Capacity caveat:</b> {@code setPlayerTeam} does nothing at all when the destination
 * team already holds {@code playersPerTeam} players. A pair trades one-for-one, so the
 * destination is still full at the moment we ask — the partner has not left yet. Hence
 * {@link #performSwap} temporarily raises the per-team cap for the duration of the
 * reassignment and restores it afterwards; without that, every call silently no-ops.</p>
 *
 * <p><b>Two-phase by necessity:</b> all team reassignments happen first, then on the following
 * tick each player is teleported and their armour re-dyed ({@link #placeAtPartnerSpots}).
 * Teleporting inline, right after each {@code setPlayerTeam}, does not stick — MBedwars is
 * still processing the team change during that tick and repositions the player afterwards.
 * Positions are snapshotted before any mutation so each destination is where the partner
 * stood pre-swap.</p>
 *
 * <p><b>Armour does not follow the team on its own</b>, confirmed in live play, so
 * {@link #applyTeamArmour} re-dyes the helmet and leggings explicitly. Scoreboard/nametag
 * colour and team potion effects are the remaining unverified ones — see
 * {@link #onPlayerReassigned}. Enabling {@code debug} in the config reports the pairings and
 * whether our placement survived the tick after it.</p>
 */
public final class TeamSwapService {

    private final JavaPlugin plugin;
    private final TeamSwapConfig cfg;

    private static final class SwapState {
        int secondsUntilSwap;
    }

    // Keyed by Arena identity so cloned arena instances each get independent state.
    private final Map<Arena, SwapState> states = new ConcurrentHashMap<>();

    public TeamSwapService(JavaPlugin plugin, TeamSwapConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void startTicking() {
        if (!cfg.enabled) return;

        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var game = BedwarsAPI.getGameAPI();
            if (game == null) return;

            for (Arena arena : runningArenas(game.getArenas())) {
                SwapState st = states.computeIfAbsent(arena, k -> freshState());

                if (--st.secondsUntilSwap <= 0) {
                    performSwap(arena);
                    st.secondsUntilSwap = randomInterval();
                }
            }
        }, 20L, 20L);
    }

    public void release(Arena arena) {
        if (arena != null) states.remove(arena);
    }

    // --- arena discovery (clone-aware) ---

    /**
     * Template arenas plus every one of their live clones, deduplicated, filtered to
     * arenas that are actually mid-round. Cloned/duos arenas are not reliably reachable
     * through the top-level arena list alone.
     */
    private static Set<Arena> runningArenas(Collection<Arena> templates) {
        Set<Arena> all = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Arena template : templates) {
            all.add(template);
            Arena[] clones = template.getClones();
            if (clones != null) Collections.addAll(all, clones);
        }
        all.removeIf(a -> a.getStatus() != ArenaStatus.RUNNING || a.getGameWorld() == null);
        return all;
    }

    private SwapState freshState() {
        SwapState st = new SwapState();
        st.secondsUntilSwap = randomInterval();
        return st;
    }

    private int randomInterval() {
        return ThreadLocalRandom.current().nextInt(cfg.minSeconds, cfg.maxSeconds + 1);
    }

    // --- the swap itself ---

    private void performSwap(Arena arena) {
        // Snapshot everything up front: teams and positions are both read before anything is
        // mutated, so each player's destination is where their partner stood *before* the swap.
        List<Player> players = new ArrayList<>(arena.getPlayers());
        if (players.size() < 2) return;

        List<Team> teams = new ArrayList<>(players.size());
        for (Player p : players) teams.add(arena.getPlayerTeam(p));

        // Anyone without a team (spectators) can't take part. Drop them so a null never
        // enters the pairing — setPlayerTeam(player, null) throws outside the lobby.
        for (int i = players.size() - 1; i >= 0; i--) {
            if (teams.get(i) == null) {
                players.remove(i);
                teams.remove(i);
            }
        }
        if (players.size() < 2) return;

        // Fewer than 2 distinct teams present -> nobody has anyone to trade with.
        if (new HashSet<>(teams).size() < 2) return;

        List<Location> spots = new ArrayList<>(players.size());
        for (Player p : players) spots.add(p.getLocation());

        int[] partner = matchPartners(teams);

        // setPlayerTeam() silently does nothing when the destination team is already at
        // playersPerTeam capacity. Every pair trades one-for-one, so at the moment we ask,
        // the destination team is still full — the partner hasn't left it yet. Without
        // lifting the cap first, every single call no-ops.
        final int originalPerTeam = arena.getPlayersPerTeam();
        final int roomForEveryone = Math.max(originalPerTeam, players.size());
        final boolean liftCap = roomForEveryone != originalPerTeam;

        // Player -> where they're going and which team they now belong to. Bodies and armour
        // are both handled after this tick.
        Map<Player, Placement> toPlace = new LinkedHashMap<>();

        try {
            if (liftCap) arena.setPlayersPerTeam(roomForEveryone);

            for (int i = 0; i < players.size(); i++) {
                int j = partner[i];
                if (j < 0) continue; // unpaired: majority team with nobody left to trade with

                Player p = players.get(i);
                Team from = teams.get(i);
                Team to = teams.get(j);

                arena.setPlayerTeam(p, to);

                // Confirm the change took effect before we bother moving the player.
                Team actual = arena.getPlayerTeam(p);
                if (actual != to) {
                    plugin.getLogger().warning("Could not move " + p.getName() + " from " + from
                            + " to " + to + " in arena '" + arena.getName() + "' (still " + actual
                            + ") — leaving them where they are.");
                    continue;
                }

                toPlace.put(p, new Placement(spots.get(j), from, to));
            }
        } finally {
            // Always restore, even if a reassignment blew up — a leaked raised cap would let
            // the arena over-fill on the next join.
            if (liftCap) arena.setPlayersPerTeam(originalPerTeam);
        }

        if (toPlace.isEmpty()) return;

        if (cfg.debug) logPairs(arena, players, teams, spots, partner);

        // Teleport on the NEXT tick, not inline above. Reassigning a team (and raising then
        // restoring playersPerTeam around it) makes MBedwars do its own bookkeeping for these
        // players during the current tick, which can reposition them — so a teleport issued
        // inline gets overwritten and players appear not to move. Deferring one tick lets
        // MBedwars finish first and leaves us with the final say on where everyone stands.
        Bukkit.getScheduler().runTask(plugin, () -> placeAtPartnerSpots(arena, toPlace));

        announce(arena, players);
    }

    /** Where a swapped player is headed, and the teams they moved between. */
    private record Placement(Location spot, Team from, Team to) {}

    /**
     * Moves each player to the position their swap partner occupied and re-dyes their team
     * armour. Uses {@code arena.teleport} rather than {@code Player#teleport} because MBedwars
     * otherwise treats a teleport inside an arena as an escape attempt and kicks the player out.
     */
    private void placeAtPartnerSpots(Arena arena, Map<Player, Placement> toPlace) {
        if (arena.getStatus() != ArenaStatus.RUNNING) return;

        toPlace.forEach((p, placement) -> {
            // They may have died or left during the tick we waited.
            if (!p.isOnline() || arena.isSpectating(p)) return;

            arena.teleport(p, placement.spot());
            applyTeamArmour(p, placement.to());
            onPlayerReassigned(arena, p, placement.from(), placement.to());

            if (cfg.debug) {
                plugin.getLogger().info("[debug] " + p.getName() + " re-dyed to "
                        + placement.to() + " armour.");
            }
        });

        if (cfg.debug) {
            // The diagnostic that four blind test rounds could not answer: did our placement
            // actually stick, or does MBedwars move these players again after us?
            Bukkit.getScheduler().runTask(plugin, () -> verifyPlacement(arena, toPlace));
        }
    }

    /**
     * Re-dyes the player's helmet and leggings to their new team's colour.
     *
     * <p>The existing pieces are <b>recoloured in place</b> rather than replaced, so any
     * Protection enchantment from a team upgrade, plus durability and custom names, survive
     * the swap. A slot holding non-leather armour is left untouched: that is a purchased
     * chainmail/iron/diamond upgrade, it carries no team colour, and swapping it for leather
     * would quietly downgrade the player mid-fight.</p>
     */
    private static void applyTeamArmour(Player player, Team team) {
        var inv = player.getInventory();
        inv.setHelmet(teamColoured(inv.getHelmet(), Material.LEATHER_HELMET, team));
        inv.setLeggings(teamColoured(inv.getLeggings(), Material.LEATHER_LEGGINGS, team));
    }

    private static ItemStack teamColoured(ItemStack current, Material leatherType, Team team) {
        boolean empty = current == null || current.getType().isAir();

        // An upgrade occupies this slot — leave it alone.
        if (!empty && current.getType() != leatherType) return current;

        ItemStack piece = empty ? new ItemStack(leatherType) : current;

        if (piece.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(team.getBukkitColor());
            piece.setItemMeta(meta);
        }
        return piece;
    }

    /** Debug-only: reports players who did not end up where we put them. */
    private void verifyPlacement(Arena arena, Map<Player, Placement> intended) {
        intended.forEach((p, placement) -> {
            if (!p.isOnline()) return;

            Location target = placement.spot();
            Location actual = p.getLocation();
            if (!Objects.equals(actual.getWorld(), target.getWorld())
                    || actual.distance(target) > 1.0) {
                plugin.getLogger().warning("[debug] " + p.getName() + " was placed at "
                        + describe(target) + " but is now at " + describe(actual)
                        + " — something moved them after us (arena '" + arena.getName() + "').");
            } else {
                plugin.getLogger().info("[debug] " + p.getName() + " placement held at "
                        + describe(actual) + ".");
            }
        });
    }

    private void logPairs(Arena arena, List<Player> players, List<Team> teams,
                          List<Location> spots, int[] partner) {
        int pairs = 0;
        for (int j : partner) if (j >= 0) pairs++;

        plugin.getLogger().info("[debug] Shuffling arena '" + arena.getName() + "': "
                + players.size() + " players, " + (pairs / 2) + " pair(s).");

        for (int i = 0; i < partner.length; i++) {
            int j = partner[i];
            if (j < 0) {
                plugin.getLogger().info("[debug]   " + players.get(i).getName() + " " + teams.get(i)
                        + " — unpaired, staying put.");
            } else if (i < j) { // log each mutual pair once
                plugin.getLogger().info("[debug]   " + players.get(i).getName() + " " + teams.get(i)
                        + " @" + describe(spots.get(i)) + "  <->  " + players.get(j).getName()
                        + " " + teams.get(j) + " @" + describe(spots.get(j)));
            }
        }
    }

    private static String describe(Location loc) {
        return String.format("%.1f,%.1f,%.1f", loc.getX(), loc.getY(), loc.getZ());
    }

    /**
     * Extension point: called once per player after their team has changed, they've been
     * teleported, and their armour has been re-dyed. Currently a no-op.
     *
     * <p>Armour colour used to be listed here as "expected to re-sync on its own" — it does
     * not, which is why {@link #applyTeamArmour} exists. Remaining candidates for the same
     * treatment, if live play shows they don't follow the team either: scoreboard/nametag
     * colour ({@code ScoreboardUpdateCause.PLAYER_TEAM_CHANGE} via
     * {@code GameAPI.getScoreboardHandler().update(...)}), and team potion effects
     * ({@code Arena.getTeamPermanentEffects} / {@code getTeamBaseOnlyEffects}).</p>
     */
    private void onPlayerReassigned(Arena arena, Player player, Team from, Team to) {
        // Intentionally empty — see javadoc for what would go here.
    }

    /**
     * Pairs players off so that each pair swaps mutually: partner[i] == j and partner[j] == i,
     * with the two always on different teams. {@code -1} means a player found no partner.
     *
     * <p>Because a pair trades one-for-one between two teams, every team keeps exactly the
     * head-count it started with, and both members of a pair are guaranteed to change team.
     * Mutual pairing is also what makes "A ends up where B was and B where A was" well
     * defined — a general rotation would send A to B's spot but B to somebody else's.</p>
     *
     * <p>Pairs are formed by repeatedly taking the two largest teams and matching one player
     * from each, which maximises the number of swaps. The only players left unpaired are in a
     * team holding more than half of everyone, since once every other team is exhausted its
     * remaining members would have to swap with each other — which would not change anyone's
     * team. Those players stay put.</p>
     */
    private static int[] matchPartners(List<Team> teams) {
        int[] partner = new int[teams.size()];
        Arrays.fill(partner, -1);

        // Group player indices by team, each group shuffled so pairings vary between cycles.
        Map<Team, List<Integer>> byTeam = new EnumMap<>(Team.class);
        for (int i = 0; i < teams.size(); i++) {
            byTeam.computeIfAbsent(teams.get(i), t -> new ArrayList<>()).add(i);
        }
        Random rnd = ThreadLocalRandom.current();
        for (List<Integer> group : byTeam.values()) Collections.shuffle(group, rnd);

        // Always drain the two largest groups: any other order can strand players in a big
        // team while small teams run dry.
        List<List<Integer>> groups = new ArrayList<>(byTeam.values());
        while (true) {
            groups.sort(Comparator.comparingInt(List<Integer>::size).reversed());
            if (groups.size() < 2 || groups.get(1).isEmpty()) break;

            List<Integer> a = groups.get(0);
            List<Integer> b = groups.get(1);
            int i = a.remove(a.size() - 1);
            int j = b.remove(b.size() - 1);

            partner[i] = j;
            partner[j] = i;
        }

        return partner;
    }

    // --- announcement ---

    private void announce(Arena arena, List<Player> players) {
        var title = Title.title(
                Component.text(colorize(cfg.title)),
                Component.text(colorize(cfg.subtitle)),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(400))
        );
        String chatMsg = colorize(cfg.chat);
        for (Player p : players) {
            p.showTitle(title);
            if (!cfg.chat.isEmpty()) p.sendMessage(Component.text(chatMsg));
        }
    }

    private static String colorize(String s) { return s == null ? "" : s.replace('&', '§'); }
}
