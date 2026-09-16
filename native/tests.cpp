#include "storage.hpp"
#include <iostream>
#include <cstring>
#include <cstdlib>
#include <sys/stat.h>
#include <unistd.h>
#include <chrono>
using namespace nfssaf;
static int checks = 0;
void expect(bool condition, const char* message) { ++checks; if (!condition) throw std::runtime_error(message); }
template<class F> void error(int code, F action) { try { action(); } catch (const Error& e) { expect(e.code == code, e.what()); return; } throw std::runtime_error("Expected an error"); }
int main(int argc, char** argv) {
    try {
        for (auto path : {"/", "/hello world", "/日本/🙂", "/a/b"}) validate_path(path);
        for (auto path : {"", "relative", "//x", "/x/", "/../secret", "/a/./b", "/a//b"}) error(EINVAL, [&] { validate_path(path); });
        error(EINVAL, [] { validate_path(std::string("/a\0b", 4)); });
        error(EINVAL, [] { validate_path("/" + std::string(256, 'a')); });
        expect(transfer_all(100, [](size_t, size_t n) { return int(std::min(n, size_t(7))); }, false) == 100, "Short reads must be filled");
        expect(transfer_all(100, [](size_t d, size_t) { return d < 14 ? 7 : 0; }, false) == 14, "EOF");
        expect(transfer_all(100, [](size_t, size_t n) { return int(std::min(n, size_t(3))); }, true) == 100, "Short writes must be filled");
        error(EIO, [] { transfer_all(1, [](size_t, size_t) { return 0; }, true); });
        error(ENOSPC, [] { transfer_all(100, [](size_t d, size_t) { return d ? -ENOSPC : 1; }, true); });
        error(EACCES, [] { transfer_all(1, [](size_t, size_t) { return -EACCES; }, false); });
        expect(transfer_all(0, [](size_t, size_t) -> int { throw std::runtime_error("Zero IO called backend"); }, false) == 0, "Zero IO");
        if (argc > 1) {
            if (argc < 4) throw std::runtime_error("usage: storage_tests HOST EXPORT VERSION");
            Config c; c.host = argv[1]; c.export_path = argv[2]; c.version = std::stoi(argv[3]); c.uid = 1000; c.gid = 1000;
            Session s(c);
            const std::string dir = "/.nfssaf-test-" + std::to_string(getpid()) + "-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count());
            s.mkdir(dir);
            try {
                auto path = dir + "/日本🙂.bin";
                auto f = s.open(path, 3, true);
                std::vector<char> data(1024 * 1024); for (size_t i=0;i<data.size();++i) data[i] = char(i * 31);
                expect(s.write(f, data.data(), data.size(), 0) == data.size(), "Write");
                s.sync(f);
                if(argc>4) { std::cout << "Keeping an NFS file idle for " << argv[4] << " seconds" << std::endl; std::this_thread::sleep_for(std::chrono::seconds(std::stoi(argv[4]))); }
                std::vector<char> read(data.size());
                expect(s.read(f, read.data(), read.size(), 0) == data.size() && read == data, "Roundtrip hash");
                expect(s.read(f, read.data(), 20, data.size() - 7) == 7, "Short EOF");
                expect(s.read(f, read.data(), 20, data.size()) == 0, "EOF");
                expect(s.write(f, "XYZ", 3, 123) == 3, "Random write");
                expect(s.read(f, read.data(), 3, 123) == 3 && !memcmp(read.data(), "XYZ", 3), "Random read");
                const uint64_t large = (uint64_t(1) << 32) + 17;
                s.write(f, "Z", 1, large); s.sync(f);
                expect(s.fstat(f).size == large + 1, "64-bit sparse size");
                expect(s.read(f, read.data(), 1, large) == 1 && read[0]=='Z', "64-bit seek");
                s.close(f);
                error(EEXIST, [&] { s.open(path, 3, true); });
                auto listing = s.list(dir); expect(listing.size() == 1 && listing[0].size == large + 1, "Listing metadata");
                auto other = s.open(dir + "/other", 3, true); s.close(other);
                error(EEXIST, [&] { s.rename_file(path, dir + "/other"); });
                s.rename_file(path, dir + "/renamed");
                error(ENOENT, [&] { s.stat(path); });
                auto truncated = s.open(dir + "/renamed", 4); expect(s.fstat(truncated).size == 0, "Truncate"); s.close(truncated);
                error(ENOTEMPTY, [&] { s.remove(dir, true); });
                s.remove(dir + "/other", false); s.remove(dir + "/renamed", false);
                Config ro = c; ro.readonly = true; Session r(ro);
                error(EROFS, [&] { r.mkdir(dir + "/blocked"); });
                error(EROFS, [&] { r.open(dir + "/blocked", 3, true); });
                error(EPERM, [&] { s.remove("/", true); });
                s.remove(dir, true);
            } catch (...) { std::cerr << "Test artifacts retained at " << c.export_path << dir << '\n'; throw; }
        }
        std::cout << "PASS " << checks << " checks\n";
    } catch (const std::exception& e) { std::cerr << "FAIL: " << e.what() << '\n'; return 1; }
}
