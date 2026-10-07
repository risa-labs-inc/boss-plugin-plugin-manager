package ai.rever.boss.plugin.dynamic.pluginmanager

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.management.ObjectName
import javax.management.modelmbean.ModelMBeanInfoSupport
import javax.management.modelmbean.ModelMBeanOperationInfo
import javax.management.modelmbean.RequiredModelMBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginUpdateProcessRegistryTest {
    @Test
    fun `concurrent bootstrap callers share one registry and one owner claim`() {
        val name = ObjectName("boss.plugins.test:type=UpdateLeaseRegistry,fixture=${UUID.randomUUID()}")
        val server = ManagementFactory.getPlatformMBeanServer()
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                List(workers) {
                    pool.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS)) { "Bootstrap start timed out" }
                            val owners = PluginUpdateProcessRegistry.owners(name)
                            val token = Any()
                            Triple(owners, token, owners.putIfAbsent("fixture-owner", token) == null)
                        },
                    )
                }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Bootstrap workers did not become ready")
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            val owners = results.first().first
            results.forEach { assertSame(owners, it.first) }
            val winners = results.filter { it.third }
            assertEquals(1, winners.size, "Only one concurrent caller may claim the owner")
            val winner = winners.single()
            assertSame(winner.second, owners["fixture-owner"])
            assertTrue(owners.remove("fixture-owner", winner.second))
        } finally {
            start.countDown()
            pool.shutdownNow()
            // Never remove a registry while a fixture worker could still be using it.
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Bootstrap workers did not finish")
            if (server.isRegistered(name)) server.unregisterMBean(name)
        }
    }

    @Test
    fun `registry uses bootstrap JDK objects and serializable management metadata`() = isolated { name ->
        val owners = PluginUpdateProcessRegistry.owners(name)
        assertSame(owners, PluginUpdateProcessRegistry.owners(name))
        assertEquals(null, owners.javaClass.classLoader)
        val token = Any()
        owners["fixture"] = token
        assertEquals(null, token.javaClass.classLoader)
        val server = ManagementFactory.getPlatformMBeanServer()
        assertEquals(RequiredModelMBean::class.java.name, server.getObjectInstance(name).className)
        ObjectOutputStream(ByteArrayOutputStream()).use { it.writeObject(server.getMBeanInfo(name)) }
    }

    @Test
    fun `registry leaves system properties string compatible`() = isolated { name ->
        val properties = System.getProperties()
        val before = properties.entries.filter { it.key !is String || it.value !is String }.toSet()
        PluginUpdateProcessRegistry.owners(name)
        val after = properties.entries.filter { it.key !is String || it.value !is String }.toSet()
        assertEquals(before, after)
        assertFalse(properties.containsKey("boss.plugins.updateLease.processOwners"))
        properties.store(ByteArrayOutputStream(), "fixture")
        PrintStream(ByteArrayOutputStream()).use { properties.list(it) }
    }

    @Test
    fun `foreign registry metadata and incompatible managed values fail closed`() {
        isolated { name ->
            register(name, "toString", AtomicReference(ConcurrentHashMap<String, Any>()))
            assertFailsWith<IllegalStateException> { PluginUpdateProcessRegistry.owners(name) }
        }
        isolated { name ->
            register(name, "get", AtomicReference(HashMap<String, Any>()))
            assertFailsWith<IllegalStateException> { PluginUpdateProcessRegistry.owners(name) }
        }
    }

    private fun register(name: ObjectName, method: String, resource: AtomicReference<*>) {
        val operation = ModelMBeanOperationInfo("Fixture", AtomicReference::class.java.getMethod(method))
        val info = ModelMBeanInfoSupport(
            RequiredModelMBean::class.java.name, "Fixture", null, null, arrayOf(operation), null,
        )
        val bean = RequiredModelMBean(info)
        bean.setManagedResource(resource, "ObjectReference")
        ManagementFactory.getPlatformMBeanServer().registerMBean(bean, name)
    }

    private fun isolated(test: (ObjectName) -> Unit) {
        val name = ObjectName("boss.plugins.test:type=UpdateLeaseRegistry,fixture=${UUID.randomUUID()}")
        val server = ManagementFactory.getPlatformMBeanServer()
        try {
            test(name)
        } finally {
            if (server.isRegistered(name)) server.unregisterMBean(name)
        }
    }
}
