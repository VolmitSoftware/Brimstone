# Brimstone

Brimstone adds custom TNT that players launch by left-clicking the air, with seven effects and configurable recipes, fuel costs, and entity limits.

## Requirements

- Minecraft 26.1 through 26.3 on Spigot, Paper, Purpur, Leaf, Folia, or Canvas.
- Java 25 or newer.
- Network access for dependency downloads on the first start.

## Install

1. Stop the server and back up its worlds and plugin data.
2. Place the Brimstone distribution jar in `plugins/`, replacing the previous jar.
3. Start the server, check `/brim status`, and open `/brim config` to review the settings.

See the [wiki](https://docs.volmitsoftware.com/brimstone), [configuration reference](https://docs.volmitsoftware.com/brimstone/03-configuration), and [commands and permissions](https://docs.volmitsoftware.com/brimstone/02-commands-permissions).

## Build

Use Java 25. From the Brimstone repository:

```powershell
.\gradlew.bat build buildSwiftSwamp
```

The build resolves local VolmLib automatically and stages the verified distribution as `../BUILDS/Brimstone.jar`. Override the staging directory with `-PbuildsDirectory=<path>`.
