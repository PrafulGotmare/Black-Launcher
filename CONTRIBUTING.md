# Contributing to Black Launcher

Thanks for helping! The goals of this project:
- Pure black, minimal, tiny (no libraries, no icons by default)
- No internet permission, no tracking, no background services
- Near-zero battery use

A change that breaks one of these goals will usually not be merged.

## Ways to help
- Report bugs or device-specific problems (open an Issue: phone model, Android version, what happened)
- Suggest features (open an Issue first, so we can check battery/privacy impact)
- Send code (Pull Request)
- Translate, test on other phones, improve the docs

## Project layout
Source files sit in the repo root (flat layout). `build.sh` arranges them into an Android project and builds a signed APK and AAB using the Ubuntu Android tools. No Android Studio or Gradle needed.

- `MainActivity.java` – the whole launcher UI
- `LockAdmin.java` – device admin, used only for double-tap lock
- `AndroidManifest.xml`, `themes.xml`, `device_admin.xml`, `ic_launcher.png` – resources

## Build
Run the **Actions → Build APK and AAB** workflow, or `./build.sh` on Ubuntu/Debian. Output goes to `dist/`.

## Pull request checklist
- [ ] No new permissions (or explain why)
- [ ] No new libraries
- [ ] No background services, timers or polling
- [ ] Tested on a real phone (say which)
- [ ] Never commit keystores or passwords
