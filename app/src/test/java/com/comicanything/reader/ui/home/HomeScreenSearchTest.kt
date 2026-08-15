package com.comicanything.reader.ui.home

import com.comicanything.reader.data.repository.DriveEntry
import com.comicanything.reader.data.repository.DriveSearchHit
import com.comicanything.reader.ui.reader.DriveBreadcrumb
import com.comicanything.reader.ui.reader.LocalBreadcrumb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeScreenSearchTest {

    @Test
    fun `localDisplayPath is null for an entry directly inside the search root`() {
        val path = localDisplayPath(rootPath = "/root", entryPath = "/root/book.cbz")

        assertNull(path)
    }

    @Test
    fun `localDisplayPath joins nested folder segments with a separator`() {
        val path = localDisplayPath(rootPath = "/root", entryPath = "/root/Comics/Manga/book.cbz")

        assertEquals("Comics / Manga", path)
    }

    @Test
    fun `localDisplayPath works for a matched folder entry too, using its parent`() {
        val path = localDisplayPath(rootPath = "/root", entryPath = "/root/Comics/Manga")

        assertEquals("Comics", path)
    }

    @Test
    fun `localBreadcrumbsForFolder returns the base unchanged when the folder is the search root`() {
        val base = listOf(LocalBreadcrumb("/root", "Internal Storage"))

        val result = localBreadcrumbsForFolder(base, rootPath = "/root", folderPath = "/root")

        assertEquals(base, result)
    }

    @Test
    fun `localBreadcrumbsForFolder appends one breadcrumb per path segment below the root`() {
        val base = listOf(LocalBreadcrumb("/root", "Internal Storage"))

        val result = localBreadcrumbsForFolder(base, rootPath = "/root", folderPath = "/root/Comics/Manga")

        assertEquals(
            listOf(
                LocalBreadcrumb("/root", "Internal Storage"),
                LocalBreadcrumb("/root/Comics", "Comics"),
                LocalBreadcrumb("/root/Comics/Manga", "Manga")
            ),
            result
        )
    }

    @Test
    fun `driveBreadcrumbsForHit appends the hit's parent path and the folder itself`() {
        val base = listOf(DriveBreadcrumb("root", "My Drive"))
        val hit = DriveSearchHit(
            entry = DriveEntry.Folder("f-manga", "Manga"),
            parentPath = listOf(DriveEntry.Folder("f-comics", "Comics"))
        )

        val result = driveBreadcrumbsForHit(base, hit, hit.entry as DriveEntry.Folder)

        assertEquals(
            listOf(
                DriveBreadcrumb("root", "My Drive"),
                DriveBreadcrumb("f-comics", "Comics"),
                DriveBreadcrumb("f-manga", "Manga")
            ),
            result
        )
    }
}
