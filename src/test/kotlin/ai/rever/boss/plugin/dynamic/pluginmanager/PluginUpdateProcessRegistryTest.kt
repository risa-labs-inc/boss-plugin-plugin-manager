package ai.rever.boss.plugin.dynamic.pluginmanager

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

class PluginUpdateProcessRegistryTest {
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
