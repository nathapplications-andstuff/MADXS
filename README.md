# MADXtreamSports (MADXS)

Android app for MADXtreamSports. Record skate or boxing on one phone, get a UIS score (0–1000), follow friends, and stream a live 3D path.

Website (watch as guest): https://madxtreamsports.com

Install: Download `MADXtreamSports.zip` from [Releases](https://github.com/nathapplications-andstuff/MADXS/releases/latest), unzip, open `MADXtreamSports.apk`, allow install from the browser if asked.

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

Package `com.nathangamalnasser.natapps.recorder` must stay in sync with Firebase `google-services.json`.
