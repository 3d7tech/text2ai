# Offgrid Mesh: proof of concept

An Android chat app that sends messages phone-to-phone with **no internet and no mobile network**.
It uses two radios at once:

| Radio | Range (typical) | Role here |
|---|---|---|
| **Bluetooth LE** (GATT) | 10–50 m | Works on every phone; the baseline |
| **Wi-Fi Aware** (NAN) | 50–100 m+ | Longer range; also the iPhone ↔ Android link (iOS 26+) |

Messages **hop** across phones (multi-hop flooding with de-duplication), and phones
**carry** recent messages to peers they meet later (store-and-forward / "data mule").
So a message from village A can reach village C on a phone riding a boda-boda, even if
A and C were never in range of each other at the same time.

## Layout

```
core/   pure Kotlin, no Android: packets, fragmentation, routing, store-and-forward + tests
app/    Android: BleTransport, WifiAwareTransport, foreground MeshService, chat UI
```

`core` is transport-agnostic (`Transport` interface), so LoRa (Meshtastic/MeshCore radios
over USB/BLE), Wi-Fi HaLow, or Wi-Fi Direct can be added as more radios without
touching routing.

## Build & run

Requires Android Studio / Android SDK 35.

```bash
./gradlew :app:installDebug           # install on a connected phone
./gradlew -p core test                # run the mesh tests (no Android SDK needed)
```

Use **2–3 physical phones** (emulators have no BLE or Aware). Open the app, grant the
Bluetooth/Nearby permissions, and type. Turn mobile data and Wi-Fi internet off to prove the point.
Wi-Fi must be *enabled* for Aware, but it doesn't need to be connected to a network.

Chat commands: `/nick Amaka`, `/peers`, `/dm Amaka hello`.

Try the data-mule demo: phone A sends a message while only B is near it. Walk B out of
range of A and over to C, and C receives it.

## Household hub: iPhones, Chromebooks, tablets

Browsers can't use Bluetooth mesh or Wi-Fi Aware, so non-Android devices join through a **hub**:
an Android device (ideally a plugged-in tablet) running the app with **Hub → Run the hub on this device**.

- Every device on the same Wi-Fi opens the hub's address. The router needs no internet,
  or the hub tablet's hotspot can be used instead. Scan the QR code on the hub screen.
- **iPhone/iPad:** open in Safari → Share → **Add to Home Screen**. **Chromebook:** Chrome → ⋮ → Cast, save and share →
  Create shortcut → Open as window. **Android:** install the app, or add the page to the home screen.
- Text, **photos** (shrunk on the sending device to ≤1600 px) and **videos** (up to 300 MB each, with HTTP Range so
  iOS can play them) are stored on the hub (`filesDir/hub`, 4 GB cap, oldest media removed first).
- The QR code carries a household key; without it the API returns 401.
- Bridging: browser text is re-broadcast on the mesh under the sender's name (media becomes a short note);
  public mesh messages appear in the browsers, marked "via mesh".

**Chromebook (or any computer) as the hub:** Chromebooks can't install the APK, so the hub also ships as a
single file, `offgrid-hub.jar`, built in CI and published on the download page. In the Chromebook's Linux terminal:
`sudo apt install -y default-jre-headless && java -jar offgrid-hub.jar`, then forward port 8090
(Settings → Developers → Linux → Port forwarding). The chat's **Invite** button shows a QR code for other devices.
A Chromebook hub isn't on the radio mesh (no bridge to Android phones). The household key is kept in `~/offgrid-hub/household.key`.

Code: `core/.../hub/` (HTTP server, store, routes; JVM-tested) and `core/src/main/resources/hub/index.html` (web app).
Try it on a laptop: `gradle -p core runHubDemo -PhubArgs="--key demokey123"`, then open http://127.0.0.1:8090/?k=demokey123

Known limits: the hub is a single point (if it's off, browsers can't chat); videos recorded in HEVC on iPhones
may not play on Chromebooks or older Android (set iPhone Camera → Formats → Most Compatible); no encryption yet.

## Sharing the app without internet

Tap **Share app** (top right). Pick whichever way the other phone can receive it:

| Option | Receiver needs | How |
|---|---|---|
| Share sheet | Bluetooth, Quick Share, Xender, SHAREit, WhatsApp… | Sends `OffgridMesh.apk` |
| Bluetooth as .zip | Bluetooth | For phones whose Bluetooth refuses `.apk`. The receiver opens the zip in Files and taps the APK |
| Wi-Fi + QR code | Only a browser and camera | This phone starts a local-only hotspot and a tiny web server (`core/.../share/ApkWebServer.kt`). The other phone scans QR 1 to join and QR 2 to download |
| Download link | Internet | Shares the GitHub Pages URL |

The receiver must allow "install unknown apps" once. Because every build is signed with the same debug key,
a shared copy installs over (or gets updated by) any other copy.

## How it works

- **Packet** (46-byte header): version, type, ttl, hops, 128-bit random id, src, dst, timestamp, payload.
- **Flooding:** relay unseen packets to every neighbor except the sender, with `ttl-1` (default ttl 7).
  An 8k-entry LRU of seen ids stops loops, and stops duplicates when BLE and Aware both deliver.
- **Fragmentation:** packets are split into frames that fit the BLE MTU (negotiated up to 517) or
  the ~255-byte Wi-Fi Aware follow-up message limit.
- **Store-and-forward:** up to 256 relayed packets from the last 6 h are replayed to each new neighbor.
- **BLE:** every phone advertises plus runs a GATT server (inbound) and scans/connects as a central (outbound).
- **Wi-Fi Aware:** every phone publishes plus subscribes to one service. Frames travel in
  discovery follow-up messages, so no IP data path or pairing is needed.

## Known PoC limitations, and the next steps

1. **No encryption or authentication yet.** DMs are readable by relays. Next: Ed25519 identity keys
   (NodeId = hash(pubkey)), signed packets, and Noise XX / Double Ratchet for DMs.
2. **Naive gossip.** Carried packets are replayed in full to every new neighbor. Next: exchange
   a Bloom filter / id summary first and send only what the peer is missing (essential for LoRa).
3. **Wi-Fi Aware data path** (sockets over `WifiAwareNetworkSpecifier`) for photos and voice notes.
4. **Delivery receipts and retries** for DMs. Wi-Fi Aware follow-up messages can be dropped.
5. **Battery:** duty-cycle scanning and adapt to battery level and neighbor density.
6. **iOS app** using CoreBluetooth plus the iOS 26 Wi-Fi Aware framework, with the same wire format.
7. **LoRa transport** to reach km-scale repeaters (solar nodes on water towers, schools, shops).

## Verified so far

- `core` unit tests (43): household hub (key check, unicode/escaping, live SSE stream, resume from
  Last-Event-ID, photo/video upload, Range requests, validation, restart persistence, bridge hooks, concurrency),
- end-to-end browser test (Chromium, phone + laptop viewports): text both ways, photo shrink 3000→1600 px,
  video upload and playback, reload without duplicates. Safari/WebKit not yet tested.
- earlier tests: sharing server (exact bytes, headers, 404/405, junk requests, parallel
  downloads, port fallback), zip packaging, Wi-Fi QR escaping, plus mesh tests (8 original, 14 simulation/fuzz): packet round-trip, out-of-order fragment reassembly, multi-hop relay,
  no duplicates in a full mesh, TTL limit, DM privacy from relays (delivery only), data-mule store-and-forward,
  dual-radio de-duplication.
- `app` sources type-check against the Android 15 framework classes.
- **Not yet run on real devices.**
