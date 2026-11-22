# Haval M6 Key Mapper

Автономное Android-приложение для переназначения кнопок на рулевом колесе в совместимом головном устройстве автомобиля Haval M6. 
Фоновая служба (foreground service) отслеживает пакеты данных от MCU (микроконтроллера управления) и применяет фильтр штатной службы обработки нажатий клавиш.

Standalone Android application for remapping steering-wheel buttons on a compatible
Haval M6 head unit. The foreground service subscribes to MCU button packets and applies the OEM
key-service filter.

## Build

Use JDK 17 and Android SDK 36. From this directory run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The Gradle wrapper JAR is expected at `gradle/wrapper/gradle-wrapper.jar`.
The app intentionally targets API 28 to match the Android 9 head unit and its
legacy background-service behavior; it is not intended for Google Play.

## Signing

The head unit requires a build signed with the matching platform key. By
default Gradle uses `signing/platform.pk8` if that file exists. You can
override it with `-PplatformPk8=...` or `HAVAL_PLATFORM_PK8`. Also set
`ANDROID_SDK_ROOT` (or `ANDROID_HOME`) to the SDK path, then run:

```sh
# Debug (for fast smoke checks)
./gradlew :app:assemblePlatformDebug

# Release (minified + resource shrink + platform-signed)
./gradlew :app:assemblePlatformRelease
```

Signed APK paths:
- `app/build/outputs/apk/debug/app-debug-platform.apk`
- `app/build/outputs/apk/release/app-release-platform.apk`

The release variant enables R8 minification and resource shrinking. The regular
debug APK is not platform-signed and is not expected to have access to OEM
privileged APIs.

## Head-unit requirements and safety

- The OEM key-event service and `adayo.hvac` Binder service must be installed.
- The head unit must provide the `mcucommproxy_jni` native library and grant the
  app access to MCU callbacks. The mapper only registers MCU receive callbacks;
  it does not send MCU or CAN commands.
- A matching platform signature may be required for Binder access and background
  activity launch. The public certificate alone cannot sign the APK.
- OEM key filtering is per OEM key name, so it blocks both short and long
  gestures. The app maps MCU status `0` to a short press, status `3` to a long
  press, and recognizes a held press after 800 ms if the MCU reports DOWN/UP.
- Climate actions use the installed HVAC Binder protocol. Temperature and fan
  commands pulse for 300 ms. AC state is read before toggling. Recirculation
  switches `ac_loop_mode3_i` between `1` and `0`; verify that signal semantics
  on the specific firmware before relying on it.
- If an OEM filter operation was started but restoration was not confirmed,
  use **Восстановить штатные кнопки и выключить** or reboot the head unit.

The Binder filter API returning without an exception does not by itself prove
that a firmware filters a key. Verify each remapped button on the head unit and
confirm that the original OEM action no longer occurs. Test recovery before
leaving remapping enabled.
