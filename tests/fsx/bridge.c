/* Test-only POSIX-to-stdio transport. The upstream fsx oracle is unchanged. */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define TEST_FD 0x123456
static int protocol_out;
static void exact(int fd, void *buffer, size_t size, int writing) {
    char *p = buffer;
    while (size) {
        ssize_t n = writing ? write(fd, p, size) : read(fd, p, size);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            fprintf(stderr, "fsx bridge disconnected\n");
            exit(120);
        }
        p += n;
        size -= n;
    }
}
static void put64(int64_t value) {
    unsigned char data[8];
    for (int i = 0; i < 8; i++)
        data[7 - i] = (uint64_t)value >> (8 * i);
    exact(protocol_out, data, 8, 1);
}
static int64_t get64(void) {
    unsigned char data[8];
    uint64_t value = 0;
    exact(STDIN_FILENO, data, 8, 0);
    for (int i = 0; i < 8; i++)
        value = (value << 8) | data[i];
    return (int64_t)value;
}
static void request(int op, int64_t a, int64_t b) {
    put64(op);
    put64(a);
    put64(b);
}
static int64_t response(void) {
    int64_t result = get64();
    if (result < 0) {
        errno = (int)-result;
        return -1;
    }
    return result;
}
static int bridge_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (strcmp(path, "nfssaf-target"))
        return open(path, flags, mode);
    request(1, flags, 0);
    return response() < 0 ? -1 : TEST_FD;
}
static int bridge_close(int fd) {
    if (fd != TEST_FD)
        return close(fd);
    request(2, 0, 0);
    return response();
}
static off_t bridge_lseek(int fd, off_t offset, int whence) {
    if (fd != TEST_FD)
        return lseek(fd, offset, whence);
    request(3, offset, whence);
    return response();
}
static ssize_t bridge_read(int fd, void *buffer, size_t size) {
    if (fd != TEST_FD)
        return read(fd, buffer, size);
    request(4, size, 0);
    int64_t result = response();
    if (result > 0) {
        if ((uint64_t)result > size)
            exit(121);
        exact(STDIN_FILENO, buffer, result, 0);
    }
    return result;
}
static ssize_t bridge_write(int fd, const void *buffer, size_t size) {
    if (fd != TEST_FD)
        return write(fd, buffer, size);
    request(5, size, 0);
    exact(protocol_out, (void *)buffer, size, 1);
    return response();
}
static int bridge_fstat(int fd, struct stat *st) {
    if (fd != TEST_FD)
        return fstat(fd, st);
    request(6, 0, 0);
    int64_t size = response();
    if (size < 0)
        return -1;
    memset(st, 0, sizeof(*st));
    st->st_size = size;
    st->st_mode = S_IFREG | 0600;
    return 0;
}
// Full ftruncate is deliberately refused. SAF exposes truncate at open only.
static int bridge_ftruncate(int fd, off_t size) {
    if (fd != TEST_FD)
        return ftruncate(fd, size);
    errno = ENOTSUP;
    return -1;
}
#define open bridge_open
#define close bridge_close
#define lseek bridge_lseek
#define read bridge_read
#define write bridge_write
#define fstat bridge_fstat
#define ftruncate bridge_ftruncate
#define main upstream_fsx_main
#include "vendor/fsx.c"
#undef main
int main(int argc, char **argv) {
    protocol_out = dup(STDOUT_FILENO);
    if (protocol_out < 0 || dup2(STDERR_FILENO, STDOUT_FILENO) < 0)
        return 122;
    return upstream_fsx_main(argc, argv);
}
