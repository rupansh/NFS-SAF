package dev.nfssaf

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.DocumentsContract
import dev.nfssaf.core.*
import org.json.JSONArray
import org.json.JSONObject

internal class ShareStore(private val context: Context) {
    private val prefs=context.getSharedPreferences("shares",Context.MODE_PRIVATE)
    @Synchronized fun all(): List<Share> {
        val array=JSONArray(prefs.getString("shares","[]"))
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            Share(ShareId(o.getString("id")),o.getString("name"),o.getString("host"),RemotePath(o.getString("export")),Protocol.valueOf(o.getString("protocol")),o.getInt("port"),o.getLong("uid"),o.getLong("gid"),o.getJSONArray("groups").let { g -> (0 until g.length()).map { g.getLong(it) } },o.getBoolean("readOnly"),o.getInt("timeout"),ReadPolicy.valueOf(o.optString("readPolicy",ReadPolicy.ReadAhead.name))).validate()
        } }
    }
    fun get(id: ShareId): Share = all().firstOrNull { it.id == id } ?: throw NfsException(2,"Share was removed")
    @Synchronized fun save(share: Share) {
        replace(null,share)
    }
    @Synchronized fun replace(previous: ShareId?, share: Share) {
        share.validate()
        write(all().filterNot { it.id==share.id || it.id==previous } + share)
    }
    @Synchronized fun remove(id: ShareId) { write(all().filterNot { it.id==id }) }
    private fun write(shares: List<Share>) {
        val array=JSONArray()
        shares.forEach { s -> array.put(JSONObject().put("id",s.id.value).put("name",s.name).put("host",s.host).put("export",s.export.value).put("protocol",s.protocol.name).put("port",s.port).put("uid",s.uid).put("gid",s.gid).put("groups",JSONArray(s.groups)).put("readOnly",s.readOnly).put("timeout",s.timeoutSeconds).put("readPolicy",s.readPolicy.name)) }
        check(prefs.edit().putString("shares",array.toString()).commit()) { "Could not save settings" }
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY),null)
    }
}
internal const val AUTHORITY="dev.nfssaf.documents"

internal class Catalog(context: Context) : SQLiteOpenHelper(context,"documents.db",null,1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, share TEXT NOT NULL, path TEXT NOT NULL, inode INTEGER NOT NULL, device INTEGER NOT NULL, mode INTEGER NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, UNIQUE(share,path))")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = error("Unsupported catalog version")
    @Synchronized fun find(id: DocumentId): Node {
        readableDatabase.query("documents",null,"id=?",arrayOf(id.value),null,null,null).use { c ->
            if(!c.moveToFirst()) throw NfsException(2,"Document no longer exists")
            val path=RemotePath(c.getString(c.getColumnIndexOrThrow("path")))
            val e=Entry(path.value.substringAfterLast('/'),c.getLong(c.getColumnIndexOrThrow("inode")),c.getLong(c.getColumnIndexOrThrow("device")),c.getInt(c.getColumnIndexOrThrow("mode")),c.getLong(c.getColumnIndexOrThrow("size")),c.getLong(c.getColumnIndexOrThrow("modified")))
            return node(id,ShareId(c.getString(c.getColumnIndexOrThrow("share"))),path,e)
        }
    }
    @Synchronized fun register(share: ShareId, path: RemotePath, entry: Entry): Node {
        require(entry.regular || entry.directory)
        var id: DocumentId?=null
        readableDatabase.query("documents",arrayOf("id","inode","device"),"share=? AND path=?",arrayOf(share.value,path.value),null,null,null).use { c ->
            if(c.moveToFirst()) {
                if(c.getLong(1)==entry.inode && c.getLong(2)==entry.device) id=DocumentId(c.getString(0))
                else forget(share,path)
            }
        }
        val result=id ?: if(path==RemotePath.Root) DocumentId(share.value) else DocumentId.random()
        val values=ContentValues().apply { put("id",result.value); put("share",share.value); put("path",path.value); put("inode",entry.inode); put("device",entry.device); put("mode",entry.mode); put("size",entry.size); put("modified",entry.modifiedMillis) }
        writableDatabase.insertWithOnConflict("documents",null,values,SQLiteDatabase.CONFLICT_REPLACE)
        return node(result,share,path,entry)
    }
    @Synchronized fun registerChildren(share: ShareId, parent: RemotePath, entries: List<Entry>): List<Node> {
        val db=writableDatabase; db.beginTransaction()
        try { val result=entries.map { register(share,parent.child(FileName(it.name)),it) }; db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    @Synchronized fun pathId(share: ShareId, path: RemotePath): DocumentId? {
        if(path==RemotePath.Root) return DocumentId(share.value)
        return readableDatabase.query("documents",arrayOf("id"),"share=? AND path=?",arrayOf(share.value,path.value),null,null,null).use { c -> if(c.moveToFirst()) DocumentId(c.getString(0)) else null }
    }
    @Synchronized fun rename(file: Node.File, target: RemotePath) {
        writableDatabase.update("documents",ContentValues().apply { put("path",target.value) },"id=?",arrayOf(file.id.value))
    }
    @Synchronized fun forget(share: ShareId, path: RemotePath): List<DocumentId> {
        val where=if(path==RemotePath.Root) "share=?" else "share=? AND (path=? OR substr(path,1,length(?))=?)"
        val args=if(path==RemotePath.Root) arrayOf(share.value) else arrayOf(share.value,path.value,path.value+"/",path.value+"/")
        val ids=readableDatabase.query("documents",arrayOf("id"),where,args,null,null,null).use { c -> buildList { while(c.moveToNext()) add(DocumentId(c.getString(0))) } }
        writableDatabase.delete("documents",where,args); return ids
    }
    private fun node(id: DocumentId, share: ShareId, path: RemotePath, e: Entry): Node = when {
        e.directory -> Node.Directory(id,share,path,e)
        e.regular -> Node.File(id,share,path,e)
        else -> throw NfsException(95,"Unsupported file type")
    }
}
internal class Services private constructor(context: Context) {
    val shares=ShareStore(context.applicationContext)
    val catalog=Catalog(context.applicationContext)
    val backend=NfsBackend()
    val errors=context.getSharedPreferences("errors",Context.MODE_PRIVATE)
    // Fixed stripes bound lock memory while serializing same-document mutations/appends.
    private val locks=Array(64) { Any() }
    fun fileLock(id: DocumentId): Any = locks[(id.hashCode() and Int.MAX_VALUE)%locks.size]
    fun lock(share: ShareId): Any = locks[(share.hashCode() and Int.MAX_VALUE)%locks.size]
    companion object {
        @Volatile private var instance: Services?=null
        fun get(context: Context): Services = instance ?: synchronized(this) { instance ?: Services(context).also { instance=it } }
    }
}
