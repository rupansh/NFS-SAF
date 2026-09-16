#include "storage.hpp"
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <limits>
#include <algorithm>
#include <poll.h>
#include <chrono>
extern "C" int nfssaf_renew_v4(nfs_context*, int*);

namespace nfssaf {
#define LOCK_SESSION std::lock_guard<std::recursive_mutex> guard(mutex)

void validate_path(const std::string& path) {
    if (path.empty() || path[0] != '/' || path.size() > 4096 || path.find('\0') != std::string::npos) throw Error(EINVAL, "Invalid absolute path");
    if (path == "/") return;
    size_t start = 1;
    while (start <= path.size()) {
        auto end = path.find('/', start);
        auto part = path.substr(start, end == std::string::npos ? end : end - start);
        if (part.empty() || part == "." || part == ".." || part.size() > 255) throw Error(EINVAL, "Invalid path component");
        if (end == std::string::npos) break;
        start = end + 1;
    }
}
size_t transfer_all(size_t size, const std::function<int(size_t, size_t)>& transfer, bool write) {
    size_t done = 0;
    while (done < size) {
        int count = transfer(done, std::min(size - done, size_t(1024 * 1024)));
        if (count < 0) throw Error(-count, write ? "NFS write failed" : "NFS read failed");
        if (!count) { if (write) throw Error(EIO, "NFS write made no progress"); break; }
        if (size_t(count) > size - done) throw Error(EIO, "Invalid NFS transfer length");
        done += count;
    }
    return done;
}
void Session::check(int result, const char* op) {
    if (result < 0) throw Error(-result, std::string(op) + ": " + nfs_get_error(ctx));
}
void Session::writable() { if (readonly) throw Error(EROFS, "Share is read-only"); }
Session::Session(const Config& c) : readonly(c.readonly) {
    validate_path(c.export_path);
    if (c.host.empty() || c.host.find('\0') != std::string::npos || c.port < 1 || c.port > 65535 || c.groups.size() > 16 || c.timeout_ms < 1000) throw Error(EINVAL, "Invalid connection settings");
    ctx = nfs_init_context();
    if (!ctx) throw Error(ENOMEM, "Cannot allocate NFS context");
    try {
        check(nfs_set_version(ctx, c.version), "NFS version");
        nfs_set_uid(ctx, static_cast<int>(c.uid));
        nfs_set_gid(ctx, static_cast<int>(c.gid));
        auto groups = c.groups;
        nfs_set_auxiliary_gids(ctx, groups.size(), groups.data());
        nfs_set_nfsport(ctx, c.port);
        nfs_set_timeout(ctx, c.timeout_ms);
        nfs_set_poll_timeout(ctx, 100);
        nfs_set_autoreconnect(ctx, 0);
        nfs_set_retrans(ctx, 0);
        nfs_set_dircache(ctx, 0);
        nfs_set_auto_traverse_mounts(ctx, 0);
        nfs_set_readonly(ctx, c.readonly);
        nfs_set_readmax(ctx, 1024 * 1024);
        nfs_set_writemax(ctx, 1024 * 1024);
        check(nfs_mount(ctx, c.host.c_str(), c.export_path.c_str()), "Mount export");
        pump = std::thread([this, version=c.version] {
            auto renewed=std::chrono::steady_clock::now();
            std::unique_lock<std::mutex> stop_lock(stop_mutex);
            while (!stop_signal.wait_for(stop_lock, std::chrono::milliseconds(250), [this] { return stopping; })) {
                stop_lock.unlock();
                {
                    LOCK_SESSION;
                    pollfd fd{nfs_get_fd(ctx), static_cast<short>(nfs_which_events(ctx)), 0};
                    int ready=poll(&fd,1,0);
                    if(ready>=0) nfs_service(ctx, ready ? fd.revents : 0);
                    // 4.2's SEQUENCE keepalive is driven by nfs_service. 4.0 needs RENEW.
                    if(version==4 && std::chrono::steady_clock::now()-renewed>std::chrono::seconds(20)) {
                        if(nfssaf_renew_v4(ctx,&renewal_failed)<0) renewal_failed=1;
                        renewed=std::chrono::steady_clock::now();
                    }
                }
                stop_lock.lock();
            }
        });
    } catch (...) { nfs_destroy_context(ctx); ctx = nullptr; throw; }
}
Session::~Session() {
    { std::lock_guard<std::mutex> lock(stop_mutex); stopping=true; }
    stop_signal.notify_all();
    if(pump.joinable()) pump.join();
    if(ctx) { nfs_umount(ctx); nfs_destroy_context(ctx); }
}
static Entry entry(const nfs_stat_64& s, const std::string& name) {
    return {name, s.nfs_ino, s.nfs_dev, uint32_t(s.nfs_mode), s.nfs_size, int64_t(s.nfs_mtime * 1000 + s.nfs_mtime_nsec / 1000000)};
}
Entry Session::stat(const std::string& path) { LOCK_SESSION;
    validate_path(path);
    nfs_stat_64 s{};
    check(nfs_lstat64(ctx, path.c_str(), &s), "Stat");
    return entry(s, path.substr(path.find_last_of('/') + 1));
}
void Session::safe_path(const std::string& path, bool missing) { LOCK_SESSION;
    validate_path(path);
    size_t end = 1;
    do {
        end = path.find('/', end);
        auto part = path.substr(0, end);
        try {
            auto s = stat(part);
            if (S_ISLNK(s.mode)) throw Error(ELOOP, "Symbolic links are not exposed through SAF");
            if (end != std::string::npos && !S_ISDIR(s.mode)) throw Error(ENOTDIR, "Parent is not a directory");
        } catch (const Error& e) { if (!(missing && end == std::string::npos && e.code == ENOENT)) throw; }
        if (end != std::string::npos) ++end;
    } while (end != std::string::npos);
}
std::vector<Entry> Session::list(const std::string& path) { LOCK_SESSION;
    safe_path(path);
    nfsdir* dir = nullptr;
    check(nfs_opendir(ctx, path.c_str(), &dir), "List directory");
    std::vector<Entry> entries;
    try {
        while (auto* e = nfs_readdir(ctx, dir)) {
            if (!strcmp(e->name, ".") || !strcmp(e->name, "..")) continue;
            if (!S_ISREG(e->mode) && !S_ISDIR(e->mode)) continue;
            entries.push_back({e->name, e->inode, e->dev, e->mode, e->size, int64_t(e->mtime.tv_sec) * 1000 + e->mtime_nsec / 1000000});
        }
    } catch (...) { nfs_closedir(ctx, dir); throw; }
    nfs_closedir(ctx, dir);
    return entries;
}
nfsfh* Session::open(const std::string& path, int mode, bool exclusive) { LOCK_SESSION;
    // Modes are app-defined, never Android/Linux numeric flag values across JNI.
    if (mode < 0 || mode > 4) throw Error(EINVAL, "Invalid open mode");
    if (mode != 0 || exclusive) writable();
    safe_path(path, exclusive);
    int flags = mode == 0 ? O_RDONLY : (mode == 1 || mode == 2 ? O_WRONLY : O_RDWR);
    if (mode == 1 || mode == 4) flags |= O_TRUNC;
    if (exclusive) flags |= O_CREAT | O_EXCL;
    flags |= O_NOFOLLOW;
    nfsfh* file = nullptr;
    check(nfs_open2(ctx, path.c_str(), flags, 0660, &file), "Open file");
    try { if (!S_ISREG(fstat(file).mode)) throw Error(EISDIR, "Not a regular file"); }
    catch (...) { nfs_close(ctx, file); throw; }
    return file;
}
Entry Session::fstat(nfsfh* file) { LOCK_SESSION; nfs_stat_64 s{}; check(nfs_fstat64(ctx, file, &s), "File stat"); return entry(s, ""); }
size_t Session::read(nfsfh* file, void* buffer, size_t size, uint64_t offset) { LOCK_SESSION;
    if (offset > uint64_t(INT64_MAX) || size > uint64_t(INT64_MAX) - offset) throw Error(EINVAL, "Offset overflow");
    return transfer_all(size, [&](size_t done, size_t count) { return nfs_pread(ctx, file, static_cast<char*>(buffer) + done, count, offset + done); }, false);
}
size_t Session::write(nfsfh* file, const void* buffer, size_t size, uint64_t offset) { LOCK_SESSION;
    writable();
    if (offset > uint64_t(INT64_MAX) || size > uint64_t(INT64_MAX) - offset) throw Error(EINVAL, "Offset overflow");
    return transfer_all(size, [&](size_t done, size_t count) { return nfs_pwrite(ctx, file, static_cast<const char*>(buffer) + done, count, offset + done); }, true);
}
void Session::sync(nfsfh* file) { LOCK_SESSION; check(nfs_fsync(ctx, file), "Sync file"); }
void Session::close(nfsfh* file) { LOCK_SESSION; check(nfs_close(ctx, file), "Close file"); }
void Session::mkdir(const std::string& path) { LOCK_SESSION; writable(); safe_path(path, true); check(nfs_mkdir2(ctx, path.c_str(), 0770), "Create directory"); }
void Session::remove(const std::string& path, bool directory) { LOCK_SESSION;
    writable(); if (path == "/") throw Error(EPERM, "Cannot delete export root");
    safe_path(path); check(directory ? nfs_rmdir(ctx, path.c_str()) : nfs_unlink(ctx, path.c_str()), "Delete");
}
void Session::rename_file(const std::string& from, const std::string& to) { LOCK_SESSION;
    writable(); safe_path(from); safe_path(to, true);
    if (!S_ISREG(stat(from).mode)) throw Error(ENOTSUP, "Only regular files can be renamed safely");
    // LINK is exclusive on NFS, unlike RENAME, which silently replaces the destination.
    check(nfs_link(ctx, from.c_str(), to.c_str()), "Create new file name");
    int result = nfs_unlink(ctx, from.c_str());
    if (result < 0) { nfs_unlink(ctx, to.c_str()); check(result, "Remove old file name"); }
}
}
