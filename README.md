<p align="center">
  <img src="butterfly.jpg" width="200">
</p>

<h1 align="center">Butterfly</h1>

<p align="center">
  A from-scratch Minecraft Bedrock Edition server software in **Java 21**.
</p>

<p align="center">
  <img src="https://img.shields.io/github/license/butterfly-mc/butterfly?style=flat-square">
  <img src="https://img.shields.io/github/stars/butterfly-mc/butterfly?style=flat-square">
  <img src="https://img.shields.io/github/issues/butterfly-mc/butterfly?style=flat-square">
</p>

## The stack

Butterfly is split across five repositories:

| Repo | What it holds |
|---|---|
| [`butterfly-protocol`](https://github.com/butterfly-mc/butterfly-protocol) | RakNet, NBT, crypto/login, packet codec — the in-house wire protocol foundation. Published as Maven artifacts on tag. |
| [`butterfly-tools`](https://github.com/butterfly-mc/butterfly-tools) | MITM proxy + headless dumper for protocol research. Releases shadow jars on tag. |
| [`butterfly-data`](https://github.com/butterfly-mc/butterfly-data) | Versioned MCBE registry data dumps (`v975/`, `v980/`, …). Used by the server at boot. |
| [`butterfly-native-mod`](https://github.com/butterfly-mc/butterfly-native-mod) | C++ mod loaded into BDS to export internal registries the proxy cannot see. |

## Design principles

- **Minimal core, powerful plugins.** No vanilla world-gen, redstone, or mob AI in core. Plugins fill the gameplay layer.
- **Hard separation between the volatile protocol layer and the stable plugin API.** Protocol code lives in `butterfly-protocol` and is allowed to break across MCBE versions; the plugin API is semver-versioned and stable.
- **Self-reliance on the MCBE-specific surface.** RakNet, packet codecs, NBT, login crypto are all in-house. General-purpose libs (Netty, jose4j, fastutil) are still pulled from Maven Central.
- **Single tick thread @ 20 TPS** + async worker pool for chunk gen / IO.
- **Data-driven registries.** Block palette, items, biomes, recipes are loaded from `butterfly-data` at startup — no version-specific code paths in the server core.

## License

MIT
