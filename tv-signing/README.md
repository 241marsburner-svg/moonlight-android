# Artemide TV signing key

This keystore signs the `tv` build type (app ID `com.limelight.artemidetv`).

It is committed on purpose: GitHub Actions needs the same key on every run so that
each new APK installs as an update over the previous one, keeping your pairing and
settings. It is only meant for personal sideloading, not for publishing anywhere.

- Store / key password: `artemidetv`
- Alias: `artemidetv`
