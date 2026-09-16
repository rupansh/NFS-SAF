package dev.nfssaf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nfssaf.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val share=ShareId.random()
    private val catalog=Catalog(context)
    private fun entry(name: String, inode: Long=1, mode: Int=0x8000) = Entry(name,inode,10,mode,20,1234)
    @After fun cleanup() { catalog.forget(share,RemotePath.Root); catalog.close() }
    @Test fun idsSurviveReopenAndRename() {
        val a=catalog.register(share,RemotePath("/a"),entry("a")) as Node.File
        val b=catalog.register(share,RemotePath("/a"),entry("a")); assertEquals(a.id,b.id)
        Catalog(context).use { reopened -> assertEquals(a.id,reopened.find(a.id).id) }
        catalog.rename(a,RemotePath("/renamed")); assertEquals(RemotePath("/renamed"),catalog.find(a.id).path)
    }
    @Test fun replacementsInvalidateOldIds() {
        val a=catalog.register(share,RemotePath("/a"),entry("a",1))
        val b=catalog.register(share,RemotePath("/a"),entry("a",2)); assertNotEquals(a.id,b.id)
        try { catalog.find(a.id); fail("Old ID should be gone") } catch(_: NfsException) {}
    }
    @Test fun invalidationUsesComponentsIncludingUnicodeAndSqlWildcards() {
        val paths=listOf("/日本🙂%_", "/日本🙂%_/child", "/日本🙂%_other", "/ab")
        val nodes=paths.mapIndexed { i,p -> catalog.register(share,RemotePath(p),entry(p,i.toLong())) }
        val removed=catalog.forget(share,RemotePath(paths[0])); assertEquals(setOf(nodes[0].id,nodes[1].id),removed.toSet())
        assertEquals(nodes[2].id,catalog.find(nodes[2].id).id)
    }
    @Test fun replacingDirectoryInvalidatesDescendantIds() {
        val parent=catalog.register(share,RemotePath("/parent"),entry("parent",1,0x4000))
        val child=catalog.register(share,RemotePath("/parent/child"),entry("child",2))
        val replacement=catalog.register(share,RemotePath("/parent"),entry("parent",3,0x4000)); assertNotEquals(parent.id,replacement.id)
        try { catalog.find(child.id); fail("Descendant ID survived replaced parent") } catch(_: NfsException) {}
    }
}
