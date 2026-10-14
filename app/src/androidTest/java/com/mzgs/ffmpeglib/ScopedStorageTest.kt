package com.mzgs.ffmpeglib

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ScopedStorageTest {
    @Test fun exportsCompletedOutputToDownloadsWithoutStoragePermission() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ffmpegx-storage-${System.nanoTime()}"
        val bytes = ByteArray(1024) { it.toByte() }
        var source: File? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val path = MainActivity::class.java.getDeclaredMethod("getOutputPath", String::class.java)
                        .apply { isAccessible = true }.invoke(activity, name) as String
                    source = File(path).apply { writeBytes(bytes) }
                    assertTrue(path.startsWith(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)!!.path))
                    MainActivity::class.java.getDeclaredMethod("saveToDownloads", String::class.java)
                        .apply { isAccessible = true }.invoke(activity, path)
                }
            }
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(source!!.name), null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(1))
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                try {
                    context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
                } finally { context.contentResolver.delete(uri, null, null) }
            }
        } finally { source?.delete() }
    }
}
