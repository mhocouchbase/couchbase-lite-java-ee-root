
# Couchbase Lite 3.x

**Couchbase Lite** is an embedded lightweight, document-oriented (NoSQL), syncable database engine.

Couchbase Lite 2.0 has a completely new set of APIs. The implementation is on top of [Couchbase Lite Core](https://github.com/couchbase/couchbase-lite-core), which is also a new cross-platform implementation of database CRUD and query features, as well as document versioning.

As with most CBL products, this one has Community and Enterprise editions.
This is the Enterprise edition.  The code is proprietary and not for publication
outside Couchbase.

## Prereqs

- Java 8+
- Android SDK
- Android Studio
- git and repo

## Setup

1. There are several products that share code written in the Java language
(Android, Java-Appserver, and Java-Swing, among them.  Each has a CE and EE version).
This project is one of several necessary to build and of them.  To do this
use the `repo` tool.  E.g., for the mercury release, you would do something like this:
```
mkdir mercury
cd mercury
repo init -u ssh://<user>@review.couchbase.org/manifest.git -m couchbase-lite-android/mercury.xml
repo sync
```
The top level directory now has the necessary projects, each in its canonical location.

2. To work on a product, open the appropriate *subdirectory* from your IDE.
To work on this product, for instance (the Android EE product),
open `/couchbase-lite-android-ee/build.gradle`, with Android Studio.
Don't try to open the top level `build.gradle` from your IDE.  It might
work but it probably won't be very useful.

3. If you intend to work specifically on the CE project, don't do it here.
For historical reasons the CE project is maintained as a git
project called `couchbase-lite-android-ce`.  This top-level repo simply
recreates the canonical environment (minus the proprietary EE code),
using git submodules, instead of the repo tool.  It is a good idea to
check in on this project, occasionally, to be sure that it is up to date,
healthy and working.  It is our face to the OSS community.

## Building
1. There are three convenient targets for developer use:
- `smokeTest` compiles and does static analysis
- `fullTest` runs tests that require an emulator or an attached device

2. At the moment, the build process for the Java product
builds its own instance of Core.  This is probably broken: those bits
are not independently tested etc.  It would be better if this build
could pull the tested bits from somewhere... or, better yet, express
a dependency so that the appropriate Core lib could be downloaded by the
client build.

3. By default, the build process builds all of the default Android ABIs.
This takes a long time and is not, typically, useful during development.
The property `targetAbis` is a comma separated list of ABIs to be built.
If non-null, only the ABIs in the list will be built.  Putting this
property into `~/.gradle/gradle.properties` can save a lot of time.
```
# ABIs that should be generated
targetAbis=armeabi-v7a
```

4. The gradle build also uses the property `testFilter` to disable
automated tests.  The value of the property is a comma separated list
of FQNs of Annotation classes that are  applied to automated tests.
Tests that are annotated with annotations in the list will *not* be run.

5. The gradle build also uses properties when publishing to Maven.
See the build file for details.

6. Because there is a significant amount of code shared by all of the products
it is a good idea to run all tests for all products before submitting.
To do this use build/test from the root directory.  That will cause a build
and test of each of the Java products.  All of the targets mentioned in
#1 should work.

## Testing

The testing process for the Android EE and CE products is a bit convoluted.
The goal is to test exactly the bits that are going to be shipped while
still allowing convenient use of tests from the IDE.  In order to do that
there is a separate module, called `test` which builds a test application
and depends on internal Maven to provide the CBL library.  Running
tests this way can be useful if you want to test existing bits.

1. First, edit the repositories *in the `test` module* so that they
include the location of the bits you want to test.  For example:
```
repositories {
    mavenLocal()
    maven { url "http://proget.build.couchbase.com/maven2/cimaven/" }
    google()
    mavenCentral()
}
```

2. You can, absolutely, use your local maven repo.  Just push something
there, first:
```
./gradlew publishToMavenLocal
```

3. Push the test app to your device/emulator.  It is the `-PautomatedTests=true`
flag that causes the test application to be built.
```
./gradlew -PautomatedTests=true installDebug installDebugAndroidTest
```

4. Run the test or tests of interest.  Note that the application is named `com.couchbase.lite.test.test`.
```
adb shell am instrument -w -e class <test-class-fqn>(#<optional-test-method>) com.couchbase.lite.test.test/android.support.test.runner.AndroidJUnitRunner
```


## Documentation

There is more documentation here:
- [Bindings](https://hub.internal.couchbase.com/confluence/display/cbeng/CouchbaseLite+Bindings%3A+Java)
- [Manifests](https://hub.internal.couchbase.com/confluence/display/cbeng/The+Wonderful+World+of+Manifests)
- [Versions](hhttps://hub.internal.couchbase.com/confluence/display/cbeng/The+Wonderful+World+of+Versions)
