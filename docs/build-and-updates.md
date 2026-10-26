# Build and update notes

GitHub Actions builds an installable **development/debug APK** on pushes to main. Open the Actions run, then download the ZIP under Artifacts and extract the APK on Android.

### Important
- Until a stable signing key is configured, Android debug signing changes between GitHub runners. New builds may not install as upgrades. **Back up projects before uninstalling an old development APK.**
- For stable in-place upgrades, generate a long-lived Android signing keystore offline, back it up securely and set GitHub Actions secrets: SHORTS_KEYSTORE_BASE64, SHORTS_STORE_PASSWORD, SHORTS_KEY_ALIAS, SHORTS_KEY_PASSWORD. Never commit the keystore.
- The workflow is currently a developer artifact builder, **not yet a production GitHub Release publisher**.
- V0.1 has an online GitHub Release update check and a link to download approved future releases, not silent background installation.
- A future milestone will handle verified APK downloads with a progress display and the normal Android installation consent screen.
- Version code currently starts at 1 and must increase for each published APK.
