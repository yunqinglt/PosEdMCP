#!/system/bin/sh
#
# Runs once per kernel boot, before zygote. This is the only moment that is
# reliably ahead of the app putting a hook back into system_server, which is why
# the decision is made here rather than from a timer.

MODDIR=${0%/*}
. "$MODDIR/guard.sh"

guard_init
n=$(guard_bump boots)
guard_log "kernel boot #$n without a finished one"
guard_check
