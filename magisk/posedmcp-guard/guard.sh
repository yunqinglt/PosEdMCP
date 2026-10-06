#!/system/bin/sh
#
# Shared logic for the posedmcp-guard Magisk module. Sourced by post-fs-data.sh
# and service.sh; never run on its own.
#
# The whole module exists for one failure. A hook the app keeps inside
# system_server is put back before the user can reach the page that would remove
# it, so if that hook is what stops the device finishing boot, the app is in no
# position to fix it - it may never get to run at all. The fix therefore has to
# happen out here, before zygote, and it has to be automatic.
#
# It is two-stage on purpose. Suspending through the app costs nothing and loses
# nothing: the hooks stay in the library, and the user can see the reason and
# lift it from the Hooks page. Only if the device is still not booting after that
# does this move the library aside, which is the half that cannot be undone in
# place - and even then the file is copied out to $STATE/backup first.

STATE=/data/adb/posedmcp-guard
APP=dev.posedmcp
# How many unfinished boots, and system_server restarts, before acting. Three is
# a compromise: two would act on a device that merely crashed twice, and much
# more means sitting through more failed boots than anyone wants to.
LIMIT=3
# Read through the underlying path rather than /storage, which is a FUSE mount
# that is not necessarily up when post-fs-data runs.
MEDIA_DIRS="/data/media/0/Android/media/$APP /storage/emulated/0/Android/media/$APP"

guard_init() {
    mkdir -p "$STATE" 2>/dev/null
    # So the app can say whether this module is present. /data/adb is not
    # readable from an app and is not meant to be, so the fact has to be
    # published rather than looked up.
    setprop persist.posedmcp.guard 1 2>/dev/null
}

guard_log() {
    log -t posedmcp-guard "$*" 2>/dev/null
    # post-fs-data runs before the clock is set - measured: its lines came out
    # dated 1970 - so uptime is logged alongside, and uptime is what actually
    # tells one boot from another when the wall clock cannot be trusted.
    printf '%s up=%ss %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" \
        "$(cut -d' ' -f1 /proc/uptime 2>/dev/null)" "$*" >> "$STATE/guard.log" 2>/dev/null
}

# Adds one to a counter and prints the new value. Prints nothing else, so it can
# be used in a command substitution.
guard_bump() {
    local file="$STATE/$1" n=0
    [ -f "$file" ] && n=$(cat "$file" 2>/dev/null)
    case "$n" in '' | *[!0-9]*) n=0 ;; esac
    n=$((n + 1))
    printf '%s\n' "$n" > "$file" 2>/dev/null
    printf '%s\n' "$n"
}

guard_reset() {
    printf '0\n' > "$STATE/boots" 2>/dev/null
    printf '0\n' > "$STATE/ss" 2>/dev/null
}

guard_media_dir() {
    local d
    for d in $MEDIA_DIRS; do
        if [ -d "$d" ]; then
            printf '%s\n' "$d"
            return 0
        fi
    done
    return 1
}

# The app writes this while enabled hooks point at system_server; it is the only
# thing that separates "this device is rebooting because of us" from "this device
# is rebooting". Without it the module would act on bootloops that have nothing
# to do with PosEdMCP, which is the one way it could make things worse.
guard_system_hooks_on_disk() {
    local dir
    dir=$(guard_media_dir) || return 1
    [ -f "$dir/system-hooks.json" ]
}

# Stage one: ask the app to stop arming system hooks. Nothing is destroyed.
guard_suspend() {
    local reason="$1" note="$2" dir
    printf '%s\n' "$reason" > "$STATE/suspended" 2>/dev/null
    # The durable copy. A wiped sdcard cannot take a property away, and the app
    # reads it before the bridge starts, so nothing can be armed in between.
    setprop persist.posedmcp.no_system_hooks "$reason" 2>/dev/null
    if dir=$(guard_media_dir); then
        printf '%s\n' "$note" > "$dir/NO-SYSTEM-HOOKS" 2>/dev/null
    fi
    guard_log "suspended system hooks ($reason)"
}

# Stage two: the suspension did not help, so take the definitions away. The
# library is copied out first - this is recoverable, but only by hand.
guard_neutralize() {
    local stamp dir f found=0
    stamp=$(date '+%Y%m%d-%H%M%S')
    mkdir -p "$STATE/backup" 2>/dev/null
    for f in /data/user/0/$APP/shared_prefs/hooks.xml \
        /data/misc/*/prefs/$APP/hooks.xml; do
        [ -f "$f" ] || continue
        cp -f "$f" "$STATE/backup/hooks-$stamp-$(printf '%s' "$f" | tr '/:' '__').xml" 2>/dev/null
        if mv -f "$f" "$f.rescued" 2>/dev/null; then
            found=$((found + 1))
        fi
    done
    # The app's note has to go with it, or the next boot reads a device that
    # still claims to have system hooks and acts on a problem already dealt with.
    if dir=$(guard_media_dir); then
        rm -f "$dir/system-hooks.json" "$dir/system-hooks.json.tmp" 2>/dev/null
    fi
    # Anything still holding the old library in memory has to let go of it.
    am force-stop $APP 2>/dev/null
    guard_log "moved $found hook librar(y|ies) aside; backups in $STATE/backup"
}

# The app clears the property when the user lifts the suspension from the Hooks
# page, and that is new information the guard has to take: it means the last
# suspension was not left in place to be tested. Without noticing, the guard
# would treat the next instability as "still failing after being suspended" and
# skip straight to moving the library aside - taking away, on the strength of a
# decision the user had already reversed, the half that is hardest to undo.
guard_notice_lifted() {
    [ -f "$STATE/suspended" ] || return 0
    case "$(getprop persist.posedmcp.no_system_hooks 2>/dev/null)" in
        '' | off)
            rm -f "$STATE/suspended"
            guard_log "the user lifted the suspension in the app; back to the first stage"
            ;;
    esac
}

guard_check() {
    local boots ss total
    guard_notice_lifted
    boots=$(cat "$STATE/boots" 2>/dev/null)
    case "$boots" in '' | *[!0-9]*) boots=0 ;; esac
    ss=$(cat "$STATE/ss" 2>/dev/null)
    case "$ss" in '' | *[!0-9]*) ss=0 ;; esac
    total=$((boots + ss))
    [ "$total" -lt "$LIMIT" ] && return 0

    if ! guard_system_hooks_on_disk; then
        # This device has a problem, but on the evidence available it is not the
        # one this module is for. Start the count again rather than escalate, so
        # the next real occurrence is not credited with these boots.
        guard_log "unstable ($total) with no system hooks on disk - not ours, resetting"
        guard_reset
        return 0
    fi

    if [ -f "$STATE/suspended" ]; then
        guard_log "still unstable after being suspended - moving the library aside"
        guard_neutralize
    else
        guard_suspend "bootloop-$total" \
            "$total boots in a row did not finish, with system hooks in the library"
    fi
    guard_reset
}
