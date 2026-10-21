#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <syslog.h>
#include <unistd.h>

#ifdef WITH_SYSTEMD
#include <systemd/sd-daemon.h>
#else
#define sd_notify(unset, state) ((void)0)   /* SysVinit build: no systemd */
#endif

static volatile sig_atomic_t running = 1;

static void handle_signal(int sig)
{
    (void)sig;
    running = 0;
}

static long mem_available_kb(void)
{
    char line[128];
    long kb = -1;
    FILE *f = fopen("/proc/meminfo", "r");

    if (!f)
        return -1;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "MemAvailable: %ld kB", &kb) == 1)
            break;
    }
    fclose(f);
    return kb;
}

static void read_loadavg(char *buf, size_t len)
{
    FILE *f = fopen("/proc/loadavg", "r");

    if (!f || !fgets(buf, (int)len, f))
        snprintf(buf, len, "unknown");
    if (f)
        fclose(f);
    buf[strcspn(buf, "\n")] = '\0';
}

int main(int argc, char *argv[])
{
    int interval = 10;
    int opt;
    char load[64];

    while ((opt = getopt(argc, argv, "i:")) != -1) {
        if (opt == 'i')
            interval = atoi(optarg);
    }
    if (interval < 1)
        interval = 1;

    signal(SIGTERM, handle_signal);
    signal(SIGINT, handle_signal);

    openlog("sysmon", LOG_PID, LOG_DAEMON);
    syslog(LOG_INFO, "started, interval %d s", interval);
    sd_notify(0, "READY=1");        /* started: systemd may go on */

    while (running) {
        read_loadavg(load, sizeof(load));
        syslog(LOG_INFO, "load: %s, mem available: %ld kB",
               load, mem_available_kb());
        sd_notify(0, "WATCHDOG=1");     /* "still alive", every loop */
        sleep(interval);
    }

    syslog(LOG_INFO, "stopped");
    closelog();
    return 0;
}
