# Tuning values

Numbers used by the flight logic that have no setting in the Meteor menu, with their defaults. The defaults
are what worked in testing on 6b6t (Minecraft 1.21.11); other servers may want other values.

To change one without rebuilding, create `.minecraft/config/volytragoto.properties` with lines like
`lava.margin = 1.5`. The file is read again whenever it changes, also in the middle of a flight.

What each one does is explained in the source where it is used: search `brain/BrainImpl.java` for the key.

| key | type | default |
| --- | --- | --- |
| `accel.floor` | number | `0.2` |
| `accel.free.speed` | number | `2.95` |
| `accel.high` | number | `0.1` |
| `accel.horizontal` | true/false | `true` |
| `accel.max` | number | `0.1` |
| `air.drift.max` | number | `0.05` |
| `air.drop.velocity` | true/false | `true` |
| `collide.resolve` | true/false | `true` |
| `creep.speed` | number | `0.6` |
| `creep.ticks` | whole number | `60` |
| `decel.max` | number | `0.2` |
| `decel.plan` | number | `0.15` |
| `dig.ahead.steps` | whole number | `5` |
| `dig.confirm.ticks` | whole number | `8` |
| `dig.gap.ticks` | whole number | `1` |
| `dig.ground.truth` | true/false | `true` |
| `dig.instant` | true/false | `true` |
| `dig.instant.fails` | whole number | `4` |
| `dig.instant.retry` | whole number | `8` |
| `dig.min.ticks` | whole number | `4` |
| `dig.mode.ticks` | whole number | `600` |
| `dig.on.foot` | true/false | `true` |
| `dig.packet.above` | number | `0.5` |
| `dig.pending.ticks` | whole number | `16` |
| `dig.rotate.gliding` | true/false | `false` |
| `dig.truth.hold` | whole number | `60` |
| `dv.free.below` | number | `0.25` |
| `dv.max` | number | `0.2` |
| `fireres.lava.paths` | true/false | `false` |
| `fireres.path.ticks` | whole number | `1500` |
| `floor.gap` | number | `0.12` |
| `foot.setbacks.max` | whole number | `5` |
| `fps.keep.minimized` | true/false | `true` |
| `frontier.replan.distance` | number | `24` |
| `frontier.replan.ticks` | whole number | `5` |
| `glide.confirm.ticks` | whole number | `20` |
| `glide.desync.fix` | true/false | `true` |
| `glide.wait.confirm` | true/false | `true` |
| `glide.wait.sink` | number | `0.0` |
| `helpers.enable` | true/false | `true` |
| `hover.lava.clear` | number | `1.5` |
| `hover.lava.rise` | number | `0.15` |
| `lava.escape.speed` | number | `0.8` |
| `lava.exit.air.penalty` | number | `40` |
| `lava.fall.range` | whole number | `24` |
| `lava.hop.ticks` | whole number | `8` |
| `lava.margin` | number | `1.2` |
| `lava.margin.creep` | number | `0.0` |
| `lava.margin.tight` | number | `0.7` |
| `lava.still.ticks` | whole number | `20` |
| `log.takeoff` | true/false | `false` |
| `logout.hp` | number | `8` |
| `mine.lineup.giveup` | whole number | `120` |
| `mine.lineup.ticks` | whole number | `40` |
| `mine.planned.only` | true/false | `true` |
| `nether.cruise.weight` | number | `0.3` |
| `nether.cruise.y` | whole number | `95` |
| `pad.below.max` | number | `0.05` |
| `paired.adapt` | true/false | `true` |
| `paired.speed.cap` | number | `1.5` |
| `path.ceiling.weight` | number | `0.3` |
| `path.floor.clearance` | whole number | `10` |
| `path.floor.touch` | number | `15` |
| `path.floor.weight` | number | `0.5` |
| `path.lava.cost` | number | `20` |
| `path.lavaok.cost` | number | `6` |
| `path.mob.cost` | number | `12` |
| `path.mob.max` | whole number | `40` |
| `path.mob.radius` | number | `5` |
| `portal.scan.budget` | whole number | `6` |
| `portal.scan.chunks` | whole number | `6` |
| `portal.stop` | true/false | `false` |
| `replan.ticks` | whole number | `100` |
| `route.change.dist` | number | `4` |
| `search.extend` | true/false | `true` |
| `search.min.gain` | number | `16` |
| `search.ms.idle` | whole number | `20` |
| `search.ticks.goal` | whole number | `100` |
| `search.ticks.max` | whole number | `160` |
| `search.ticks.partial` | whole number | `30` |
| `setback.cut` | number | `0.8` |
| `setback.keep.camera` | true/false | `true` |
| `setback.recover` | number | `1.15` |
| `setback.recover.ticks` | whole number | `100` |
| `setback.slow.speed` | number | `1.5` |
| `setback.slow.ticks` | whole number | `0` |
| `sim.pitch` | number | `90` |
| `sink.max` | number | `0.5` |
| `sink.min` | number | `0` |
| `snapshot.interval` | whole number | `40` |
| `speed.cap` | number | `0` |
| `speed.turn.min` | number | `0.8` |
| `stall.ticks` | whole number | `4` |
| `still.creep.ticks` | whole number | `20` |
| `still.dig.ticks` | whole number | `60` |
| `takeoff.airstuck.ticks` | whole number | `10` |
| `takeoff.force.ticks` | whole number | `100` |
| `takeoff.lava.below` | number | `0` |
| `takeoff.low` | true/false | `false` |
| `takeoff.low.air` | number | `0.7` |
| `takeoff.min.air` | number | `0.7` |
| `takeoff.space` | number | `1.85` |
| `takeoff.stuck.radius` | number | `1.5` |
| `takeoff.stuck.ticks` | whole number | `60` |
| `takeoff.two.step` | true/false | `true` |
| `tick.catchup.min` | number | `0.0` |
| `tick.catchup.ms` | number | `30` |
| `tick.catchup.scale` | true/false | `false` |
| `tps.sync` | true/false | `false` |
| `tps.sync.below` | number | `19.0` |
| `tps.sync.min` | number | `0.5` |
| `trapped.gain` | number | `8` |
| `trapped.searches` | whole number | `2` |
| `tunnel.crawl.speed` | number | `0.1` |
| `tunnel.end.gain` | number | `16` |
| `tunnel.end.window` | whole number | `200` |
| `tunnel.max` | whole number | `40` |
| `tunnel.stand` | true/false | `false` |
| `tunnel.walk.max` | number | `0.4` |
| `tunnel.walk.speed` | number | `0.2` |
| `walk.noroom.ticks` | whole number | `20` |
| `walk.rock.ahead` | true/false | `false` |
| `walk.rock.nodes` | whole number | `4` |
| `walk.settle.ticks` | whole number | `5` |
| `walk.use.speed` | true/false | `true` |
| `watchdog.exempt.ticks` | whole number | `500` |
| `watchdog.ticks` | whole number | `200` |
