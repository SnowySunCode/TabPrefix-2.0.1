# TabPrefix 2.0.2

- Automatic editor addresses now use active server network interfaces and prefer the Minecraft player's subnet. IPv4, IPv6, loopback and multiple interfaces are handled.
- Added `/lptab linkwifi`: a clickable server-interface list, private address selection and automatic reset. Selecting an address opens a new editor session. Wi-Fi passwords are not requested; the command works with server network addresses, not SSIDs.
- Added automatic recovery from an occupied HTTP port in automatic LAN mode. `web.port: 0` supports OS-assigned ports. Explicit public URLs/hosts retain their configured fixed port.
- Editor and resource-pack URLs use the actual bound port. Pack delivery follows each player's selected or automatically chosen address unless an explicit pack URL is configured.
- Added separate starting, disabled, listening, bind-failed, start-failed and stopped states. `/lptab doctor` and `/lptab status` include HTTP binding, actual port and startup errors.
- Editor sessions bind Origin validation to the address used for that session. Different LAN addresses no longer require weakening cross-origin checks. New sessions still revoke previous sessions for the same player.
- Updated English and Russian messages, help, tab completion and network setup documentation.
- Added 52 network checks covering subnet selection, occupied ports, real HTTP/pack downloads, per-session Origin, address changes and clickable command delivery. All 252 automated checks passed.

Same-LAN browser access needs no router port forwarding. Internet access still requires a reachable public address/proxy/tunnel. Live Minecraft server/client validation was not performed in this build environment.
