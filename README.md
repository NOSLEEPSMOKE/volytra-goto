# Volytra Goto

A [Meteor Client](https://meteorclient.com) addon that flies you somewhere with an elytra on its own, the way Baritone
walks you somewhere: give it coordinates, it plans a 3D route through the chunks it can see and steers
[VolytraFly](https://github.com/Volizray/VolytraFly-Addon) along it, through Nether caves as well as open sky.

It was built and tested on 6b6t (Minecraft 1.21.11), and its timing is tuned to what that server accepts. It is a
hacked-client module: only use it on servers whose rules allow that.

## What it does

- Plans a route through loaded terrain and re-plans as new chunks arrive; waits at the edge of unloaded chunks instead
  of flying blind.
- Takes off by itself, and takes off again after the server closes the elytra or pulls you back.
- Keeps away from lava, fire and mobs; swims out of lava if it ends up in it.
- Digs a 1x1 tunnel and crawls through it when there is no way round.
- Swaps to a fresher elytra, or mends the worn one with XP bottles, before it breaks.
- Lands (or hovers) at the destination. It can also stop at the first Nether portal it passes.

## Requirements

- Minecraft 1.21.11 with Fabric Loader
- Meteor Client (for 1.21.11)
- VolytraFly 0.1.1

## Install

Download `volytra-goto-<version>.jar` from the [Releases page](https://github.com/NoSleepSmoke/volytra-goto/releases) and put it in your `.minecraft/mods` folder, next to
Meteor Client and VolytraFly. That is the whole install: one jar, no extra files.

## Use

| command | what it does |
| --- | --- |
| `.vgoto x z` | fly to x z and land |
| `.vgoto x y z` | fly to x y z and hover there |
| `.vgoto ow x z` | Overworld coordinates; in the Nether it flies to x/8 z/8 |
| `.vgoto stop` | stop |
| `.vgoto` | where it is going and how far is left |

`.vg` works as a short form. The module is under Movement as `volytra-goto`; turning it on flies to the x / z in its
settings.

## Things to know before the first flight

- **It logs out when it arrives.** `logout-on-arrival` is on by default and also turns Auto Reconnect off so you stay
  logged out. Turn the setting off if you don't want that.
- **It logs out when things go badly wrong**, for example at low health while burning, or when there is no usable
  elytra left in the air.
- **It turns other modules on** when a flight starts: VolytraFly, and Meteor's Anti-Hunger and Speed (Speed sets the
  crawling pace in tunnels).
- **It digs.** `mine-through-walls` is on by default. It never digs next to water or lava, under sand or gravel, or
  through obsidian-hard blocks.
- **Portal stop is off by default.** Turn on `portal-stop` to have it land beside the first Nether portal on the way
  and log out there.
- **Minimised window.** Minecraft drops to 10 frames a second when minimised, which makes the server pull you back
  constantly. While a flight is running the addon tells the game the window is not minimised so it keeps its normal
  pace; the real state is put back when the flight stops.
- Carry food, a few golden apples and a spare elytra. Meteor's Auto Eat, Auto Gap, Auto Totem and NoFall work well with
  it.

## Settings

The Meteor menu has the ones you are likely to change: destination and arrive mode, cruise height, speed limit,
acceleration and braking, elytra durability handling, path search limits, digging, and the path rendering.

`debug-log` (off by default) writes what the flight logic is doing to `latest.log`, in lines starting with `[VG]`.
Turn it on before reporting a problem and attach the log.

The many smaller numbers (lava margins, take-off timing, how hard it may accelerate per tick and so on) are listed in
[TUNING.md](TUNING.md), with how to change them.

## Building

Clone the repository and run `./gradlew build` (`gradlew.bat build` on Windows) with JDK 25 or newer. The jar ends
up in `build/libs/`. (JDK 25 is what the Fabric build plugin needs; the mod itself runs on Java 21.)

Nothing has to be added by hand: the build downloads Minecraft, Meteor Client (from Meteor's maven) and VolytraFly
(from its GitHub release) to compile against. None of them ends up inside the Volytra Goto jar.

## Credits

By NoSleepSmoke, written with ChatGPT and Claude. It flies with [VolytraFly](https://github.com/Volizray/VolytraFly-Addon)
by Volizray and runs on [Meteor Client](https://meteorclient.com).

## Licence

GPL-3.0, the same as Meteor Client. See [LICENSE](LICENSE).
