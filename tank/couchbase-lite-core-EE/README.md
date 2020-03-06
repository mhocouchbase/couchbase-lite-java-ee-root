# LiteCore Enterprise Edition

This **private** repo contains EE-only functionality for [LiteCore](https://github.com/couchbase/couchbase-lite-core), the cross-platform core of Couchbase Lite.  The source in this repo is not open source (until further notice compiled versions are bound by the [Enterprise Subscription License Agreement](https://www.couchbase.com/ESLA-11132015)).  **IT MUST NOT BE DISTRIBUTED OUTSIDE OF COUCHBASE**

At a high level, EE adds peer-to-peer replication and encryption to Couchbase Lite. The code in this repo implements

* WebSocket server functionality (from CivetWeb)
* Glue code to wrap a CivetWeb WebSocket connection in a LiteCore `C4Socket` that can be passed to the replicator
* A subclass of LiteCore's REST listener that registers the `/db/_blipsync` endpoint for incoming WebSocket sync connections
* A minimal listener class that listens for sync connections without implementing the REST API
* Encryption via the [SQLite Encryption Extension](https://www.sqlite.org/see/doc/trunk/www/index.wiki)

The binaries produced are

* `LiteCoreREST_EE`, an enhanced version of the CE LiteCoreREST library with the sync endpoint
* `LiteCoreREST_EE`, a dynamic library with the same API as LiteCoreREST but which only implements sync, not REST
* `LiteCoreServ_EE`, an enhanced version of the LiteCoreServ command-line tool with the sync endpoint. (Use the `--sync` flag to enable incoming sync requests.)
* `LiteCore` from the public repo is altered to include encryption

## Checkout

This repo is not standalone: it uses relative paths to access LiteCore source code. Therefore **it _must_ be checked out next to the `couchbase-lite-core` repo in the filesystem**:
```
    /WorkDir/
        couchbase-lite-core/
        couchbase-lite-core-EE/
```
(This may cause it to be within the `vendor` directory of an outer repo like couchbase-lite-ios that has LiteCore as a submodule. That's OK, it won't confuse the outer repo. Git is smart enough to ignore subdirectories that are themselves Git repos.)

## Building

### Xcode 

Open `LiteCore EE.xcodeproj`, select the desired scheme and build it. (Press Cmd-Shift-I for a release/optimized build.).  This is the only supported mode for iOS.

### Cmake
The CMakeLists.txt file in the repo will build all targets for all of our supported platforms except iOS.  For example:

```sh
mkdir build
cd build
cmake .. # or cmake -DCMAKE_BUILD_TYPE=RelWithDebInfo ..
make -j8 LiteCore
``
