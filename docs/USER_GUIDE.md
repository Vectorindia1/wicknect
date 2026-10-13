# HostelAutoLogin – quick start (for everyone)

This app logs you in to your Wi-Fi's login page automatically, and reconnects when the Wi-Fi drops.
You only need your own Wi-Fi username and password. You never have to edit any files.

## On your phone (Android)
1. Download the APK from the **Releases** page and open it. If Android asks, allow installing from your browser.
2. Open **HostelAutoLogin** and allow *Location* and *Notifications* when asked (Android needs Location to read the Wi-Fi name).
3. Connect your phone to your Wi-Fi.
4. Go to the **Connect** tab, check the Wi-Fi name, type your username and password, tap **Connect**.
5. If it asks "Is this your Wi-Fi's login page?" tap **Yes** (once).
6. When it says **You're connected**, tap **Allow background running**. Done.

## On your computer (Windows)
1. Download `HostelAutoLogin.exe` from **Releases** and double-click it. (If Windows shows a blue warning, click *More info* → *Run anyway*.)
2. Connect to your Wi-Fi, check the Wi-Fi name, type your username and password, click **Connect**.
3. Click **Yes** if it asks "Is this your Wi-Fi's login page?". The app now lives in the tray (bottom-right).

## On your iPhone (iOS)
iPhones don't allow apps to run in the background all day, so the iPhone version logs you in **when your phone joins the Wi-Fi** (via a small automation) and **whenever you tap a button**. It can't silently re-login every few minutes like Android.

**Install (free, no App Store):**
1. On a Windows PC or Mac install **Sideloadly** (sideloadly.io) or **AltStore** (altstore.io).
2. Download `HostelAutoLogin-unsigned.ipa` from the **Releases** page (look for "iOS IPA build").
3. Plug in your iPhone, open Sideloadly, drag the `.ipa` in, sign in with **your own Apple ID**, click Start.
4. On the iPhone: Settings → General → VPN & Device Management → trust your Apple ID. Turn on Developer Mode if iOS asks (Settings → Privacy & Security).
5. Free Apple IDs expire the app after **7 days**; open Sideloadly again to re-sign it. (A paid Apple Developer account lasts a year.)

**Use:**
1. Connect to your Wi-Fi, open the app → **Connect** tab → username and password → **Connect**. Tap **Yes** when it asks "Is this your Wi-Fi's login page?". Allow *Local Network* when iOS asks.
2. Open the **Automate** tab and follow the 1-minute steps (Shortcuts → Automation → Wi-Fi → "Log in to Wi-Fi"). After that it logs in by itself each time you join the Wi-Fi.
3. Optional one-tap login: Settings → Accessibility → Touch → Back Tap → pick "Log in to Wi-Fi", then double-tap the back of the phone any time.
4. Settings → Wi-Fi → (i) next to your network → **Auto-Join** on, so the iPhone reconnects to the Wi-Fi by itself.

## Several Wi-Fi names with the same login (hostel, college…)
Put each name on its own line in the Wi-Fi name box. If a network has a different login page, connect to it and tap **Connect** once more.

## If something doesn't work
| What you see | What to do |
|---|---|
| "Couldn't reach the login page" | Make sure you're connected to your Wi-Fi, then Connect again. |
| "didn't accept your username or password" | Re-type them carefully and Connect again. |
| "You already have internet" | Turn Wi-Fi off and on, then Connect again (the app needs to see the login page once). |
| "asks for a code or CAPTCHA" | Your Wi-Fi needs a code typed in; the app can't do that. |
| Stops working after a while (Android) | Settings tab → allow background running; on Xiaomi also enable Autostart. |

Your password is stored encrypted on your own device and only ever sent to the login page address you confirmed. No accounts, no servers, no tracking.
