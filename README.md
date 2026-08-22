# MADXtreamSports (MADXS)

Android app for MADXtreamSports (MADXS). MAD means Motion Active Data. Record skate or boxing on one phone, get a UIS score (0–1000), follow friends, and stream a live 3D path.

Website (watch as guest): https://madxtreamsports.com

Install: on https://madxtreamsports.com click **Download the app**. That fetches `MADXtreamSports.apk` from GitHub Releases. Allow install from the browser if asked.

## Run

Open this folder in Android Studio, or:

```
gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/MADXtreamSports.apk`

## Test

```
gradlew.bat testDebugUnitTest
```

Package `com.nathangamalnasser.whatupapps.madxs` must stay in sync with Firebase `google-services.json`.
