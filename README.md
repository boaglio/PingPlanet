<h1 align="center">PingPlanet</h1>

<p align="center">A speed test for your local network and for servers in 20 countries, written in Java.</p>

<p align="center">
  <a href="https://github.com/boaglio/PingPlanet/actions/workflows/build.yml"><img src="https://github.com/boaglio/PingPlanet/actions/workflows/build.yml/badge.svg" alt="Build"></a>
</p>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/gui-dark.png">
  <img src="docs/images/gui-light.png" alt="The PingPlanet window after an internet test: a world map with servers colored by latency, a results table and a download ranking">
</picture>

<sub>Example numbers. Yours depend on your connection.</sub>

## Features

- **Internet test**: latency, jitter and download speed to public test servers around the world, on a live world map.
- **LAN test**: run a server on one machine and test from another, like `iperf`.
- **Wired vs Wi-Fi**: every connection is tested and compared, with a warning when the default one is not the best.
- **Window or terminal**: a desktop window (light and dark), or a colorful terminal dashboard that also works over SSH.
- **Share results**: a Screenshot button (Ctrl+Shift+S), Markdown reports (`--markdown`) and JSON (`--json`).

## Download

Get the latest version from [Releases](https://github.com/boaglio/PingPlanet/releases/latest). The Windows and
Linux downloads include Java, so there is nothing else to install.

| System | File | Run |
|---|---|---|
| Windows | `PingPlanet-<version>-windows-x64.zip` | Unzip, then open `PingPlanet.exe`. For the terminal, use `pingplanet-cli.exe`. |
| Ubuntu, Debian | `pingplanet_<version>_amd64.deb` | `sudo apt install ./pingplanet_<version>_amd64.deb`, then open PingPlanet from the menu. |
| Other Linux | `PingPlanet-<version>-linux-x64.tar.gz` | Extract, then run `PingPlanet/bin/PingPlanet`. |
| Any, with Java 21+ | `PingPlanet-<version>.jar` | `java -jar PingPlanet-<version>.jar` |

The .deb installs to `/opt/pingplanet`. To type `pingplanet` in a terminal:
`sudo ln -s /opt/pingplanet/bin/PingPlanet /usr/local/bin/pingplanet`.

Windows may warn that the app is from an unknown publisher, because it is not signed: choose
**More info → Run anyway**.

## Build from source

You need Java 21 or newer and Maven.

```sh
./pingplanet.sh            # Linux and macOS: builds on the first run, opens the window
pingplanet.bat             # Windows
./pingplanet.sh world      # the internet test in the terminal
./pingplanet.sh --build    # rebuild after pulling new code
```

## Usage

```
pingplanet                       open the window
pingplanet world [options]       test internet servers in many countries
pingplanet lan <host>[:port]     test the local network against "pingplanet serve"
pingplanet serve [--port N]      wait for LAN tests from other machines (TCP 7357)
pingplanet list                  show the internet test servers
pingplanet interfaces            show this machine's network connections

-c, --countries BR,US,JP   only these countries      -l, --latency-only   skip downloads
-s, --seconds N            seconds per transfer (5)  -n, --streams N      parallel connections (4)
-i, --interface eth0,wlan0 only these connections    --servers FILE       your own server list (CSV)
--markdown [FILE.md]       save a Markdown report    --json               results as JSON
```

Run `pingplanet --help` for every option.

## How it measures

Latency is the median of small HTTP requests on an already open connection, so connection setup and
busy servers don't inflate it, and no root access is needed. Downloads use several parallel connections
and start timing at the first byte. Each download can use a few hundred MB on a fast link, so use
`--latency-only` on a metered connection.

Test files are hosted by [Vultr](https://www.vultr.com), [Hetzner](https://www.hetzner.com),
[OVH](https://www.ovhcloud.com) and [Cloudflare](https://speed.cloudflare.com). The map uses
[Natural Earth](https://www.naturalearthdata.com) data.

To publish a release, set the version in `pom.xml` and push a tag like `v0.1.0`: GitHub Actions builds
the packages (`tools/package.sh`) and attaches them to the release.

## License

[GNU General Public License v3.0](LICENSE)
