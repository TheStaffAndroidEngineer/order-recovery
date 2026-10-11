# Order Recovery

Source-code companion for *The Staff Android Engineer*. Chapter explanations, exercises, written solutions and editorial records are distributed with the book, not this repository.

## Setup

Use JDK 27 and this repository’s pinned Gradle wrapper/version catalog.
Install the Android SDK/platform/build tools specified in `app/build.gradle.kts`. Set `ANDROID_HOME` or an untracked `local.properties` file.

```sh
./gradlew :app:assembleDebug
./gradlew tasks --all
```

Build flavors, deliberate failing implementations and test tasks are described in the book. Select the documented checkpoint; do not treat learner/negative failures as a reference result.

## Local service

Run the controlled Kotlin service in a separate terminal:

```sh
./gradlew :server:run
```

Local SDK settings, runtime data, generated APKs, captured evidence and book text are excluded. Keep required third-party licences/notices with the code. This repository is private; no new companion-code licence is granted by this upload.
