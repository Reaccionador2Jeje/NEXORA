#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static int write_all(int fd, const char *data, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t count = write(fd, data + offset, length - offset);
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        offset += (size_t)count;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        dprintf(STDERR_FILENO, "nexora-pty: missing command\n");
        return 127;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) {
        dprintf(STDERR_FILENO, "nexora-pty: cannot allocate PTY: %s\n", strerror(errno));
        if (master >= 0) close(master);
        return 126;
    }

    char *slave_name = ptsname(master);
    if (!slave_name) {
        dprintf(STDERR_FILENO, "nexora-pty: cannot locate PTY slave: %s\n", strerror(errno));
        close(master);
        return 126;
    }

    pid_t child = fork();
    if (child < 0) {
        dprintf(STDERR_FILENO, "nexora-pty: fork failed: %s\n", strerror(errno));
        close(master);
        return 126;
    }

    if (child == 0) {
        close(master);
        if (setsid() < 0) _exit(126);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);

        struct winsize size = { .ws_row = 24, .ws_col = 80, .ws_xpixel = 0, .ws_ypixel = 0 };
        (void)ioctl(slave, TIOCSWINSZ, &size);

        if (dup2(slave, STDIN_FILENO) < 0 ||
            dup2(slave, STDOUT_FILENO) < 0 ||
            dup2(slave, STDERR_FILENO) < 0) _exit(126);
        if (slave > STDERR_FILENO) close(slave);

        execvp(argv[1], &argv[1]);
        dprintf(STDERR_FILENO, "nexora-pty: exec failed: %s\n", strerror(errno));
        _exit(127);
    }

    struct pollfd fds[2] = {
        { .fd = master, .events = POLLIN },
        { .fd = STDIN_FILENO, .events = POLLIN }
    };
    int stdin_open = 1;
    int child_status = 0;
    int child_done = 0;
    char buffer[8192];

    while (1) {
        int result = poll(fds, 2, 100);
        if (result < 0 && errno != EINTR) break;

        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t count = read(master, buffer, sizeof(buffer));
            if (count > 0) {
                if (write_all(STDOUT_FILENO, buffer, (size_t)count) < 0) break;
            } else if (count == 0 || errno == EIO || errno == EAGAIN) {
                if (child_done) break;
            }
        }

        if (stdin_open && (fds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t count = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (count > 0) {
                if (write_all(master, buffer, (size_t)count) < 0) stdin_open = 0;
            } else if (count == 0) {
                stdin_open = 0;
                fds[1].fd = -1;
            }
        }

        if (!child_done) {
            pid_t waited = waitpid(child, &child_status, WNOHANG);
            if (waited == child) child_done = 1;
        }
        if (child_done && !(fds[0].revents & POLLIN)) {
            char drain[1];
            ssize_t count = read(master, drain, sizeof(drain));
            if (count <= 0) break;
            if (write_all(STDOUT_FILENO, drain, (size_t)count) < 0) break;
        }
    }

    if (!child_done) {
        while (waitpid(child, &child_status, 0) < 0 && errno == EINTR) {}
    }
    close(master);
    if (WIFEXITED(child_status)) return WEXITSTATUS(child_status);
    if (WIFSIGNALED(child_status)) return 128 + WTERMSIG(child_status);
    return 1;
}
