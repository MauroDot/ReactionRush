# Reaction Rush
A native Android reaction-time game by Mauro Interactive.

## Gameplay
Tap to start, wait for green, then tap as quickly as possible. False starts are detected. Best score and attempt count persist locally.

## Cloud APK build
Push this project to a GitHub repository. The included GitHub Actions workflow builds a debug APK on every push to `main`, or manually from the Actions tab. Download the `ReactionRush-debug-apk` artifact and install `app-debug.apk` on an Android device.

## Local build
Open the project in Android Studio and run it on a device, or build `:app:assembleDebug` with a compatible Gradle/JDK 17 Android environment.
