# Making Tethr Link "official" without the Play Store

Phones block or warn about Tethr Link today for three separate reasons. Each one has its own fix.

| What people see | Why | Fix |
| --- | --- | --- |
| **Play Protect: "Unsafe app blocked"** or "App scan required" | Google has never seen this app before | Register the app with Google (below). Until then: **More details → Install anyway**. |
| **"Unverified developer"** (Android developer verification) | From 2026, certified Android phones only install apps whose developer and package are registered with Google. Enforcement started in Brazil, Indonesia, Singapore and Thailand in September 2026, and rolls out worldwide from 2027. | Register the app with Google (below). |
| **"Restricted setting"** when turning on Notification access, Screen control or SMS | Android does this for every app installed from a file rather than an app store | Tethr Link explains it in the app: App info → ⋮ → **Allow restricted settings**. Apps installed by an app store (including F-Droid-style stores that use Android's installer session) usually avoid it. |

## The fix: register Tethr Link with Google (no Play Store listing)

Google's **Android Developer Console** is the official route for apps that are only distributed outside Google Play. It doesn't publish anything; it links your identity to Tethr Link's package name and signing key, so phones treat Tethr Link as coming from a verified developer.

Only the account owner can do this, because it needs your ID and a payment.

1. **Create an account** at the Android Developer Console (search "Android Developer Console developer verification").
   - **Full distribution account:** a one-off **US$25** fee, identity verification (government ID), and a verified contact email and phone number. The fee is the same as Google Play's. This is the one Tethr Link needs, because anyone can download it.
   - **Limited distribution account:** free, no ID, but only for up to 20 devices. Fine for testing on your own phones; not for public releases.
2. **Verify your website.** Google asks for an official site, verified with Google Search Console. Use `https://fatalquackers.github.io/pixlink`.
3. **Register the package name** `app.pixlink` on the **Packages** page.
4. **Prove you own the signing key.** Google gives you a small file or token to include in a signed APK. Sign it with the same key the releases use: it's the `pixlink-release.jks` you saved (the one in the `ANDROID_KEYSTORE_BASE64` GitHub secret), alias `pixlink`. Upload that APK.
   - Keep that keystore safe. If it's lost, you can't register or update the app under the same name.
5. **Wait for "Registered".** It usually takes hours. Every future release signed with the same key is covered automatically.

## Also worth doing

- **Report Play Protect's warning as a false positive.** Google has an "appeal a Play Protect warning" form for developers. Submit the latest `Pixlink-android.apk` with a short note that it's the open-source app at github.com/FatalQuackers/pixlink. Repeat if a new version gets flagged.
- **Explain the permissions.** Tethr Link asks for notification access, accessibility (Screen control) and SMS, which scanners treat as high-risk. The app only uses them for what's described on each screen, and the source is public, which helps the appeal.
- **Offer an app store install later.** Being listed in an open-source store (for example IzzyOnDroid, used through the Droid-ify or Neo Store apps) means updates come through a store installer. It doesn't replace developer verification, but it makes restricted settings less likely.

## Until it's registered

People can always install it themselves:

1. Play Protect dialog: **More details → Install anyway**.
2. If "Install anyway" is missing: Play Store → profile picture → **Play Protect** → ⚙ → turn off **Scan apps with Play Protect**, install, then turn it back on.
3. If the phone shows "unverified developer": Android's one-time "advanced flow" (a 24-hour wait, then re-authenticate), or install from a PC with `adb install Pixlink-android.apk`.
