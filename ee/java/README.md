
# Couchbase Lite Java Enterprise Edition

This repo contains code common to all Couchbase Lite Java Enterprise Products

## How to build couchbase-lite-java-ee

### 1. Prepare projects

#### 1.1 Clone the following repositories

```
$ git clone https://github.com/couchbase/couchbase-lite-java.git
$ git clone https://github.com/couchbaselabs/couchbase-lite-java-ee.git
$ git clone https://github.com/couchbase/couchbase-lite-core.git
$ cd couchbase-lite-core && git submodule update --init --recursive && cd ..
$ git clone https://github.com/couchbaselabs/couchbase-lite-core-EE.git
```

#### 1.2 Create version.text

At the root of your working space, create version.text file.

```
$ echo "2.8.0" > version.text
```

#### 1.3 Go to couchbase-lite-java-ee

```
$ cd couchbase-lite-java-ee
```

#### 1.4 Create local.properties required by build.gradle

```
$ touch local.properties
```

### 2. Get couchbase-lite-core shared library


#### 2.1 Option 1: Build couchbase-lite-core

**MacOS / Linux**

```
$ ../couchbase-lite-java/scripts/build_litecore.sh -e EE 
```

**Windows**

```
$ ..\couchbase-lite-java\scripts\build_litecore.bat 2019 EE
```
** Assuming that the Visual Studio 2019 with C++ development libraries was installed.

#### 2.2 Option 2: Download couchbase-lite-core

#### 2.2.1 Download LiteCore

**MacOS**

```
$ ../couchbase-lite-java/scripts/fetch_litecore.sh -n http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore -v macos -e EE
```

**Linux**

```
$ ../couchbase-lite-java/scripts/fetch_litecore.sh -n http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore -v linux -e EE
```

**Windows**

```
$ ..\couchbase-lite-java\scripts\fetch_litecore.ps1 http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore EE
```

#### 2.2.2 Build mbedcrypto required by couchbase-lie-java's JNI

**MacOS / Linux**

```
$ ../couchbase-lite-java/scripts/build_litecore.sh -e EE -l mbedcrypto
```

**Windows**

Assuming that the Visual Studio 2019 with C++ development libraries was installed.

```
$ ..\couchbase-lite-java\scripts\build_litecore.bat 2019 EE mbedcrypto
```

### 3. Build couchbase-lite-java

#### 3.1 Build and Test

```
$ ./gradlew build 
```

#### 3.2 Create distribution zip file

```
$ ./gradlew distZip 
```

The generated zip file will located at `build/distribution` directory.
