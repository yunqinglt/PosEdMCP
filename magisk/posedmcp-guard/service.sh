#!/system/bin/sh
#
# late_start. Two jobs: see and count a userspace reboot, which is the failure
# post-fs-data never sees because a soft reboot does not re-run it, and clear the
# counters once the device has actually settled.
#
# All of it runs in the background, so nothing here can hold up boot, and
# nothing here has to have finished by the time the script returns. It is also
# the fragile half of this module - a ROM that reaps the watcher loses only the
# soft-reboot count, because post-fs-data's boot counter stands on its own.

MODDIR=${0%/*}
. "$MODDIR/guard.sh"

guard_init

(
    # system_server does not exist yet at late_start - measured: an immediate
    # pidof returns nothing. Waiting for it matters twice over. An empty baseline
    # would look like a restart the moment the process appeared, and the
    # userspace-reboot test has nothing to compare against without a real pid.
    current_pid=""
    attempts=0
    while [ -z "$current_pid" ] && [ "$attempts" -lt 90 ]; do
        sleep 2
        current_pid=$(pidof system_server 2>/dev/null)
        attempts=$((attempts + 1))
    done
    if [ -z "$current_pid" ]; then
        guard_log "no system_server pid after 3 minutes; the watcher is off for this boot"
        exit 0
    fi

    # A userspace reboot re-runs this script but not post-fs-data, and it changes
    # system_server's pid while leaving the kernel boot id alone. That pair is
    # what tells it apart from an ordinary boot, where the boot id moves too.
    boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    previous_boot_id=$(cat "$STATE/boot_id" 2>/dev/null)
    previous_pid=$(cat "$STATE/ss_pid" 2>/dev/null)
    printf '%s\n' "$boot_id" > "$STATE/boot_id" 2>/dev/null
    printf '%s\n' "$current_pid" > "$STATE/ss_pid" 2>/dev/null
    if [ -n "$previous_pid" ] && [ "$previous_boot_id" = "$boot_id" ] \
        && [ "$previous_pid" != "$current_pid" ]; then
        n=$(guard_bump ss)
        guard_log "userspace reboot: system_server is now $current_pid (#$n)"
        guard_check
    fi

    stable=0
    last=$current_pid
    while true; do
        sleep 20
        if [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
            stable=0
            continue
        fi
        now=$(pidof system_server 2>/dev/null)
        if [ -z "$now" ]; then
            # Read at shutdown, and in the gap between one system_server and the
            # next. Measured: this counted an ordinary reboot as two restarts -
            # the pid went away and came back - which is enough on its own to
            # suspend a device that was doing nothing wrong. A missing pid is not
            # information; a changed one is.
            stable=0
            continue
        fi
        if [ "$now" != "$last" ]; then
            last="$now"
            stable=0
            n=$(guard_bump ss)
            guard_log "system_server restarted to $now (#$n)"
            guard_check
            continue
        fi
        stable=$((stable + 1))
        if [ "$stable" -ge 6 ]; then
            # Two minutes with one system_server and a finished boot is a boot
            # that worked. Whatever happened before is not a loop.
            stable=0
            guard_reset
        fi
    done
) &
