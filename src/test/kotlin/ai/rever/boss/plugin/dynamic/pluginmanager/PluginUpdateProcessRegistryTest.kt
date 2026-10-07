package ai.rever.boss.plugin.dynamic.pluginmanager

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginUpdateProcessRegistryTest {
    @Test
    fun `independent classloader copies share bootstrap String property claims`() {
        val type = PluginUpdateProcessRegistry::class.java
        val bytes = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        val loader = object : ClassLoader(type.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == type.name) {
                    synchronized(getClassLoadingLock(name)) {
                        (findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)).also {
                            if (resolve) resolveClass(it)
                        }
                    }
                } else {
                    super.loadClass(name, resolve)
                }
        }
        val copy = loader.loadClass(type.name)
        assertTrue(copy !== type)
        val instance = copy.getField("INSTANCE").get(null)
        val owners = PluginUpdateProcessRegistry.owners()
        assertSame(owners, copy.getMethod("owners").invoke(instance))
        val path = "fixture-${UUID.randomUUID()}"
        val key = PluginUpdateProcessRegistry.ownerKey(path)
        assertEquals(key, copy.getMethod("ownerKey", String::class.java).invoke(instance, path))
        val token = UUID.randomUUID().toString()
        try {
            assertEquals(null, owners.putIfAbsent(key, token))
            assertEquals(String::class.java, key.javaClass)
            assertTrue(owners[key] is String)
            assertEquals(null, key.javaClass.classLoader)
            assertEquals(null, owners[key]!!.javaClass.classLoader)
        } finally { owners.remove(key, token) }
    }

    @Test
    fun `concurrent callers share properties and exactly one owner claim`() {
        val key = PluginUpdateProcessRegistry.ownerKey("fixture-${UUID.randomUUID()}")
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val tokens = List(8) { UUID.randomUUID().toString() }
        val owners = PluginUpdateProcessRegistry.owners()
        try {
            val futures = tokens.map { token ->
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    val shared = PluginUpdateProcessRegistry.owners()
                    Triple(shared, token, shared.putIfAbsent(key, token) == null)
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            results.forEach { assertSame(owners, it.first) }
            val winner = results.filter { it.third }.single()
            assertEquals(winner.second, owners[key])
            assertTrue(owners.remove(key, winner.second))
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            tokens.forEach { owners.remove(key, it) }
        }
    }

    @Test
    fun `active claims preserve String property diagnostics`() {
        val owners = PluginUpdateProcessRegistry.owners()
        val before = owners.entries.filter { it.key !is String || it.value !is String }.toSet()
        val key = PluginUpdateProcessRegistry.ownerKey("fixture-${UUID.randomUUID()}")
        val token = UUID.randomUUID().toString()
        try {
            assertEquals(null, owners.putIfAbsent(key, token))
            assertEquals(before, owners.entries.filter { it.key !is String || it.value !is String }.toSet())
            owners.store(ByteArrayOutputStream(), "fixture")
            PrintStream(ByteArrayOutputStream()).use { owners.list(it) }
        } finally { owners.remove(key, token) }
    }

    @Test
    fun `conditional ownership preserves foreign values and newer tokens`() {
        // Deliberately malformed foreign values are confined to an isolated fixture Properties.
        val owners = Properties()
        val key = PluginUpdateProcessRegistry.ownerKey("fixture")
        val token = UUID.randomUUID().toString()
        for (foreign in listOf<Any>("foreign", Any())) {
            owners[key] = foreign
            assertSame(foreign, owners.putIfAbsent(key, token))
            assertFalse(owners.remove(key, token))
            assertSame(foreign, owners[key])
        }
        owners.clear()
        assertEquals(null, owners.putIfAbsent(key, token))
        val newer = UUID.randomUUID().toString()
        assertTrue(owners.remove(key, token))
        assertEquals(null, owners.putIfAbsent(key, newer))
        assertFalse(owners.remove(key, token))
        assertEquals(newer, owners[key])
    }
}
