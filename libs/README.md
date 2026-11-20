# libs

The build compiles against three jars that are not in this repository. Put them here under exactly these names
(or change the names in `build.gradle`):

| file | where it comes from |
| --- | --- |
| `meteor-client-1.21.11-86.jar` | Meteor Client for Minecraft 1.21.11, from https://meteorclient.com |
| `VolytraFly-0.1.1-1.21.11.jar` | VolytraFly, from https://github.com/Volizray/VolytraFly-Addon |
| `orbit-0.2.4.jar` | Meteor's event bus. It is inside the Meteor jar: `META-INF/jars/orbit-0.2.4.jar` |

They are only needed to compile; none of them ends up inside the Volytra Goto jar.
