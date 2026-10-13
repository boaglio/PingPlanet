<h1 align="center">PingPlanet</h1>

<p align="center">A free and open source speed test for your local network and for servers around the world, written in Java.</p>

<p align="center">
  <a href="https://github.com/boaglio/PingPlanet/actions/workflows/build.yml"><img src="https://github.com/boaglio/PingPlanet/actions/workflows/build.yml/badge.svg" alt="Build"></a>
</p>

PingPlanet has a **window** and a **terminal** mode. Double-click the jar, or run it with no arguments, and
the window opens: a world map that lights up as servers answer, live results, speed gauges for your local
network and a built-in server for LAN tests. Any argument (`pingplanet world`, `pingplanet lan …`) runs it in
the terminal instead, which also works over SSH and on machines without a desktop.

In a terminal PingPlanet shows a live, colorful dashboard: an animated progress bar while it measures,
a table with signal bars and speed gauges that fill as each download runs, a **braille world map** with
every server colored by latency, and a download ranking:

```
╭─────┬────────────────┬────────────┬──────────────┬─────────┬────────────────────────────────╮
│     │ Location       │ Provider   │ Latency      │ Jitter  │ Download                       │
├─────┼────────────────┼────────────┼──────────────┼─────────┼────────────────────────────────┤
│ BR  │ São Paulo      │ Vultr      │ ▂▄▆█   12 ms │  0.6 ms │ ██████████████▊───  287.3 Mbps │
│ US  │ New Jersey     │ Vultr      │ ▂▄▆█  118 ms │  1.2 ms │ █████████████▍────  201.8 Mbps │
│ JP  │ Tokyo          │ Vultr      │ ▂▄▆█  262 ms │  2.1 ms │ ███████████▏──────   96.4 Mbps │
╰─────┴────────────────┴────────────┴──────────────┴─────────┴────────────────────────────────╯
```

*(Example numbers. Yours depend on your connection.)*

## Features

- **Desktop window** (light and dark themes): Internet, LAN and Server tabs, a dotted world map with each
  server colored by latency and a pulse on the one being tested, live results, a download ranking, a
  comparison of your connections, an overall progress bar with the time left, and a Stop button.

- **Internet test in 20 countries**: latency, jitter and download speed to public test servers in the Americas, Europe, Africa, the Middle East, Asia and Oceania, plus Cloudflare's nearest edge. Run `pingplanet list` to see them all.
- **Local network test**: run `pingplanet serve` on one machine and `pingplanet lan <host>` on another to measure latency, download and upload between them, like `iperf`.
- **Honest latency**: like browser speed tests, it times tiny HTTP requests on an already open connection. Connection setup is never timed, so busy servers that are slow to accept new connections do not inflate the result, and no root access is needed (unlike ICMP ping).
- **Accurate on fast links**: transfers use several parallel connections (`--streams`), and timing starts at the first byte, so connection setup is not counted.
- **Wired vs Wi-Fi, automatically**: every connection to the internet (cable, Wi-Fi, USB modem…) is found and tested, one after the other, with a side-by-side comparison at the end. `-i` picks specific ones.
- **JSON output** (`--json`) for scripts, cron jobs and dashboards.
- **Your own servers**: export the list with `pingplanet list --csv`, edit it, and pass it back with `--servers`.
- **Looks good in a terminal**: colors, live gauges, rounded tables and a world map. When the output goes to a file or a pipe you get plain text instead, and `NO_COLOR` or `--no-color` turns the colors off.
- **No dependencies**: one small jar, plain JDK.

## Build & run

You need Java 21 or newer and Maven.

```sh
./pingplanet.sh            # Linux and macOS (builds on the first run), opens the window
pingplanet.bat             # Windows, opens the window
./pingplanet.sh world      # the internet test in the terminal
```

Or build and run the jar yourself:

```sh
mvn package
java -jar target/pingplanet-0.1.0.jar
```

Pass `--build` to the launcher to rebuild after pulling new code.

## Usage

```
pingplanet                       open the window (on a desktop, with no arguments)
pingplanet gui [--servers FILE]  open the window
pingplanet world [options]       test internet servers in many countries
pingplanet lan <host>[:port]     test the local network against "pingplanet serve"
pingplanet serve [--port N]      wait for "pingplanet lan" tests from other machines
pingplanet list                  show the internet test servers
pingplanet interfaces            show this machine's network connections

-c, --countries BR,US,JP   only test these country codes (see "list")
-l, --latency-only         skip the download test (fast)
-s, --seconds N            seconds per transfer test (default 5)
-n, --streams N            parallel connections per transfer (default 4)
-i, --interface eth0,wlan0 test only through these interfaces (names or local IPs);
                           "default" uses just the system's default route.
                           Without -i, every connection to the internet is
                           tested, one run each, then compared
-p, --port N               LAN port (default 7357)
    --servers FILE         use your own server list (CSV, same format as "list --csv")
    --csv                  with "list": print the server list as CSV
    --json                 print results as JSON
    --no-color             no colors (also when NO_COLOR is set)
```

### Examples

```sh
pingplanet --latency-only            # quick check of every country, a few seconds
pingplanet -c BR,PT,US -s 10         # three countries, 10 seconds each
pingplanet --json > results.json     # machine-readable results
```

### Local network test

On the first machine:

```sh
pingplanet serve
```

On the second machine, using the first one's IP address:

```sh
pingplanet lan 192.168.0.10
```

```
  Latency   ▂▄▆█  0.4 ms   jitter 0.1 ms · loss 0%
  Download  ██████████████████████████████▏─  941.2 Mbps  of 1 Gbps
  Upload    ██████████████████████████████──  938.7 Mbps  of 1 Gbps
```

`serve` prints this machine's IP addresses, so you know what to type on the other one.

The server listens on TCP port 7357, so allow it in the firewall of the machine running `serve`.

### Wired vs Wi-Fi

When the machine has more than one connection to the internet, PingPlanet tests all of them by itself:
the default connection first, then the others, then a comparison. The LAN test does the same. On Linux
a connection counts when it has a default route, which leaves out VPN and overlay links like Tailscale.

```sh
pingplanet interfaces                  # names and addresses, and which one is the default
pingplanet -c BR,US                    # every connection, then a comparison table
pingplanet -i wlan0                    # only Wi-Fi
pingplanet -i default                  # only the system's default connection, as with one connection
pingplanet lan 192.168.0.10            # LAN test over every connection
```

The comparison shows, per interface, how many servers answered, the latency to the nearest server
(what the connection itself adds), the median latency and the median download speed.

If the connection your system uses by default is clearly not the best one (another is at least 20% faster
without answering more than 20 ms slower, or the default reached no servers), PingPlanet ends with a warning
and the command to make the better one the default (`nmcli` or `ip route` on Linux, PowerShell on Windows,
the service order on macOS).

**Linux note:** Linux chooses the outgoing interface by destination, not by source address, so traffic
for the non-default interface can still leave through the default one. PingPlanet detects this and
prints the two commands that add a source route for that address, for example:

```sh
sudo ip route add default via 192.168.0.1 dev wlan0 table 7357
sudo ip rule add from 192.168.0.23 table 7357
```

They last until reboot. Windows and macOS send from the chosen interface without this.

## How it measures

| Measure  | Internet | LAN |
|----------|----------|-----|
| Latency  | Median of 5 one-byte HTTP requests on an open connection, after one warm-up request | Median of 20 one-byte echoes on an open connection |
| Jitter   | Mean difference between consecutive latency samples | same |
| Download | HTTP download of a large test file over N connections for S seconds | Server streams data over N connections for S seconds |
| Upload   | not measured (the public servers only offer downloads) | Client streams data to the server |

Internet downloads run one server at a time so they do not compete for bandwidth. Each one downloads up to a few hundred MB on a fast connection, so use `--latency-only` or `--countries` if you are on a metered connection.

The test files are hosted by [Vultr](https://www.vultr.com), [Hetzner](https://www.hetzner.com), [OVH](https://www.ovhcloud.com) and [Cloudflare](https://speed.cloudflare.com), which publish them for speed testing.

### Behind a proxy

Latency needs a direct connection to the server, so it shows `-` behind a proxy. Downloads can go through an HTTP proxy with the standard Java settings:

```sh
java -Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=3128 -jar target/pingplanet-0.1.0.jar
```

## Development

The world map is drawn from [Natural Earth](https://www.naturalearthdata.com) land polygons (public domain).
To regenerate `src/main/resources/pingplanet/world.txt`, run `python3 tools/make-map.py ne_110m_land.geojson`.

## License

[GNU General Public License v3.0](LICENSE)
