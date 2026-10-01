# FocusBlock

**FocusBlock** is a free, open-source, no-ads website blocker for Android. Add the sites that distract you, flip the switch, and they stop resolving everywhere on your phone — in every browser and every app. It works entirely on your device: no account, no subscription, no tracking, no data collection.

- **100 % free** — for you and your friends. Just share the APK.
- **No root required** — it uses Android's official `VpnService` API.
- **Private by design** — the "VPN" is a loopback tunnel on your phone. Only DNS lookups are inspected; blocked sites get a fake "this site does not exist" answer, everything else is forwarded to a public resolver (1.1.1.1) untouched. Your browsing traffic never passes through the app.

---

## 1. Install the app

The ready-to-install APK is at:

```
FocusBlock/app/build/outputs/apk/debug/app-debug.apk
```

1. Copy `app-debug.apk` to your phone (USB cable, cloud drive, or send it to yourself via messaging).
2. On the phone, open the APK file. Android will ask you to allow installation from that source (e.g. "Allow from Files" or "Allow from Chrome") — confirm once.
3. Tap **Install**.

That's it. No Play Store, no account.

## 2. Use the app

1. Open **FocusBlock**.
2. Type a site into the input field — e.g. `reddit.com` — and tap **Add**.
   - You can paste full URLs like `https://www.youtube.com/watch?v=...`; the app strips everything down to the domain automatically.
   - Blocking `reddit.com` also blocks all subdomains (`www.`, `old.`, `m.`, ...), but *not* lookalikes (`notreddit.com` stays reachable).
3. Flip the **Block websites** switch to ON.
4. Android shows a VPN consent dialog ("FocusBlock wants to set up a VPN connection..."). Tap **OK** — this is required for blocking to work. The app only sees DNS queries, as described above.
5. A persistent notification "FocusBlock is active" appears. You can stop protection anytime from that notification or with the in-app switch.

Blocked sites now show a browser error like **"This site can't be reached / DNS_PROBE_FINISHED_NXDOMAIN"** — as if the site didn't exist. You also get a brief "Blocked: &lt;site&gt;" alert each time (throttled, can be turned off in Android notification settings for the app).

To unblock a site: open FocusBlock, tap **Remove** next to it. Changes take effect immediately, even while protection is on.

## 3. Tips for strict blocking (recommended)

Tech-savvy workarounds exist; close the common ones:

- **Android "Private DNS"**: Settings → Network → Private DNS → set to **Off** (or leave "Automatic" — the fake VPN resolver does not support DoT, so it falls back anyway; only a *custom provider hostname* would bypass blocking).
- **Browser "Secure DNS" / DNS-over-HTTPS**: in Chrome: Settings → Privacy and security → Use secure DNS → **Off**. Same for Firefox (Settings → General → Network → DNS over HTTPS → Off) and other Chromium browsers. DoH bypasses *any* DNS-based blocker, paid ones included.
- The app also intercepts queries that apps send directly to the well-known public resolvers (1.1.1.1, 8.8.8.8, 9.9.9.9, …), so most "hardcoded resolver" tricks are still covered.

## 4. Known limitations

- IPv6-only networks: blocking covers IPv4 DNS; queries over IPv6 resolvers are not intercepted (rare in practice, since the system resolver is IPv4 here).
- DNS-over-HTTPS to arbitrary servers can only be blocked by disabling it in the browser (see above).
- Traffic to the captured resolver IPs on ports other than 53 is dropped (those are resolver anycast addresses, not websites people browse to).

## 5. Share it with friends

Send them `app-debug.apk` — any file-sharing method works. They install it the same way as in section 1.

## 6. Build from source yourself

Prerequisites: JDK 17 and the Android SDK (platform 34, build-tools 34).

```bash
cd FocusBlock
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # run the unit tests (21 tests)
```

The APK lands in `app/build/outputs/apk/debug/`.

### Project layout

```
app/src/main/java/com/focusblock/app/
├── MainActivity.kt          # UI: block list, add/remove, on/off switch
├── BlockerVpnService.kt     # Local VPN tunnel + DNS interception engine
├── BlockListStore.kt        # SharedPreferences storage
├── blocking/DomainRules.kt  # URL normalization + domain matching (pure JVM)
└── net/
    ├── DnsMessages.kt       # DNS query parsing + NXDOMAIN forging (pure JVM)
    ├── Ipv4Udp.kt           # IPv4/UDP packet parse/build (pure JVM)
    └── UdpForwarder.kt      # Upstream DNS relay with reply re-wrapping
app/src/test/                # JVM unit tests for all of the above
```

All blocking-critical logic (domain matching, DNS parsing/response forging, packet building, UDP relay) is plain JVM Kotlin covered by unit tests — that's what was verified here (21/21 passing) along with a full APK compile. Device-level testing wasn't possible on this machine (no Android phone/emulator attached), so do a quick smoke test on your phone: add a site, enable, try to open it.
