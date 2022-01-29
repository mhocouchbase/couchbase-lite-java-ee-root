
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
$ echo "3.0.0" > version.text
```

#### 1.3 Go to couchbase-lite-java-ee

```
$ cd couchbase-lite-java-ee
```

#### 1.4 Create local.properties required by build.gradle

```
$ touch local.properties
```

### 2. Get the couchbase-lite-core shared library

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

