#include "storage.hpp"
#include <jni.h>
#include <codecvt>
#include <locale>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cerrno>
#include <fcntl.h>

using namespace nfssaf;
namespace {
struct NativeSession {
    Session storage;
    std::unordered_map<jlong, nfsfh *> files;
    jlong next = 1;
    std::vector<char> buffer;
    std::mutex mutex;
    explicit NativeSession(const Config &c) : storage(c) {}
    ~NativeSession() {
        for (auto &f : files) {
            try {
                storage.close(f.second);
            } catch (...) {
            }
        }
    }
    nfsfh *file(jlong id) {
        auto it = files.find(id);
        if (it == files.end())
            throw Error(EBADF, "Closed file handle");
        return it->second;
    }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeSession>> sessions;
std::atomic<jlong> next_session{1};
std::shared_ptr<NativeSession> session(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto it = sessions.find(id);
    if (it == sessions.end())
        throw Error(EBADF, "Closed session");
    return it->second;
}
std::string utf8(JNIEnv *env, jstring s) {
    if (!s)
        throw Error(EINVAL, "Missing string");
    const jchar *data = env->GetStringChars(s, nullptr);
    if (!data)
        throw std::bad_alloc();
    std::u16string text(reinterpret_cast<const char16_t *>(data), env->GetStringLength(s));
    env->ReleaseStringChars(s, data);
    try {
        return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.to_bytes(text);
    } catch (...) {
        throw Error(EILSEQ, "Invalid Unicode filename");
    }
}
jstring string(JNIEnv *env, const std::string &s) {
    try {
        auto text =
            std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(s);
        return env->NewString(reinterpret_cast<const jchar *>(text.data()), text.size());
    } catch (...) {
        throw Error(EILSEQ, "Server filename is not valid UTF-8");
    }
}
void fail(JNIEnv *env, const std::exception &ex) {
    if (env->ExceptionCheck())
        return;
    auto error = dynamic_cast<const Error *>(&ex);
    auto cls = env->FindClass("dev/nfssaf/core/NfsException");
    auto ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    auto message = string(env, ex.what());
    auto throwable = env->NewObject(cls, ctor, error ? error->code : EIO, message);
    env->Throw(static_cast<jthrowable>(throwable));
}
jobject entry(JNIEnv *env, const Entry &e) {
    auto cls = env->FindClass("dev/nfssaf/core/Entry");
    auto ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;JJIJJ)V");
    auto name = string(env, e.name);
    auto result = env->NewObject(cls, ctor, name, jlong(e.inode), jlong(e.device), jint(e.mode),
                                 jlong(e.size), jlong(e.modified_ms));
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(cls);
    return result;
}
} // namespace
extern "C" JNIEXPORT void JNICALL Java_dev_nfssaf_NativeBridge_directProxy(JNIEnv *env, jobject,
                                                                           jint fd) {
    try {
        int flags = fcntl(fd, F_GETFL);
        if (flags < 0 || fcntl(fd, F_SETFL, flags | O_DIRECT) < 0)
            throw Error(errno, "Cannot enable coherent proxy IO");
    } catch (const std::exception &e) {
        fail(env, e);
        return;
    }
}
extern "C" JNIEXPORT jint JNICALL Java_dev_nfssaf_NativeBridge_liveSessions(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    return sessions.size();
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_nfssaf_NativeBridge_connect(
    JNIEnv *env, jobject, jstring host, jstring path, jint version, jint port, jlong uid, jlong gid,
    jlongArray groups, jint timeout, jboolean ro) {
    try {
        Config c;
        c.host = utf8(env, host);
        c.export_path = utf8(env, path);
        c.version = version;
        c.port = port;
        c.uid = uid;
        c.gid = gid;
        c.timeout_ms = timeout;
        c.readonly = ro;
        jsize size = env->GetArrayLength(groups);
        if (size > 16)
            throw Error(EINVAL, "Too many groups");
        jlong values[16]{};
        env->GetLongArrayRegion(groups, 0, size, values);
        for (int i = 0; i < size; ++i)
            c.groups.push_back(values[i]);
        auto s = std::make_shared<NativeSession>(c);
        auto id = next_session++;
        std::lock_guard<std::mutex> lock(registry_mutex);
        sessions.emplace(id, std::move(s));
        return id;
    } catch (const std::exception &e) {
        fail(env, e);
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL Java_dev_nfssaf_NativeBridge_disconnect(JNIEnv *env, jobject,
                                                                          jlong id) {
    try {
        std::shared_ptr<NativeSession> old;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            auto it = sessions.find(id);
            if (it != sessions.end()) {
                old = std::move(it->second);
                sessions.erase(it);
            }
        }
    } catch (const std::exception &e) {
        fail(env, e);
        return;
    }
}
extern "C" JNIEXPORT jobject JNICALL Java_dev_nfssaf_NativeBridge_stat(JNIEnv *env, jobject,
                                                                       jlong id, jstring path) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        auto p = utf8(env, path);
        s->storage.safe_path(p);
        return entry(env, s->storage.stat(p));
    } catch (const std::exception &e) {
        fail(env, e);
        return nullptr;
    }
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_nfssaf_NativeBridge_list(JNIEnv *env, jobject,
                                                                            jlong id,
                                                                            jstring path) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        auto entries = s->storage.list(utf8(env, path));
        auto cls = env->FindClass("dev/nfssaf/core/Entry");
        auto out = env->NewObjectArray(entries.size(), cls, nullptr);
        for (size_t i = 0; i < entries.size(); ++i) {
            auto e = entry(env, entries[i]);
            env->SetObjectArrayElement(out, i, e);
            env->DeleteLocalRef(e);
            if (env->ExceptionCheck())
                return nullptr;
        }
        return out;
    } catch (const std::exception &e) {
        fail(env, e);
        return nullptr;
    }
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_nfssaf_NativeBridge_open(JNIEnv *env, jobject, jlong id,
                                                                     jstring path, jint mode,
                                                                     jboolean exclusive,
                                                                     jlong inode, jlong device) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        auto f = s->storage.open(
            utf8(env, path), mode, exclusive,
            exclusive ? std::nullopt
                      : std::optional<Identity>({uint64_t(inode), uint64_t(device)}));
        auto key = s->next++;
        s->files.emplace(key, f);
        return key;
    } catch (const std::exception &e) {
        fail(env, e);
        return 0;
    }
}
extern "C" JNIEXPORT jobject JNICALL Java_dev_nfssaf_NativeBridge_fstat(JNIEnv *env, jobject,
                                                                        jlong id, jlong file) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        return entry(env, s->storage.fstat(s->file(file)));
    } catch (const std::exception &e) {
        fail(env, e);
        return nullptr;
    }
}
extern "C" JNIEXPORT jint JNICALL Java_dev_nfssaf_NativeBridge_read(JNIEnv *env, jobject, jlong id,
                                                                    jlong file, jlong offset,
                                                                    jint size, jbyteArray data) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        if (offset < 0 || size < 0 || size > env->GetArrayLength(data) || size > 1024 * 1024)
            throw Error(EINVAL, "Invalid read range");
        s->buffer.resize(size);
        auto count = s->storage.read(s->file(file), s->buffer.data(), size, offset);
        env->SetByteArrayRegion(data, 0, count, reinterpret_cast<const jbyte *>(s->buffer.data()));
        return count;
    } catch (const std::exception &e) {
        fail(env, e);
        return 0;
    }
}
extern "C" JNIEXPORT jint JNICALL Java_dev_nfssaf_NativeBridge_write(JNIEnv *env, jobject, jlong id,
                                                                     jlong file, jlong offset,
                                                                     jint size, jbyteArray data) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        if (offset < 0 || size < 0 || size > env->GetArrayLength(data) || size > 1024 * 1024)
            throw Error(EINVAL, "Invalid write range");
        s->buffer.resize(size);
        env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(s->buffer.data()));
        if (env->ExceptionCheck())
            return 0;
        return s->storage.write(s->file(file), s->buffer.data(), size, offset);
    } catch (const std::exception &e) {
        fail(env, e);
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL Java_dev_nfssaf_NativeBridge_sync(JNIEnv *env, jobject, jlong id,
                                                                    jlong file) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        s->storage.sync(s->file(file));
    } catch (const std::exception &e) {
        fail(env, e);
        return;
    }
}
extern "C" JNIEXPORT void JNICALL Java_dev_nfssaf_NativeBridge_close(JNIEnv *env, jobject, jlong id,
                                                                     jlong file) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        auto f = s->file(file);
        s->files.erase(file);
        s->storage.close(f);
    } catch (const std::exception &e) {
        fail(env, e);
        return;
    }
}
extern "C" JNIEXPORT void JNICALL Java_dev_nfssaf_NativeBridge_mkdir(JNIEnv *env, jobject, jlong id,
                                                                     jstring path) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        s->storage.mkdir(utf8(env, path));
    } catch (const std::exception &e) {
        fail(env, e);
        return;
    }
}
extern "C" JNIEXPORT void JNICALL Java_dev_nfssaf_NativeBridge_remove(JNIEnv *env, jobject,
                                                                      jlong id, jstring path,
                                                                      jboolean directory) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        s->storage.remove(utf8(env, path), directory);
    } catch (const std::exception &e) {
        fail(env, e);
        return;
    }
}
extern "C" JNIEXPORT void JNICALL Java_dev_nfssaf_NativeBridge_rename(JNIEnv *env, jobject,
                                                                      jlong id, jstring from,
                                                                      jstring to) {
    try {
        auto s = session(id);
        std::lock_guard<std::mutex> lock(s->mutex);
        s->storage.rename_file(utf8(env, from), utf8(env, to));
    } catch (const std::exception &e) {
        fail(env, e);
        return;
    }
}
