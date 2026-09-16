#pragma once
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <nfsc/libnfs.h>
#include <optional>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace nfssaf {
struct Error : std::runtime_error {
    int code;
    Error(int code, const std::string &message) : std::runtime_error(message), code(code) {}
};
struct Config {
    std::string host, export_path;
    int version = 42, port = 2049, timeout_ms = 10000;
    uint32_t uid = 65534, gid = 65534;
    std::vector<uint32_t> groups;
    bool readonly = false;
};
struct Identity {
    uint64_t inode, device;
};
struct Entry {
    std::string name;
    uint64_t inode, device;
    uint32_t mode;
    uint64_t size;
    int64_t modified_ms;
};
void validate_path(const std::string &path);
size_t transfer_all(size_t size, const std::function<int(size_t, size_t)> &transfer, bool write);
class Session {
    nfs_context *ctx = nullptr;
    bool readonly;
    std::recursive_mutex mutex;
    std::thread pump;
    std::mutex stop_mutex;
    std::condition_variable stop_signal;
    bool stopping = false;
    int renewal_failed = 0;
    void check(int result, const char *operation);
    void writable();
    void healthy();

  public:
    explicit Session(const Config &config);
    ~Session();
    Session(const Session &) = delete;
    Session &operator=(const Session &) = delete;
    Entry stat(const std::string &path);
    void safe_path(const std::string &path, bool allow_missing_leaf = false);
    std::vector<Entry> list(const std::string &path);
    nfsfh *open(const std::string &path, int mode, bool exclusive = false,
                std::optional<Identity> expected = std::nullopt);
    Entry fstat(nfsfh *file);
    size_t read(nfsfh *file, void *buffer, size_t size, uint64_t offset);
    size_t write(nfsfh *file, const void *buffer, size_t size, uint64_t offset);
    void sync(nfsfh *file);
    void close(nfsfh *file);
    void mkdir(const std::string &path);
    void remove(const std::string &path, bool directory);
    void rename_file(const std::string &from, const std::string &to);
};
} // namespace nfssaf
