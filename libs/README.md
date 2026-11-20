# libs

The build compiles against VolytraFly, which is not in this repository and has no maven. Put its jar here under
exactly this name (or change the name in `build.gradle`):

| file | where it comes from |
| --- | --- |
| `VolytraFly-0.1.2-26.2.jar` | VolytraFly releases, https://github.com/Volizray/VolytraFly-Addon/releases |

It is only needed to compile; it does not end up inside the Volytra Goto jar. Meteor Client is downloaded from
`maven.meteordev.org` by the build.
