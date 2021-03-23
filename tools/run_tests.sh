#!/bin/bash

mkdir allTests
pushd allTests > /dev/null
../tools/mk_classpath.sh

java \
   -cp classes/java/test:classes/kotlin/test:resources/test:./* \
   org.junit.runner.JUnitCore \
      com.couchbase.lite.ArrayTest \
      com.couchbase.lite.AuthenticatorTest \
      com.couchbase.lite.BlobTest \
      com.couchbase.lite.ConcurrencyTest \
      com.couchbase.lite.CookieStoreTest \
      com.couchbase.lite.DatabaseEncryptionTest \
      com.couchbase.lite.DatabaseTest \
      com.couchbase.lite.DictionaryTest \
      com.couchbase.lite.DocumentTest \
      com.couchbase.lite.EncodingTest \
      com.couchbase.lite.EncryptionKeyTest \
      com.couchbase.lite.ErrorCaseTest \
      com.couchbase.lite.FullTextFunctionTest \
      com.couchbase.lite.LiveQueryTest \
      com.couchbase.lite.LoadTest \
      com.couchbase.lite.Local2LocalReplicatorTest \
      com.couchbase.lite.LogTest \
      com.couchbase.lite.MessageEndpointTest \
      com.couchbase.lite.MigrationTest \
      com.couchbase.lite.MiscTest \
      com.couchbase.lite.NotificationTest \
      com.couchbase.lite.PreInitTest \
      com.couchbase.lite.PredictiveQueryTest \
      com.couchbase.lite.QueryChangeTest \
      com.couchbase.lite.QueryTest \
      com.couchbase.lite.ReplicatorChangeListenerTokenTest \
      com.couchbase.lite.ReplicatorConflictResolutionTests \
      com.couchbase.lite.ReplicatorMiscTest \
      com.couchbase.lite.ReplicatorOfflineTest \
      com.couchbase.lite.ReplicatorPendingDocIdTest \
      com.couchbase.lite.ResultTest \
      com.couchbase.lite.SaveConflictResolutionTests \
      com.couchbase.lite.SimpleDatabaseTest \
      com.couchbase.lite.URLEndpointListenerTest \
      com.couchbase.lite.URLEndpointTest \
      com.couchbase.lite.internal.ExecutionServiceTest \
      com.couchbase.lite.internal.KeyStoreManagerTest \
      com.couchbase.lite.internal.TLSIdentityTest \
      com.couchbase.lite.internal.core.C4AllDocsPerformanceTest \
      com.couchbase.lite.internal.core.C4BlobStoreTest \
      com.couchbase.lite.internal.core.C4CollatedQueryTest \
      com.couchbase.lite.internal.core.C4DatabaseTest \
      com.couchbase.lite.internal.core.C4DocumentTest \
      com.couchbase.lite.internal.core.C4FleeceTest \
      com.couchbase.lite.internal.core.C4KeyPairTest \
      com.couchbase.lite.internal.core.C4ListenerTest \
      com.couchbase.lite.internal.core.C4MutableFleeceTest \
      com.couchbase.lite.internal.core.C4NestedQueryTest \
      com.couchbase.lite.internal.core.C4ObserverTest \
      com.couchbase.lite.internal.core.C4PathsQueryTest \
      com.couchbase.lite.internal.core.C4QueryTest \
      com.couchbase.lite.internal.core.C4Test \
      com.couchbase.lite.internal.security.SignatureTest \
      com.couchbase.lite.utils.StringUtilsTest \
      com.couchbase.lite.utils.URIUtilsTest

popd > /dev/null

