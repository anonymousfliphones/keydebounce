/*
 * keydebounce - input filter for the Sonim XP3800 matrix keypad
 * ("soc:matrix_keypad@0").
 *
 * Two corrections, both measured on-device:
 *
 * 1. Overlap: if key B goes down while key A is still held, the ROM inserts
 *    an extra B (typing 5,6 with overlap gives "566"; the same presses with
 *    A released first give "56"). So when a new key goes down, every other
 *    held key is released first, and A's real release is swallowed later.
 *
 * 2. Bounce: a hard press can produce a second DOWN/UP ~10ms after the real
 *    release. Every release is held for debounce_ms; a DOWN of the same key
 *    inside that window cancels it (one continuous press).
 *
 * The real device is grabbed (EVIOCGRAB) only after the replacement uinput
 * device exists. If this process dies, the kernel drops the grab and the
 * keypad works unfiltered again.
 *
 * If KILL_SWITCH exists at startup, the program exits without touching input.
 */

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define TAG "keydebounce"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define DEV_NAME "soc:matrix_keypad@0"
#define KILL_SWITCH "/data/local/tmp/keydebounce.off"
#define CONF_PATH "/data/local/tmp/keydebounce.conf"
#define DEFAULT_DEBOUNCE_MS 20
#define MAX_KEYS (KEY_MAX + 1)

static volatile sig_atomic_t g_stop = 0;
static void on_signal(int sig) { (void)sig; g_stop = 1; }

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static unsigned char vdown[MAX_KEYS];   /* virtual device has this key down */
static unsigned char pending[MAX_KEYS]; /* release buffered, not sent yet */
static unsigned char forced[MAX_KEYS];  /* released early; swallow the real UP */
static long deadline[MAX_KEYS];         /* when a pending release is sent */
static long quiet[MAX_KEYS];            /* DOWNs before this are bounce */
static int scan[MAX_KEYS];
static unsigned char have_scan[MAX_KEYS];

static int read_debounce_ms(void) {
    FILE *f = fopen(CONF_PATH, "r");
    if (!f) return DEFAULT_DEBOUNCE_MS;
    int v = DEFAULT_DEBOUNCE_MS;
    if (fscanf(f, "%d", &v) != 1 || v < 0 || v > 500) v = DEFAULT_DEBOUNCE_MS;
    fclose(f);
    return v;
}

static int open_keypad(char *name, size_t len) {
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char n[UINPUT_MAX_NAME_SIZE] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(n) - 1), n) >= 0 && strcmp(n, DEV_NAME) == 0) {
            strncpy(name, n, len - 1);
            found = fd;
            LOGI("keypad at %s", path);
            break;
        }
        close(fd);
    }
    closedir(d);
    return found;
}

static void emit(int uifd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short)type;
    ev.code = (unsigned short)code;
    ev.value = value;
    if (write(uifd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) {
        LOGE("uinput write: %s", strerror(errno));
    }
}

static void send_key(int uifd, int code, int value) {
    if (have_scan[code]) emit(uifd, EV_MSC, MSC_SCAN, scan[code]);
    emit(uifd, EV_KEY, code, value);
    emit(uifd, EV_SYN, SYN_REPORT, 0);
}

int main(int argc, char **argv) {
    int nograb = argc > 1 && strcmp(argv[1], "nograb") == 0;

    if (access(KILL_SWITCH, F_OK) == 0) {
        LOGI("kill switch present, exiting");
        return 0;
    }

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);

    char name[UINPUT_MAX_NAME_SIZE] = {0};
    int realfd = open_keypad(name, sizeof(name));
    if (realfd < 0) {
        LOGE("keypad %s not found", DEV_NAME);
        return 1;
    }

    unsigned char keybits[(MAX_KEYS + 7) / 8];
    memset(keybits, 0, sizeof(keybits));
    ioctl(realfd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);

    int uifd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uifd < 0) {
        LOGE("open /dev/uinput: %s", strerror(errno));
        close(realfd);
        return 1;
    }

    ioctl(uifd, UI_SET_EVBIT, EV_KEY);
    ioctl(uifd, UI_SET_EVBIT, EV_MSC);
    ioctl(uifd, UI_SET_EVBIT, EV_SYN);
    ioctl(uifd, UI_SET_MSCBIT, MSC_SCAN);
    for (int code = 0; code < MAX_KEYS; code++) {
        if (keybits[code / 8] & (1 << (code % 8))) ioctl(uifd, UI_SET_KEYBIT, code);
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    strncpy(uidev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    uidev.id.bustype = BUS_HOST;

    if (write(uifd, &uidev, sizeof(uidev)) != (ssize_t)sizeof(uidev) ||
        ioctl(uifd, UI_DEV_CREATE) < 0) {
        LOGE("uinput create: %s", strerror(errno));
        close(uifd);
        close(realfd);
        return 1;
    }

    if (nograb) {
        LOGI("nograb: replacement device created, real keypad left alone");
        while (!g_stop) pause();
        ioctl(uifd, UI_DEV_DESTROY);
        close(uifd);
        close(realfd);
        LOGI("stopped");
        return 0;
    }

    if (ioctl(realfd, EVIOCGRAB, 1) < 0) {
        LOGE("EVIOCGRAB: %s", strerror(errno));
        ioctl(uifd, UI_DEV_DESTROY);
        close(uifd);
        close(realfd);
        return 1;
    }

    int debounce_ms = read_debounce_ms();
    LOGI("running, debounce_ms=%d", debounce_ms);

    int last_scan = 0, have_last_scan = 0;
    struct pollfd pfd = { .fd = realfd, .events = POLLIN };

    while (!g_stop) {
        long nearest = -1;
        for (int i = 0; i < MAX_KEYS; i++) {
            if (pending[i] && (nearest < 0 || deadline[i] < nearest)) nearest = deadline[i];
        }
        int timeout = -1;
        if (nearest >= 0) {
            long diff = nearest - now_ms();
            timeout = diff > 0 ? (int)diff : 0;
        }

        int pr = poll(&pfd, 1, timeout);
        if (g_stop) break;
        if (pr < 0) {
            if (errno == EINTR) continue;
            LOGE("poll: %s", strerror(errno));
            break;
        }

        if (pr == 0) {
            long t = now_ms();
            for (int i = 0; i < MAX_KEYS; i++) {
                if (pending[i] && deadline[i] <= t) {
                    pending[i] = 0;
                    vdown[i] = 0;
                    send_key(uifd, i, 0);
                }
            }
            continue;
        }

        if (!(pfd.revents & POLLIN)) continue;

        struct input_event ev;
        ssize_t n = read(realfd, &ev, sizeof(ev));
        if (n != (ssize_t)sizeof(ev)) {
            if (n == 0 || (n < 0 && errno != EINTR && errno != EAGAIN)) break;
            continue;
        }

        if (ev.type == EV_MSC && ev.code == MSC_SCAN) {
            last_scan = ev.value;
            have_last_scan = 1;
            continue;
        }
        if (ev.type != EV_KEY || ev.code >= MAX_KEYS) continue;

        int k = ev.code;
        long t = now_ms();

        if (ev.value == 1) {
            if (pending[k]) {
                pending[k] = 0;
                LOGI("bounce: key %d down %ldms after release, merged", k, t - (deadline[k] - debounce_ms));
            } else if (t < quiet[k]) {
                forced[k] = 1;
                LOGI("bounce: key %d down inside quiet window, swallowed", k);
            } else if (!forced[k] && !vdown[k]) {
                for (int j = 0; j < MAX_KEYS; j++) {
                    if (j == k || !vdown[j]) continue;
                    send_key(uifd, j, 0);
                    vdown[j] = 0;
                    if (pending[j]) {
                        pending[j] = 0;
                        quiet[j] = deadline[j];
                    } else {
                        forced[j] = 1;
                        LOGI("overlap: released key %d before key %d", j, k);
                    }
                }
                scan[k] = last_scan;
                have_scan[k] = (unsigned char)have_last_scan;
                send_key(uifd, k, 1);
                vdown[k] = 1;
            }
        } else if (ev.value == 0) {
            if (forced[k]) {
                forced[k] = 0;
                quiet[k] = t + debounce_ms;
            } else if (vdown[k]) {
                pending[k] = 1;
                deadline[k] = t + debounce_ms;
            }
        } else if (vdown[k] && !pending[k]) {
            send_key(uifd, k, ev.value);
        }
        have_last_scan = 0;
    }

    ioctl(realfd, EVIOCGRAB, 0);
    ioctl(uifd, UI_DEV_DESTROY);
    close(uifd);
    close(realfd);
    LOGI("stopped");
    return 0;
}
