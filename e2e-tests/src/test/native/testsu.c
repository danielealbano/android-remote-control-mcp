#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (setgid(0) != 0 || setuid(0) != 0) {
        perror("su: setuid");
        return 1;
    }
    if (argc >= 3 && strcmp(argv[1], "-c") == 0) {
        execl("/system/bin/sh", "sh", "-p", "-c", argv[2], (char *)NULL);
    } else {
        execl("/system/bin/sh", "sh", "-p", (char *)NULL);
    }
    perror("su: exec");
    return 127;
}
