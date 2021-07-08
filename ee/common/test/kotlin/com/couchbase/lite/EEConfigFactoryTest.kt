//
// Copyright (c) 2020, 2017 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test


const val EE_CONFIG_FACTORY_TEST_STRING = "you stood out like a ruby"

class EEConfigFactoryTest : BaseDbTest() {
    @Test
    fun testDatabaseConfigurationFactory() {
        val config = DatabaseConfigurationFactory.create(databasePath = EE_CONFIG_FACTORY_TEST_STRING)
        assertEquals(EE_CONFIG_FACTORY_TEST_STRING, config.directory)
    }

    @Test
    fun testFullTextIndexConfigurationFactoryCopy() {
        val config1 = DatabaseConfigurationFactory.create(databasePath = EE_CONFIG_FACTORY_TEST_STRING)

        val key = EncryptionKey("sekrit")
        val config2 = config1.create(encryptionKey = key)
        assertNotEquals(config1, config2)
        assertEquals(EE_CONFIG_FACTORY_TEST_STRING, config2.directory)
        assertEquals(key, config2.encryptionKey)
    }

    @Test
    fun testReplicatorConfigurationFactory() {
        val target = DatabaseEndpoint(baseTestDb)
        val config = ReplicatorConfigurationFactory.create(database = baseTestDb, target = target)
        assertEquals(baseTestDb, config.database)
        assertEquals(ReplicatorType.PUSH_AND_PULL, config.type)
        assertFalse(config.isContinuous)
        assertEquals(target, config.target)
        assertEquals(0, config.maxAttempts)
    }

    @Test(expected = IllegalStateException::class)
    fun testReplicatorConfigurationFactoryNullExp() {
        ReplicatorConfigurationFactory.create()
    }

    @Test
    fun testReplicatorConfigurationFactoryCopy() {
        val target = DatabaseEndpoint(baseTestDb)
        val config1 = ReplicatorConfigurationFactory.create(database = baseTestDb, target = target)
        val config2 = config1.create(maxAttempts = 7)
        assertNotEquals(config1, config2)
        assertEquals(baseTestDb, config2.database)
        assertEquals(ReplicatorType.PUSH_AND_PULL, config2.type)
        assertFalse(config2.isContinuous)
        assertEquals(target, config2.target)
        assertEquals(7, config2.maxAttempts)
    }

    @Test
    fun testMessageEndpointListenerConfigurationFactory() {
        val config = MessageEndpointListenerConfigurationFactory
            .create(database = baseTestDb, protocolType = ProtocolType.MESSAGE_STREAM)
        assertEquals(baseTestDb, config.database)
        assertEquals(ProtocolType.MESSAGE_STREAM, config.protocolType)
    }

    @Test(expected = IllegalStateException::class)
    fun testMessageEndpointListenerConfigurationFactoryNullExp() {
        MessageEndpointListenerConfigurationFactory.create()
    }

    @Test
    fun testMessageEndpointListenerConfigurationFactoryCopy() {
        val config1 = MessageEndpointListenerConfigurationFactory
            .create(database = baseTestDb, protocolType = ProtocolType.MESSAGE_STREAM)
        val config2 = config1.create(protocolType = ProtocolType.BYTE_STREAM)
        assertNotEquals(config1, config2)
        assertEquals(baseTestDb, config2.database)
        assertEquals(ProtocolType.BYTE_STREAM, config2.protocolType)
    }


    @Test
    fun testURLEndpointListenerConfigurationFactory() {
        val config = URLEndpointListenerConfigurationFactory.create(database = baseTestDb, port = 67)
        assertEquals(baseTestDb, config.database)
        assertEquals(67, config.port)
    }

    @Test(expected = IllegalStateException::class)
    fun testURLEndpointListenerConfigurationFactoryNullExp() {
        URLEndpointListenerConfigurationFactory.create()
    }

    @Test
    fun testURLEndpointListenerConfigurationFactoryCopy() {
        val config1 = URLEndpointListenerConfigurationFactory.create(database = baseTestDb, port = 67)
        val config2 = config1.create(port = 67, networkInterface = "en1")
        assertNotEquals(config1, config2)
        assertEquals(baseTestDb, config2.database)
        assertEquals(67, config2.port)
        assertEquals("en1", config2.networkInterface)
    }
}