# Contributing to Pixlink

Thanks for helping! A few ground rules:

1. **Open an issue first** for anything bigger than a small fix, so we can agree on the approach.
2. **Keep the protocol in sync.** If you add or change a message, update `docs/PROTOCOL.md` and both apps in the same PR.
3. **Play Store safety.** Don't add restricted permissions (SMS, call log, all-files access, accessibility service) to the Android manifest. Restricted features go through Power mode (Shizuku).
4. **No Google or Microsoft branding** in the UI, icons, or store assets.
5. **Style:** Kotlin follows the official Kotlin style guide; C# follows .NET conventions. Keep functions small and commented where Android/Windows quirks are involved.

## Dev setup

See the Building section of the README.
