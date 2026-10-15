# SWEEP detection-spike procurement

Prepared October 12, 2026 (America/Los_Angeles). This is a reviewable shopping list. No order, cart, checkout, payment, or delivery estimate has been created. Prices and inventory are observations from the listed sources and must be refreshed before purchase.

## Required devices

### 1. Apple AirTag — quantity 1

- **Observed price:** $29.00 for the single pack on [Apple's US store](https://www.apple.com/shop/buy-airtag/airtag).
- **Use:** BLE tracker reception and classification; compare owner-near, owner-separated, stationary, and moving states.
- The current Apple listing describes the next generation of AirTag. Record the generation, firmware, and identifier behavior in the trial record; do not assume older signature fixtures validate this unit.
- The product page offers purchase selection. Destination-specific stock, delivery, tax, and shipping were not checked.
- Owner-state tests require an available compatible Apple device and account. Use an existing device rather than adding an iPhone to this order.

### 2. Tile Mate — quantity 1

- **User's planning allowance:** approximately $25; this is **not a verified current price**.
- [Official Tile Mate product URL](https://www.tile.com/products/tile-mate) now redirects to the [Life360 Tile product page](https://www.life360.com/tile-trackers/products/tile-mate). The retrieved page did not expose a purchasable Mate variant, US price, or stock.
- **Status:** selection and current price remain unresolved. Obtain a specific Mate generation/SKU from the official store or an authorized seller before committing a purchase; do not silently substitute Tile Pro, Slim, Sticker, or a different generation.
- **Use:** Tile reception and classification, with normal and Anti-Theft Mode states recorded separately. [Life360 documents](https://support.life360.com/hc/en-us/articles/30582923425815-Tile-Anti-Theft-Mode) that Anti-Theft Mode hides Tiles from its own Scan and Secure feature; SWEEP's behavior still needs measurement.

### 3. TP-Link Tapo C110 — quantity 1

- **Observed direct-store data:** $19.79; `available: true`, one default variant. Read from the store's live [product JSON](https://us.store.tapo.com/products/tp-link-tapo-2k-indoor-security-camera-c110.js) on October 12. Older indexed page prices differed; this is still not a checkout quote.
- [US Tapo product/purchase page](https://us.store.tapo.com/products/tp-link-tapo-2k-indoor-security-camera-c110).
- **Why this model:** a documented camera with a reproducible setup network, suitable for the AP-signature spike. [TP-Link's setup procedure](https://www.tp-link.com/us/support/faq/2710/) describes the `Tapo_Cam_XXXX` setup network and joining home Wi-Fi.
- **Test modes:** setup AP, configured Wi-Fi client, offline local recording where supported, and powered off. Record the received BSSID and SSID during setup, then verify whether any setup broadcast remains after configuration.
- This validates a camera **radio indicator**, not universal hidden-camera discovery. Android's [Wi-Fi API enumerates visible access points](https://developer.android.com/develop/connectivity/wifi/wifi-scan); a camera acting only as a client is outside that AP enumeration.
- A TP-Link account, a test Wi-Fi network, and an existing compatible microSD card are needed for setup/local-recording trials. A microSD purchase has not been priced or added.

### 4. Genuine Espressif ESP32-C3-DevKitM-1-N4X — quantity 2

- **Selected seller:** [Mouser product page](https://www.mouser.com/en/ProductDetail/Espressif-Systems/ESP32-C3-DevKitM-1-N4X?qs=olJun0bQHM8KNNDD%252BFT48A%3D%3D), manufacturer Espressif Systems, Mouser part **356-ESP32C3DVKTM1N4X**.
- **Observed availability:** the retrieved product page redirected to Mouser Europe and showed **289 in stock, can dispatch immediately**, priced at **€6.88 each**. The [US-dollar results page](https://www.mouser.com/en/c/?q=356-ESP32-C3) showed **$8.00 each** and positive stock. Region-dependent currency and inventory differed, so the US-dollar price is a sourcing estimate, not a confirmed US checkout quote. US results also displayed a possible 15% tariff.
- This supplies an available alternative to the [DigiKey N4X listing](https://www.digikey.com/en/products/detail/espressif-systems/esp32-c3-devkitm-1-n4x/25587617), which showed no stock in the initial check. [Adafruit's equivalent board](https://www.adafruit.com/product/5337) showed $9.95 but was also out of stock.
- **Purpose:** two independently configured Remote ID transmitters, including simultaneous-drone and transport-isolation trials.
- [ArduRemoteID](https://github.com/ArduPilot/ArduRemoteID) explicitly supports the ESP32-C3 DevKitM-1, permits simulation through USB UART, and supports legacy BLE, coded/extended BLE, Wi-Fi broadcast/beacon, and Wi-Fi NAN. Flashing, exact N4X revision compatibility, and reception must be verified on arrival.
- Do not substitute an unspecified ESP32 board or bare MINI-1 module. The original ESP32 lacks BLE long-range/extended advertising; the [OpenDroneID implementation notes](https://github.com/opendroneid/opendroneid-core-c) require C3/S3 hardware for those modes.

## Reuse before buying

- **Two micro-USB data cables:** reuse existing cables if both boards enumerate as serial devices. Buy only the shortfall. The [Espressif board guide](https://docs.espressif.com/projects/esp-dev-kits/en/latest/esp32c3/esp32-c3-devkitm-1/user_guide.html) requires data-capable micro-USB cables; charging-only cables cannot program the boards. Cable prices are not quoted.
- **Android 10+ phone:** model and OS build are awaiting the user's available-device details. Start with an existing/spare handset. Do not buy a phone solely on its Android version or Bluetooth 5 label. Test actual legacy BLE, coded/extended BLE, Wi-Fi beacon, and NAN reception; report unavailable transports. [OpenDroneID's phone tests](https://github.com/opendroneid/receiver-android/blob/master/supported-smartphones.md) show that affirmative feature flags can still fail reception.
- **Other existing items:** a compatible Apple owner device, USB power/ports, a tape measure, a labeled test Wi-Fi network, and a microSD card for camera recording tests.

## Cost and order status

No exact rig total is stated: Tile price, phone need, cable need, microSD need, tax, shipping, seller currency, and any duties remain unresolved. The observed AirTag and Tapo prices plus the Mouser board estimates are enough to size the first purchase, but do not establish a delivered total.

The purchase handoff still needs a shipping destination and a payment/checkout arrangement, plus the available phone model. These details are not available in this task. Do not imply hardware has been ordered or a physical detection trial has run.
