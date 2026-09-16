#include "storage.hpp"
#include <jni.h>
#include <codecvt>
#include <locale>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cerrno>

using namespace nfssaf;
namespace {
struct NativeSession {
    Session storage;
    std::unordered_map<jlong, nfsfh*> files;
    jlong next = 1;
    std::vector<char> buffer;
    std::mutex mutex;
    explicit NativeSession(const Config& c) : storage(c) {}
    ~NativeSession() { for (auto& f : files) { try { storage.close(f.second); } catch (...) {} } }
    nfsfh* file(jlong id) { auto it=files.find(id); if (it==files.end()) throw Error(EBADF,"Closed file handle"); return it->second; }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeSession>> sessions;
std::atomic<jlong> next_session{1};
std::shared_ptr<NativeSession> session(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto it=sessions.find(id); if(it==sessions.end()) throw Error(EBADF,"Closed session"); return it->second;
}
std::string utf8(JNIEnv* env, jstring s) {
    if (!s) throw Error(EINVAL,"Missing string");
    const jchar* data = env->GetStringChars(s,nullptr);
    if (!data) throw std::bad_alloc();
    std::u16string text(reinterpret_cast<const char16_t*>(data), env->GetStringLength(s));
    env->ReleaseStringChars(s,data);
    try { return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(text); }
    catch (...) { throw Error(EILSEQ,"Invalid Unicode filename"); }
}
jstring string(JNIEnv* env, const std::string& s) {
    try {
        auto text=std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.from_bytes(s);
        return env->NewString(reinterpret_cast<const jchar*>(text.data()),text.size());
    } catch (...) { throw Error(EILSEQ,"Server filename is not valid UTF-8"); }
}
void fail(JNIEnv* env, const std::exception& ex) {
    if (env->ExceptionCheck()) return;
    auto error=dynamic_cast<const Error*>(&ex);
    auto cls=env->FindClass("dev/nfssaf/core/NfsException");
    auto ctor=env->GetMethodID(cls,"<init>","(ILjava/lang/String;)V");
    auto message=string(env,ex.what());
    auto throwable=env->NewObject(cls,ctor,error ? error->code : EIO,message);
    env->Throw(static_cast<jthrowable>(throwable));
}
jobject entry(JNIEnv* env,const Entry& e) {
    auto cls=env->FindClass("dev/nfssaf/core/Entry");
    auto ctor=env->GetMethodID(cls,"<init>","(Ljava/lang/String;JJIJJ)V");
    auto name=string(env,e.name);
    auto result=env->NewObject(cls,ctor,name,jlong(e.inode),jlong(e.device),jint(e.mode),jlong(e.size),jlong(e.modified_ms));
    env->DeleteLocalRef(name); env->DeleteLocalRef(cls); return result;
}
}
#define JNI_METHOD(type,name) extern "C" JNIEXPORT type JNICALL Java_dev_nfssaf_NativeBridge_##name
#define BEGIN(id) try { auto s=session(id); std::lock_guard<std::mutex> lock(s->mutex);
#define END(ret) } catch(const std::exception& e) { fail(env,e); return ret; }
JNI_METHOD(jlong,connect)(JNIEnv* env,jobject,jstring host,jstring path,jint version,jint port,jlong uid,jlong gid,jlongArray groups,jint timeout,jboolean ro) {
    try {
        Config c; c.host=utf8(env,host); c.export_path=utf8(env,path); c.version=version; c.port=port; c.uid=uid; c.gid=gid; c.timeout_ms=timeout; c.readonly=ro;
        jsize size=env->GetArrayLength(groups); if (size>16) throw Error(EINVAL,"Too many groups");
        jlong values[16]{}; env->GetLongArrayRegion(groups,0,size,values);
        for(int i=0;i<size;++i) c.groups.push_back(values[i]);
        auto s=std::make_shared<NativeSession>(c); auto id=next_session++;
        std::lock_guard<std::mutex> lock(registry_mutex); sessions.emplace(id,std::move(s)); return id;
    END(0)
}
JNI_METHOD(void,disconnect)(JNIEnv* env,jobject,jlong id) {
    try { std::shared_ptr<NativeSession> old; { std::lock_guard<std::mutex> lock(registry_mutex); auto it=sessions.find(id); if(it!=sessions.end()) { old=std::move(it->second); sessions.erase(it); } }
    END()
}
JNI_METHOD(jobject,stat)(JNIEnv* env,jobject,jlong id,jstring path) {
    BEGIN(id) auto p=utf8(env,path); s->storage.safe_path(p); return entry(env,s->storage.stat(p)); END(nullptr)
}
JNI_METHOD(jobjectArray,list)(JNIEnv* env,jobject,jlong id,jstring path) {
    BEGIN(id)
    auto entries=s->storage.list(utf8(env,path));
    auto cls=env->FindClass("dev/nfssaf/core/Entry"); auto out=env->NewObjectArray(entries.size(),cls,nullptr);
    for(size_t i=0;i<entries.size();++i) { auto e=entry(env,entries[i]); env->SetObjectArrayElement(out,i,e); env->DeleteLocalRef(e); if(env->ExceptionCheck()) return nullptr; }
    return out; END(nullptr)
}
JNI_METHOD(jlong,open)(JNIEnv* env,jobject,jlong id,jstring path,jint mode,jboolean exclusive) {
    BEGIN(id) auto f=s->storage.open(utf8(env,path),mode,exclusive); auto key=s->next++; s->files.emplace(key,f); return key; END(0)
}
JNI_METHOD(jobject,fstat)(JNIEnv* env,jobject,jlong id,jlong file) { BEGIN(id) return entry(env,s->storage.fstat(s->file(file))); END(nullptr) }
JNI_METHOD(jint,read)(JNIEnv* env,jobject,jlong id,jlong file,jlong offset,jint size,jbyteArray data) {
    BEGIN(id)
    if(offset<0 || size<0 || size>env->GetArrayLength(data) || size>1024*1024) throw Error(EINVAL,"Invalid read range");
    s->buffer.resize(size); auto count=s->storage.read(s->file(file),s->buffer.data(),size,offset);
    env->SetByteArrayRegion(data,0,count,reinterpret_cast<const jbyte*>(s->buffer.data())); return count; END(0)
}
JNI_METHOD(jint,write)(JNIEnv* env,jobject,jlong id,jlong file,jlong offset,jint size,jbyteArray data) {
    BEGIN(id)
    if(offset<0 || size<0 || size>env->GetArrayLength(data) || size>1024*1024) throw Error(EINVAL,"Invalid write range");
    s->buffer.resize(size); env->GetByteArrayRegion(data,0,size,reinterpret_cast<jbyte*>(s->buffer.data()));
    if(env->ExceptionCheck()) return 0;
    return s->storage.write(s->file(file),s->buffer.data(),size,offset); END(0)
}
JNI_METHOD(void,sync)(JNIEnv* env,jobject,jlong id,jlong file) { BEGIN(id) s->storage.sync(s->file(file)); END() }
JNI_METHOD(void,close)(JNIEnv* env,jobject,jlong id,jlong file) {
    BEGIN(id) auto f=s->file(file); s->files.erase(file); s->storage.close(f); END()
}
JNI_METHOD(void,mkdir)(JNIEnv* env,jobject,jlong id,jstring path) { BEGIN(id) s->storage.mkdir(utf8(env,path)); END() }
JNI_METHOD(void,remove)(JNIEnv* env,jobject,jlong id,jstring path,jboolean directory) { BEGIN(id) s->storage.remove(utf8(env,path),directory); END() }
JNI_METHOD(void,rename)(JNIEnv* env,jobject,jlong id,jstring from,jstring to) { BEGIN(id) s->storage.rename_file(utf8(env,from),utf8(env,to)); END() }
