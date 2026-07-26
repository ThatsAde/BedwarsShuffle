# BedwarsShuffle

Addon for [MBedwars](https://www.spigotmc.org/resources/mbedwars.63464/) that periodically makes
players trade teams in pairs, mid match. Every cycle, two players from different teams swap
completely: each one joins the other's team, is teleported to the exact spot the other was
standing in, and inherits the other's bed, team upgrades and armour colour.

**Requires:** MBedwars. Nothing else (no PlaceholderAPI).

> Built as a separate plugin on purpose. Team shuffling is its own game mode and is not meant to
> run at the same time as the closing-border deathmatch in
> [BedwarsFlux](https://github.com/ThatsAde/BedwarsFlux). If it detects that plugin installed with
> deathmatch enabled, it logs a warning at startup.

## How a swap works

1. Every 1 to 3 minutes (randomised per cycle, configurable) players are paired across teams, by
   repeatedly matching one player from each of the two largest teams. This keeps every team at
   exactly the head count it started with, and guarantees both members of a pair change team.
2. Each player is reassigned with `Arena.setPlayerTeam`. Beds and upgrades in MBedwars belong to
   the team rather than to the player, so the new bed and the new team's upgrade levels follow
   automatically. Nothing is copied by hand.
3. On the next tick, each player is teleported to their partner's position (snapshotted before
   anything was changed) and their leather helmet and leggings are re-dyed to the new colour.

## Features

### Pairwise team and position swap
**Pros**
* Mutual swaps, so "A ends up where B was and B where A was" is always well defined. A rotation
   would send A to B's spot but B to somebody else's.
* Head counts never drift: a pair trades one for one, so no team empties out or overfills.
* Works on cloned arena instances, not just the top level arena list, so duos and any other
   cloned setup are covered.
* Spectators and anyone without a team are filtered out before the pairing.

**Cons**
* If a single team holds more than half of all players, the leftovers have nobody to trade with
   and stay put for that cycle. Swapping them with each other would not change anyone's team.
* A player can land on a team whose bed is already destroyed, ending their run early through no
   fault of their own. Under an individual swap this is unavoidable.
* The swap briefly raises the arena's players per team value so the destination team is not
   considered full, then restores it. That fires `ArenaPropertyChangeEvent` twice per cycle, which
   other addons listening for it will see.

### Team armour recolouring
**Pros**
* Pieces are recoloured in place rather than replaced, so Protection from a team upgrade,
   durability and custom names all survive the swap.
* A slot holding chainmail, iron or diamond is left untouched. That is a purchased upgrade, it
   carries no team colour, and replacing it with leather would quietly downgrade the player
   mid fight.
* An empty slot is filled with a freshly dyed leather piece, matching normal spawn behaviour.

**Cons**
* Only the helmet and leggings are handled, which is where the team colour normally lives. If a
   server dyes other slots too, those keep the old colour.
* Enchantments are not re-derived for the new team. A player moving from a team with Protection II
   to one without it keeps Protection II.

### Announcements
**Pros**
* Title, subtitle and chat line are all configurable, with standard `&` colour codes.

**Cons**
* Sent to everyone in the arena at once, with no per player message naming who you swapped with.

### Debug mode
**Pros**
* Off by default, so no console spam in production.
* Logs who traded with whom and the coordinates involved, then re-checks one tick later whether
   each player actually stayed where they were put. That last check is what identifies MBedwars
   overriding the placement, instead of having to guess from in game screenshots.

**Cons**
* Verbose while enabled: several lines per swap cycle per arena.

## Configuration

Full `config.yml`:

```yaml
# Requires a restart after changing.
enabled: true

# Logs every swap to the console: who traded with whom, the coordinates involved, and whether
# each player actually stayed where they were placed. Leave off in production.
debug: false

# Random delay range between shuffles, in seconds.
min_seconds: 60
max_seconds: 180

# Shown to every player in the arena when a shuffle happens.
announce:
  title: "&eTEAM SHUFFLE"
  subtitle: "&7Everyone has been swapped!"
  chat: "&eTeams have been shuffled, check your new team!"
```

Values are read once at startup, so a server restart is needed after editing. For testing, set
`min_seconds: 10` and `max_seconds: 15` with `debug: true`.

## Building

Gradle 8.8 cannot compile the build script under Java 23, so point `JAVA_HOME` at a Java 21 JDK:

```bash
JAVA_HOME="/path/to/jdk-21" ./gradlew build
```

The jar lands in `build/libs/BedwarsShuffle-1.0.jar`.
