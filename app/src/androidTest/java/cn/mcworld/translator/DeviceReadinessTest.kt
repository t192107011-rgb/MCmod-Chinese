package cn.mcworld.translator

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.mcworld.translator.core.DisplayCommand
import cn.mcworld.translator.core.SafeWorldArchive
import cn.mcworld.translator.storage.SafWorkingCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceReadinessTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun activityStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        assertFalse(activity.isFinishing)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun noNetworkPermission() {
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("APK must have no INTERNET permission", "android.permission.INTERNET" in permissions)
    }

    @Test fun realBedrockFixtureWriteExportAndReread(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val fixture = File(context.filesDir, "device-test-fixture.mcworld")
            assertTrue("Side-load the pinned real Amulet 1.18 fixture using tools/device_validation.py", fixture.isFile)
            val id = UUID.randomUUID().toString()
            val store = ProjectStore(context, id)
            try {
                val world = SafeWorldArchive().extract(fixture, File(store.directory, "fixture"))
                val state = ProjectState(id, world = world.absolutePath)
                val operations = WorldOperations(context, store)
                operations.extract(state)
                val sign = state.entries.single { it.original == "<----\n§r---->\n§r" }
                sign.translation = "向左\n§r向右\n§r"; sign.status = "手动修改"
                val exported = operations.export(state)
                assertTrue(exported.isFile && exported.length() > 0)
                val reread = SafeWorldArchive().extract(exported, File(store.directory, "reread"))
                val state2 = ProjectState(id, world = reread.absolutePath)
                operations.extract(state2)
                assertTrue(state2.entries.any { it.original == "向左\n§r向右\n§r" })
                Log.i("MCWT_TEST", "Real Bedrock native round trip passed; ${state.entries.size} extracted text entries")
                exported.delete()
            } finally { store.directory.deleteRecursively() }
        }
    }

    @Test fun inaccessibleSafUriFailsAndCleansCopy(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("world-import-") }.map { it.name }.toSet()
            try {
                SafWorkingCopy(context).copyArchive(Uri.parse("content://cn.mcworld.nonexistent.provider/map.mcworld"))
                fail("Inaccessible provider must not succeed")
            } catch (expected: java.io.FileNotFoundException) {
                val after = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("world-import-") }.map { it.name }.toSet()
                assertEquals(before, after)
            }
        }
    }

    @Test fun nestedCommandProtectionOnDevice() {
        val command = "execute as @a at @s run tellraw @s {\"rawtext\":[{\"text\":\"Hello %s\"},{\"score\":{\"name\":\"@s\",\"objective\":\"quest\"}}]}"
        val plan = DisplayCommand.inspect(command)
        assertEquals(command.replace("Hello %s", "你好 %s"), plan.rewrite(mapOf(0 to "你好 %s")))
    }
}

