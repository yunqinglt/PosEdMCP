# PosEdMCP

English | [中文](README.zh-CN.md)

An MCP server for an agent running on the Android device itself. An LSPosed module plus a
root shell, so an agent living *on the phone* can actually operate the phone.

This is not remote control: the server runs on the phone, the agent runs on the phone, and
the network never leaves `127.0.0.1`.

> The app's launcher name is 奈何桥 ("Naihe Bridge"); PosEdMCP is the same thing.

## What it does

| Capability | Route | Confirmation |
|---|---|---|
| Device/module status, app list, event stream | this process | no |
| Foreground app, screen on/off | system_server module | no |
| Run a shell command (uid 0) | `su` | **yes, every time, cannot be disabled** |
| Screenshot | system_server privileges / `screencap` | yes (the system route can be disabled) |
| Dump the view tree | `uiautomator` (root) | **yes, cannot be disabled** |
| Inject taps/swipes/text/keys | system_server privileges / `input` | yes (the system route can be disabled) |
| Inject and call code in a third-party app | LSPosed scope + in-memory DEX | yes (can be disabled) |
| An app process joining the device bridge | the module inside it dials out | **yes, once per package, cannot be disabled** |

One thing overrides every "yes" in that column, for a quarter of an hour at a time and only
if the user arms it deliberately: **hand-off mode**, below.

## Three things the design turns on

**One: the confirmation dialog is the only gate.** Every privileged operation raises an
overlay that spells out, word for word, the command about to run and the reason the agent
gave for it. The user approves it by hand, or it does not happen. `root_shell_exec` cannot
be relaxed — that is the whole point of the project: an agent must not run a command the
user has not read. When the overlay cannot be shown (neither the overlay permission nor
the notification permission is granted) the call is **refused**, not allowed through.

There is exactly one exception, and it is a decision the user makes rather than a setting:
**hand-off mode** hands the whole gate over for a fixed, self-expiring window. What it is
for, what it costs, and why it is built the way it is are [below](#hand-off-mode-the-gate-off-on-purpose).

**Two: there are no automatic fallback chains.** Which tools go through a shell and which
use the module's platform access is spelled out explicitly. Otherwise a relaxed setting
could quietly downgrade into a root command nobody ever read.

**Three: nothing in system_server hooks a framework method.** Screenshots and input
injection go through reflection over hidden APIs (all wrapped in try/catch, falling back
to the root path), and the foreground app is found by polling every 2 seconds rather than
registering a `TaskStackListener` — registering one means reaching into a private singleton
of `ActivityTaskManager` and an AIDL interface, and getting it wrong is a system_server
crash and a phone that reboots forever. A monitoring module that causes a bootloop is far
worse than one missing an event.

## Installation

Requires root (Magisk / KernelSU), LSPosed, and Android 9+. Developed and verified on
Android 16 / arm64.

```bash
./tools/bootstrap-gradle.sh     # downloads Gradle (first time only)
echo "sdk.dir=E:/SDK" > local.properties   # Android SDK path, adjust as needed
./tools/gradle.sh assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

Enable PosEdMCP in LSPosed Manager and tick **System Framework** in its scope. Tick
third-party apps as needed. **You must reboot after changing the scope** — the system
framework hook is injected at boot.

> After changing the APK and reinstalling, **reboot as well** for system_server to pick up
> the new code: module classes are loaded when a process starts, and system_server only
> starts once, at boot.

Open the app and work down the STATUS section:

1. **Overlay permission** — the approval dialog needs it. Without it the prompt degrades
   to a notification; without that too, privileged calls are refused outright.
2. **Accessibility** — see the next section. It also provides screen capture, gestures and
   the view tree without root.
3. The app starts the service itself and keeps a notification in the shade; the ENDPOINT
   section holds the address and token.

### Accessibility: the one step you cannot skip

**Without it, this server is dead exactly when it is needed most.** The moment the app
leaves the screen the platform freezes its process (measured: ColorOS does it within 24
seconds). A frozen process stops accepting connections, the MCP endpoint goes completely
silent, and **it leaves no trace in any log** — which makes it very easy to misdiagnose as
a crash or a code bug.

And the agent normally runs in **another app** (RikkaHub and the like), which is precisely
when this app is in the background.

The fix is enabling the accessibility service: a process hosting one holds a system
binding, so it is not frozen. The ROM's own accessibility screen says as much ("with
accessibility enabled, an app gains the right to auto-start and is not affected by the
auto-start management settings").

The same service also provides **root-free** screenshots, gesture injection and view-tree
reads — which on Android 16 is not a luxury: `SurfaceControl` no longer exposes a display
token, so the system_server screenshot route does not exist at all any more.

Measured, with this app in the background and another app in front:

| | Before | With accessibility on |
|---|---|---|
| Frozen threads | all (33/33 in `do_freezer_trap`) | 0/33 |
| MCP endpoint | completely unresponsive | answering normally for 90 s straight |

> **Do not use `am force-stop` when updating the app.** It marks the app as stopped, the
> system then drops the accessibility binding — you will have destroyed the keep-alive by
> hand and then wondered why it got frozen again. Use `kill <pid>`: the system restarts the
> process and rebinds because of the binding.
>
> ```bash
> adb shell su -c "kill $(adb shell pidof dev.posedmcp)"
> ```

Unrestricted battery (the **Battery** button in the app) is still worth doing, and on
ColorOS you may additionally need to allow background activity for PosEdMCP under
Settings → Battery → App battery management — but **that alone is not enough**.

> **On this ROM the app can also be killed outright, accessibility binding and all.**
> Measured mid-session: `OplusClearSystemService` reaped both of this app's processes under
> `powersavemode(kill-res)`, along with the notification manager and several other things,
> and the accessibility binding brought them back about a second and a half later. The
> binding is what keeps the app *unfrozen*; it does not make it unkillable. The visible
> symptom is the MCP endpoint refusing connections for those couple of seconds and any
> hooked app's bridge dropping — which looks like a crash and is not one. If it happens
> often, the battery settings above are the lever worth pulling.
>
> **And it can leave the service switched on but dead.** When the process dies, the
> accessibility framework records the dropped connection as a crash, puts the component in
> `mCrashedServices`, and stops binding it — while the setting, and the switch in Settings,
> still read as on. Switching it on there does nothing, because as far as that screen is
> concerned it is already on; it has to be switched **off and on**. The status tab tells the
> two apart and offers **Repair accessibility…**, which does the off-and-on over root. That
> distinction is the whole point of the button: "not enabled" and "on but not running" look
> identical from inside the app and have opposite fixes.

### Confirmation policy: what to relax, and what never to

`input_inject`, `screen_capture` and `ui_dump` prompt by default. That is unusable for UI
automation — tap once, prompt once. So those three (and the plugin-related calls) can have
their confirmation turned off.

**`root_shell_exec` always prompts and cannot be turned off.** That is deliberate: what
gets relaxed is the module/accessibility route, and the real privilege boundary does not
disappear because of a setting.

### Hand-off mode: the gate, off on purpose

Everything above assumes the user is there to read each prompt. Hand-off mode is for when
they deliberately are not — the agent is working through a long sequence and nobody wants
to tap Allow forty times, or the phone is being driven from across the room.

While it is armed, **every** kind of request is answered on the user's behalf, including
the two that no setting can relax: the root shell and the bridge trust decision. No dialog
appears at all.

What that costs is worth being blunt about. That dialog is not a formality — it is the only
thing that has ever stood between an agent and this device. A command that deletes files,
disables a system component or writes to a partition runs exactly as the agent typed it,
and a phone left unable to boot is not something this app can undo.

So it is built to be hard to open, and hard to leave open by accident:

- **It is not a switch.** Arming it means three dialogs, each naming a different
  consequence, and then typing `HANDOFF` into a field before the button even enables. A
  pocket, a mis-tap or a stray touch cannot open this; only a person reading it can.
- **It expires.** Fifteen minutes by default, extendable five at a time, deliberately and
  by hand. There is no "always on".
- **A reboot clears it — but the app being killed does not.** This ROM kills apps on its own
  schedule. Measured, mid-session: `OplusClearSystemService` took both of this app's processes
  out under `powersavemode(kill-res)`, the accessibility binding had them back a second later,
  and the remaining minutes of the window were gone with them. Losing the session to a routine
  memory sweep helps nobody, so the deadline is a wall-clock time that survives a process
  restart, and the boot time is written beside it — that is what tells a reboot, the thing
  that should end it, apart from the ROM being itself.
- **It says so, twice.** The status tab counts down from the moment it is armed, and the
  permanent notification carries the remaining time. Not a third time, and that is on
  purpose: see below for why the agent is told nothing.
- **It reports what is being done.** Every action taken through the open gate raises a
  heads-up banner naming the tool and what it ran — so an unattended window is not an
  unwatched one. Arming, disarming and the window running out each get their own banner,
  and those are never rate-limited. The action banners are: one may interrupt every twelve
  seconds, and the ones in between still update the notification silently and are counted
  in the text ("and 3 more since the last banner"). An agent works in bursts and a phone
  that buzzes forty times is a phone that gets muted, but a burst that ends inside one
  window still leaves a correct record.
- **And the log line stays.** Every auto-approval also writes to the log, naming the action
  and the time left. The decision is skipped; the record is not:

  ```
  W PosEdMCP: HAND-OFF MODE ARMED for 15 min - every tool now runs without asking
  I PosEdMCP: confirmation[SHELL] AUTO-APPROVED by hand-off mode (14 min left): id
  ```

And the thing it does not change: **only the user can arm it.** There is no tool for it,
so an agent cannot widen its own authority.

**And the agent is deliberately not told.** It would be easy to report the state in
`module_status`, and the first version did exactly that. It was taken back out: telling a
model "nobody is checking right now" is precisely the context that invites it to take
liberties. The opposite worry — an agent that lets the dialog do its thinking for it — is
answered where it belongs, in the prompt, which now tells it to assume every command may
execute exactly as written with nobody having read it, and that the dialog is not its
safety net. So the agent sees a confirmation policy that says nothing about hand-off, and
the guidance that makes that safe holds whether or not the mode is on.

### Root availability

If the root manager hides `su` from apps by default (Magisk's SuList mode, KernelSU's
equivalent), then inside an app process `su` **simply does not exist**
(`No such file or directory`). Note that checking with `adb shell` or `run-as` gives a
misleading answer — those two inherit the shell's mount namespace, and an ordinary app
process does not. `device_info`'s `root.diagnostics` says which case you are in.

## Connecting a client

The server listens on `127.0.0.1:8765`, endpoint `/mcp` (MCP Streamable HTTP), with Bearer
token authentication.

A client on the same device connects to `http://127.0.0.1:8765/mcp` directly. A client on
a PC needs `adb forward` first. The agent prompt is
[docs/MCP_PROMPT.md](docs/MCP_PROMPT.md). `/health` needs no authentication and is there
to check liveness.

For testing, it is worth bypassing `adb forward` — it can fail silently once the app
process is replaced:

```bash
POSEDMCP_TOKEN=<token> ./tools/mcp-call.sh '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Tools

Read-only: `device_info`, `module_status`, `list_packages`, `foreground_app`,
`events_poll`, `plugin_list`, `apk_info`, `apk_list`, `dex_classes`, `dex_search`,
`smali_disassemble`, `smali_assemble`, `hook_records`, `hook_list`, `script_list`.

Prompt first: `root_shell_exec`, `screen_capture`, `ui_dump`, `input_inject`,
`plugin_load`, `plugin_invoke`, `lua_exec`, `hook_method`, `hook_lua`, `hook_clear`,
`invoke_method`.

No prompt but still changes state: `launch_app` — brings an app to the front, exactly as
tapping its icon would. And `script_save` — files a script into the app's own Scripts page,
which only writes this app's own storage and changes nothing on the device. They are
listed here because they do not prompt while still changing what you will see.
`smali_assemble` writes files without prompting for the same reason — the real boundary is
**execution**, and that happens in `plugin_load` and `lua_exec`; tapping Run on the Scripts
page is execution.

### The Scripts page

The app's UI has three tabs: **Status** (service state, endpoint, confirmation policy, tool
list), **Scripts** and **Hooks**. An **About** button sits at the right of the toolbar: the
author, the project, and — the line that earns its place — **which framework is actually
running this module**, with the hook API it chose. That answer comes from a module instance
over the bridge rather than from anything the app works out itself, because this process
cannot see the module's own classes; all it could conclude on its own is what is *installed*,
which is not the same question. On LSPosed it reads `LSPosed 1.10.2 · classic hooks`; on
Vector, `Vector 2.2, libxposed API 102 · libxposed hooks`.

The Scripts tab lists the scripts the model filed for you, each showing its name, what it
does, its target app, and **the result of the last run**; you can open the source, run it,
or delete it.

Tapping Run there **does not raise a confirmation dialog** — that tap is your own decision,
and asking again would be asking twice. So the precondition for giving the model this
ability is that you have looked at the script and you are the one running it. The path the
model runs by itself (`lua_exec`) still prompts every time.

**Run brings the target app to the front first.** This ROM freezes an app within seconds of
it going to the background, and a frozen process does not answer the bridge, so a script
that ran anyway would only time out — hence starting the target, waiting for its module to
connect, and then running. The cost is that you get switched to that app while this one
goes to the background: **that depends on the accessibility service running** (without it,
this app gets frozen too and the run never finishes). So when accessibility is off, Run
says why and offers a button to the setting, instead of leaving you looking at a failure
that looks like a script bug.

The UI is **Material 3** (`Theme.Material3.DayNight.NoActionBar` + Material Components):
toolbar, TabLayout, script cards, buttons graded by how destructive they are (filled /
tonal / outlined), switches. Every colour comes from the theme, so on Android 12+ it
follows the wallpaper palette and elsewhere falls back to Material's defaults — nothing in
the code names a colour of its own.

> The Material3 theme inherits an AppCompat theme, so `MainActivity` is an
> `AppCompatActivity`, and AppCompat and Material therefore end up in the module's dex:
> **the APK went from 14 MB to 26 MB** (debug, unminified). Classes load on demand, but the
> module APK is injected into every scoped process, so it is worth knowing about. If it
> bothers you, turn R8 on for release — neither build has it on today.

## Decompilation and runtime observation

For an agent doing reverse engineering on the device. It produces smali assembly and
application metadata, not Java source; changes are made by **runtime injection**, without
touching the APK or re-signing anything.

```
apk_info / apk_list       what the app is: manifest, components, permissions, signature, files
   ↓
dex_classes / dex_search  what is inside: classes, methods, strings
   ↓
smali_disassemble         how exactly it is written
   ↓
hook_method               what actually happens at runtime (zero DEX, the module hooks it directly)
   ↓
   ├─ the change is a "value" (fix a return, swap an argument, set a field) → still hook_method.
   │  That is data, not code: nothing to compile, no DEX touched, and records are marked
   │  altered to prove it took effect.
   ├─ the change is "logic" (loop, branch, build a string, call several APIs in a row) → lua_exec.
   │  Also nothing to compile: the interpreter came into the target process with the module.
   └─ neither a value nor logic, but a structural change → smali_assemble → plugin_load
```

- Disassembly/assembly use **baksmali/smali**, pure Java, running directly on ART (apktool
  does not work — its resource decoding calls a native aapt2 on the host)
- The manifest is parsed with the platform's own `PackageManager`, which is more accurate
  than any reimplementation
- The engine runs in its own process (`android:process=":dex"`): decompiling a large APK
  eats memory, and an OOM kills only that process, leaving the MCP endpoint and the
  confirmation dialog you are looking at untouched
- Large outputs always go to disk, returning a path and counts; only a single small class
  is inlined
- **Hooks are per-process state**, so `hook_method` / `hook_clear` / `plugin_load` act on
  **every** process of the package, and `hook_records` merges results across processes with
  their origin labelled. An app often has several processes, and asking only one of them
  gets you the misleading answer "nothing is hooked"

## Injected logic: why Lua

`invoke_method` can express "one call" and `hook_method` can express "one change", but a
great many things are neither: list a directory, then decide the next step from what came
back; concatenate several return values; loop over a batch of objects. That is **logic**,
and until recently the only way to express it was to hand-write smali.

That road is too steep for a model, and its failures are **silent**: get the polarity of a
single null guard backwards and it lists nothing, appends nothing, and throws nothing — it
looks exactly like "the data is not there". In a real test, a probe injected into the
GitHub app concluded "this app is not signed in" that way, when it was signed in all along.

So there is a Lua interpreter (LuaJ, pure Java):

- **Nothing to compile.** The interpreter is injected into the target process along with
  the module, so a script has no assembly, transfer or load step — it is just text.
- **The confirmation dialog shows the script itself**, which is far more readable than
  smali: the user sees exactly what is about to run.
- **It does not widen the capability surface.** A script can reach the same things the
  existing tools can: the app's Context, its class loader, its methods (private ones
  included), its fields, its files. It is a new syntax for existing privileges, not a new
  privilege.
- **No `io` and no `os`.** Files are readable only through `app.files` / `app.read`, and
  read-only at that.
- **It cannot hang the app.** A script is interrupted once it passes its instruction
  budget, and the script cannot switch that guard off.
- **`app.files` throws when it cannot read a directory rather than returning an empty
  table.** An empty table means exactly one thing: the directory really is empty. This
  rule exists specifically to kill the class of silent failure described above.
- **`app.db` is read-only.** It is there for "this app is incomprehensible, what does it
  store?" — obfuscated class names do not help, a database schema does. Read-only is not a
  limitation but the point: a handle that cannot write cannot corrupt a database the app
  still has open; writing should go through the app's own API (`ContentResolver` and the
  like), which updates caches, observers and notifications along the way, and a bare UPDATE
  does not. Reading a database is the same level of access as reading its files, which
  `app.read` has always had.

A script gets a global table `app`: `name()`, `uid()`, `context()`, `class()`, `new()`,
`call()`, `get()`, `set()`, `methods()`, `files()`, `exists()`, `read()`, `db()`,
`native`, `hook()`, `log()`. `app.call` goes through `getDeclaredMethod` +
`setAccessible`, walks the inheritance chain, and picks an overload by **scoring how well
the argument types fit** (`ContentValues` has nine two-argument `put`s, and
`put("title","Dentist")` still picks `put(String,String)`); a genuine tie is **refused with
the candidates listed** rather than guessed, and you can then spell it the way
`app.methods` printed it: `app.call(values, "put(String,Integer)", "key", 5)`.

The smali road is still there: **structural** changes that neither `invoke_method` nor Lua
can express still have to take it.

## Persistent hooks: why, and what they cost

A runtime hook lives only inside **the process it was installed into**. On this device that
is not a small problem: ColorOS freezes background apps within seconds and reaps them at
will, so by the time the user does the thing you are watching for, the app may well be a
freshly started process — and the hook is gone. The whole chain of "find the method → hook
it → have the user do the thing → come back and read the records" is worth nothing at the
next app start. That is not inconvenient; it makes the entire workflow unreliable.

So `hook_method` and `hook_lua` do not just install a hook — they **write it down**:

```
hook_method / hook_lua
   ├─ installs it right now in every live process of that app (as before, so you can see whether it took)
   └─ files the definition in the app's own hook library
          ↓
   a process starts and its module connects to the bridge
          ↓
   the app pushes that package's *enabled* hooks back into it (hook_method / lua_exec)
          ↓
   hook_list shows "last armed at", and the Hooks page shows it as on
```

The definitions live in this app's own SharedPreferences (the module cannot read them —
every cross-uid read of prefs is walled off on this device), and **the app pushes them back
when a peer connects**. The push has to happen on its own thread: the thread that accepted
the connection is about to become that peer's read loop, and sending a request from it
would wait for a reply nothing was reading — a deadlock.

**The cost has to be said out loud: a persistent hook prompts only once.** After that it
takes effect every time the app starts, without asking anyone. That touches the very core
of this project, where the confirmation dialog is the only gate. What replaces it is the
**Hooks page**: grouped by app, expanded to list each hook (layer, target method, a text
description, when it was last armed or why it failed), with a switch and a delete button.
Which means that page **has to be accurate**, and **switching off or deleting has to reach
into the live process and unhook**, not merely cross out a record — otherwise the user
believes they stopped it while it comes back at the next app start. `hook_clear` works the
same way: it deletes the saved definition along with the live hook.

**And it works with no live process at all**, which turns out to be the case it is most needed
for. A hook that crashes its app keeps crashing it, because the app re-arms that hook on every
start — so the saved definition is the thing that has to go, and removing it needs nothing
running. The first version of `hook_clear` asked for a reachable process first, like every
other hook tool, which meant the one operation that could have broken the loop refused in
exactly the situation that needed it. Someone hit that and had to clear the hook by hand in the
app. The check is gone; the answer now reports honestly instead — which processes were reached
(usually none) and which definitions are now gone.

It also takes an **`id`**, which `hook_list` now reports, so one hook can be removed on its own.
A target string is readable but a *substring* of it is not: on a class carrying eleven ad hooks,
"delete the one I mean" needs something exact to point at. An id that does not resolve is
refused outright rather than quietly falling through to the filters, so a stale one cannot turn
into "everything in this package".

**Lua can hook too.** `app.hook{...}` hands the module a Lua function, which is held on the
Java side — the script's environment is discarded when it finishes, so that reference is
the only thing keeping the closure alive — and it is called back on every match. It is the
only form in which Lua can "keep working after it has finished", and it fills exactly the
gap `lua_exec` could not. Persisting it means storing the script and re-running it at
process start to arm itself, so **the script should do nothing but register the hook**.

An error inside a hook body never reaches the application (whose own call it is
interrupting), but it **is** recorded and reported by `hook_records` as `bodyError` — a
hook that throws on every call otherwise looks exactly like one that matches nothing, and
that is precisely why this module was rewritten.

## Hooking `system_server`, and the module that gets you out of it

This module deliberately hooked nothing inside `system_server`. Screen capture, input injection
and foreground tracking all go through public broadcasts and reflective calls into
`IActivityTaskManager` inside `try/catch`, precisely so a platform change could degrade a
feature without taking the process down with it — a bootloop caused by a monitoring module
being a far worse outcome than a missing event.

That held until the point where the agent could not touch `system_server` **at all**, and the
error it was given made things worse. Three separate things were in the way, and any one of
them was enough:

1. **It could not be addressed.** A peer from `system_server` registers as role `system`, whose
   key is the bare word `system`. Every hook tool resolved its target through the
   `app:<pkg>:<pid>` prefix, so `hook_method` refused at `requirePeer` before anything else
   happened — and the message it refused with said "add the package to the module's scope",
   which is advice to do something that had already been done.
2. **There was nothing there to answer.** `SystemHooks` registered six ops — `status`,
   `screenshot`, `input`, `foreground`, `ping`, `probe_display` — and no hook ops at all.
3. **Nothing would put a hook back.** `BridgeServer` fired its peer-ready callback only for
   role `app`, so the re-arm path that keeps every other hook alive never ran for this one.

The fix is one word wide on the outside and rewrites nothing on the inside. A hook cannot be
stored without a package to hang it on, so `system_server` is addressed as **`android`** — what
classic Xposed calls it — and everything that resolves a target now goes through one helper
that maps that name to the `system` peer. `HookRegistry`, `LuaRuntime` and `MethodInvoker` were
not touched: they want a `ClassLoader` and nothing else, so registering them on the system
bridge client is the whole of it.

### Telling system_server apart from a process that merely loaded its classes

`Framework.isSystemServer` used to decide from the package name the framework handed it, with the
process name as a second guess and `/proc/self/cmdline` only as a last resort. The first of those
does not answer the question it looks like it answers: a callback's package name is whichever
package the framework happened to be loading, and in `com.android.systemui`'s process that can be
`android`.

Measured on Vector: systemui therefore decided it was system_server and installed a second
`SystemHooks`. Both copies connected as the `system` peer — and since a peer's key is its role,
`BridgeServer` closed the older one each time a newer arrived. The two then took turns, connecting
and being closed once a second, for hours. Every symptom was indirect: the system bridge was never
stably up, each push to the system peer raced a socket that was being torn down, and the system
ops could be answered by systemui with its own privileges rather than system_server's. None of it
was visible in the app, which is why it took a log to find.

The process's own name is the only signal here that cannot be about a different process, so it
decides whenever it can be read; the framework's hints are kept for a process whose own name
cannot be read at all.

**The bargain is different here, and the prompt says so.** A kept hook inside `system_server`
is put back before the user can reach the page that would remove it, which is how a bad hook
becomes a phone that cannot finish booting. The confirmation dialog for that target is
therefore longer than any other in the project: what `system_server` is, whether the hook is
kept, and whether the rescue module below is installed. A `persist=false` hook is still
allowed while that module has system hooks suspended — it cannot outlive the process, and
refusing it too would take away the one way left to look at what went wrong.

### `posedmcp-guard`

The way out cannot live in this app, because the app is what may never get to run. It is a
Magisk module in [`magisk/posedmcp-guard`](magisk/posedmcp-guard), installed with
[`tools/install-guard.sh`](tools/install-guard.sh) — no zip, because a zip is an installer for
an installer and `adb` is how everything else here reaches the device.

```
post-fs-data.sh   counts kernel boots that never finished, before zygote is up
service.sh        counts system_server restarts - the only way to see a userspace
                  reboot, since post-fs-data does not re-run for one - and clears
                  the counters once the device has settled for two minutes
```

It acts in two stages, and the order is the point:

1. **Suspend, destroying nothing.** The app writes a note in its own external media directory
   whenever enabled hooks point at `system_server`. The guard reads that note and acts only
   then, which is how it tells "this device is rebooting because of us" from "this device is
   rebooting" — with no note it resets its counters and leaves the problem to whoever owns it.
   With one, it writes a kill-switch (a `persist.` property, plus the same fact as a file) that
   the app checks **before arming any kept system hook and before showing anyone a dialog**.
   The hook library is untouched, the reason appears on the Hooks page, and one button lifts it.
2. **Only if that did not help**, it copies the hook library to
   `/data/adb/posedmcp-guard/backup/` and moves it aside.

Two decisions inside it were wrong first, and both are worth knowing:

- **A missing `pidof` is not information.** The first version compared the pid it read against
  the last one it had, so an ordinary reboot — where the pid goes away and comes back — counted
  as two restarts, and suspended a device that was doing nothing wrong. An empty reading is now
  ignored; only a changed, present pid counts.
- **Lifting the suspension is information too.** The user clearing the kill-switch from the
  Hooks page means the last suspension was not left in place to be tested, so the guard stands
  back down to stage one instead of escalating to the stage that moves files.

The scripts are checked for CR before they ship. A Magisk script with CRLF endings fails as
`#!/system/bin/sh<CR>: not found`, at a point in boot where nothing can tell you why.

## Injecting into the native layer

`app.native` pushes code one level further down: the native code inside the target process.
The module itself runs from inside the target application's process, so the .so files it
loads live in that process's address space, reaching things Java cannot — the app's own
.so files, libc, and anywhere a symbol name resolves.

```
app.native.status()           which load path was used, or why it is unavailable
app.native.probe()            self-check
app.native.open(path)         dlopen, returns a small integer id; nil on failure
app.native.symbol(id, name)   dlsym, returns the address ("0x…"); nil on failure
app.native.call(addr, ...)    call a function pointer (up to six arguments)
app.native.read(addr, len)    read memory, returns a table of bytes
app.native.write(addr, bytes) write memory
app.native.string(addr[, max]) read a C string
app.native.error()            the last dlopen/dlsym error
```

**Trade-offs you have to know about:**

- **Addresses are hex strings, not numbers.** A Lua number here is a double, and a pointer
  only round-trips exactly if it fits in 53 bits — measured, one did not, came back four
  bytes off, and the next `dlsym` took the target process down. A string is exact, and it
  is readable when printed. To compare numerically use `app.native.number("0x…")`, which
  returns a value only when it can be represented exactly and nil otherwise — rather than
  quietly rounding.
- **dlopen handles never leave the native layer.** On this device a handle is not always an
  address: a library in a non-default namespace gets a synthetic value from an internal
  linker table. Sending that out to Java and Lua and back makes `dlsym` crash inside the
  linker's own namespace lookup, so callers get a small id and the real handle stays in a
  table on the C side.
- **Arguments and return values are machine words**, so this only drives integer/pointer
  functions: floats, doubles and structs passed by value cannot be expressed. That is the
  inherent limit of a word-sized bridge, not something to be filled in later.
- **Reading or writing a bad address takes the target process with it.** That is the nature
  of reaching into someone else's memory; there is no safety net. Where a script's
  addresses come from determines how dangerous it is.

**How the .so gets in:** the module APK carries `lib/arm64-v8a/libposednative.so`, and
inside the target process it is `System.load`ed as `<apk>!/lib/<abi>/lib.so` — Android's
linker understands that form, and the file context of the APK is executable by the app, so
nothing has to be written anywhere. **Verified on the device** (nativeloader log:
`Load …base.apk!/lib/arm64-v8a/libposednative.so using isolated ns … : ok`).

> The module cannot find that path itself: its class loader gives no code source, and the
> PackageManager cannot see an application it does not own. **This app tells it the path
> over the bridge.**

## Architecture

```
┌──────────────── app process (dev.posedmcp) ─────────────────┐
│  McpService (foreground service)                            │
│    ├── HttpTransport  127.0.0.1:8765  /mcp                  │
│    ├── McpServer      JSON-RPC, tool dispatch               │
│    ├── ToolRegistry   28 tools + confirmation policy        │
│    ├── ConfirmationGate ──> ConfirmOverlay (app overlay)    │
│    ├── BridgeServer   127.0.0.1:8766  (in-device bridge)    │
│    ├── RootShell      su, piped stdio (not a pty)           │
│    └── EventStore     ring buffer + seq cursor              │
└─────────────────────────────────────────────────────────────┘
             ▲ TCP + token / first connection approved by the user
             │
┌────────────┴────────────┐   ┌──────────────────────────────┐
│ system_server           │   │ scoped third-party apps      │
│  (role=system)          │   │  AppHost                     │
│  SystemHooks            │   │   ├ in-memory DEX loading    │
│   ├ screenshot / input  │   │   ├ plugin invocation        │
│   ├ foreground polling  │   │   └ hook registry            │
│   └ screen on/off       │   │                              │
└─────────────────────────┘   └──────────────────────────────┘
```

Plugins travel into the target process as a byte stream over the bridge and are loaded with
`InMemoryDexClassLoader`, **never written to disk** — otherwise every injection would first
have to push a file through a root command, which is one more confirmation dialog each
time.

An app with several processes (the clock has a main process and `:clockWidget`) registers
each as `package:pid`; a plugin is loaded into whichever process, and calls are routed
there.

## Two backends, and why the framework picks one

The module is injected by an Xposed framework, and there are now two of those in
circulation: the classic `de.robv.android.xposed` API from 2012, and
`io.github.libxposed.api`, the redesign that Vector — LSPosed rewritten by the same
author — is built on. So the module ships **two entry points** and does not choose
between them:

| | declared by | entry class |
|---|---|---|
| classic | `assets/xposed_init` | `PosEdMcpModule implements IXposedHookLoadPackage` |
| modern | `META-INF/xposed/java_init.list` + `module.prop` | `VectorModule extends XposedModule` |

Whichever the framework invokes is the backend, and that is not a preference that
could be expressed any other way: the classic entry is handed an `XposedBridge`, the
modern one is handed an `XposedInterface`, and neither can reach the other's. Which
one you get was decided by who called you. Both then do exactly the same thing —
hand the same class loader to the same `SystemHooks` and `AppHost` — so there is one
implementation of what this module does and two ways of being started.

**What forced it.** Vector still loads classic modules, through a compatibility
bridge (`VectorLegacyBridge` in its logs), and that bridge is thinner than the real
thing. Measured on Vector 2.2: `AndroidAppHelper.currentApplication()` returns null
there, so `lua_exec`'s `app.context()` was always nil and every script that needed a
Context — a ContentResolver, a package manager — simply could not be written. The
framework's own release notes describe the legacy bridge as the fragile half: they
record a release where "modules loaded, and then nothing happened" because R8 had
merged a class that `XposedHelpers.findClass` touches on its way in. Running on each
framework's native API beats asking one of them to emulate the other.

**The parts that differ** are small, because hooking was already behind an interface:

- `Framework` is the seam. Both entries record themselves there; `HookRegistry` and
  `app.hook` ask it for a `HookApi` without caring which arrives.
- `LibXposedHookApi` is the second implementation. Two things are genuinely
  different: the modern API has **no `setResult`** — an interceptor either calls
  `chain.proceed()` or answers for the method by returning without calling it, which
  is what `setResult` meant — and arguments travel with `proceed(args)` rather than
  in a shared array. Both are reconciled in about thirty lines.
- `AppHost.currentApplication()` asks the platform first (`ActivityThread`) and the
  framework second. The platform route works under both; the framework route is the
  variable one.

### What an API 100 framework does with a modern entry

The tidy story above is not how it went, and the detour is worth keeping because
none of it is guessable. LSPosed 1.10.2 is an **API 100** framework, and it reads
`META-INF/xposed/java_init.list` as well — then, crucially, **it does not fall back
to `assets/xposed_init` when that entry fails**. So an entry it cannot load is not a
degraded module, it is no module at all:

| declared | what LSPosed 1.10.2 did |
|---|---|
| `minApiVersion=101` | refused the module outright: *"this module requires a newer Xposed version (101), so it cannot be activated"* |
| `minApiVersion=100` | accepted it, found the entry, and constructed it the **pre-101 way** — with `(XposedInterface, ModuleLoadedParam)` as constructor arguments — then died with `NoSuchMethodException` |

So the entry has to be constructible both ways. `VectorModule` has a no-argument
constructor for 101+ (the framework calls `attachFramework()` and then
`onModuleLoaded()`) and a two-argument one for 100 (which attaches and adopts
itself). Being built the second way is also information: that framework predates
101, and its mature API is the classic one — so that route deliberately leaves
`Framework` on its classic backend, and the modern hook backend serves only the
frameworks whose classic support is the thin part.

**And one call had to become reflective.** API 102 changed `attachFramework` to take
a `Runnable` for hot reload; 101 and before took only the framework. There is no
single jar that satisfies both — compiling against 101 moves the `NoSuchMethodError`
to Vector, compiling against 102 leaves it on LSPosed. So that one method is looked
up and invoked reflectively, picking the arity that exists, and everything else is
ordinary code.

The result on both devices: Vector constructs the entry the 101 way and runs hooks
through `XposedInterface`; LSPosed constructs it the 100 way and runs hooks through
`XposedBridge`. Same APK, same build, no configuration.

**The one thing that did not change is the confirmation gate.** A backend decides how
a hook is installed, not whether the user is asked.

> **Reinstalling the module needs the framework to re-read the APK.** The daemon
> caches the module's path and descriptors, and an install moves the APK to a new
> `~~hash` directory, so the cached path goes bad — Vector logs
> `XSharedPreferences: Apk parser fails: NoSuchFileException` and keeps using the old
> entry. On LSPosed the answer is a reboot. On Vector there is a lighter one:
> `/data/adb/modules/zygisk_vector/cli modules disable dev.posedmcp` then
> `... enable dev.posedmcp` makes the daemon re-read it, and the next process started
> gets the new code.

## How credentials reach the module

This is the most convoluted part of the project, because **Android has every out-of-band
channel walled off**:

| Channel | Result |
|---|---|
| Abstract Unix socket | SELinux refuses `connectto` (`untrusted_app` → `untrusted_app`, different security categories) — a platform design boundary, not a configuration problem |
| ContentProvider | Package visibility: `Unknown authority`. The host app's manifest is not ours to change |
| Explicit `bindService` | Also blocked by package visibility, `bindService` just returns false (system apps and uid 1000 are exempt, which is why systemui can connect) |
| Reading the file directly | Android 16 moved prefs to `/data/misc/<uuid>/prefs/`, unreachable across uids; even stripping the category with `chcon` leaves `untrusted_app` restricted from reading `app_data_file` |
| `XSharedPreferences` | The framework's own mechanism, relying on the daemon granting access at boot — measured, it does not work here |

So the credentials are **handed out over the connection the app has already established**:
when an app connects to the bridge without a token, a dialog asks the user whether to trust
"some package", and on approval the token is handed over and remembered. It asks once per
package, and a refused package is not bothered again for 10 minutes.

This is not cryptographically strong identity — what gets approved is "the connection that
claims to be this package". What it buys is **integrity** (another process cannot forge
events or race the real peer with a forged reply), not confidentiality; the real privilege
boundary is still the confirmation dialog. For a scoped app the token was never going to
stay secret anyway: the module runs inside it.

The other channels are kept as optimisation paths: the external media directory
`Android/media/<pkg>/`, Binder services, ContentProvider — if one works, use it and save a
dialog.

## Languages

The interface ships in English (`res/values/`) and Simplified Chinese
(`res/values-zh-rCN/`), so it follows the phone's system language on every Android release with
nothing else switched on.

On Android 13 and later the app also appears in **Settings → Apps → 奈何桥 → Language**. That entry is
generated rather than hand-written: `androidResources { generateLocaleConfig = true }` builds
`res/xml/_generated_res_locale_config.xml` from the resource folders that exist, and takes the language
the unqualified resources are written in from `res/resources.properties`. Whether a Chinese ROM still
shows the picker is the platform's business, and the translations do not depend on it — measured on the
OnePlus: `cmd locale set-app-locales dev.posedmcp --user 0 --locales en-US` (the same API the picker
calls) switches the whole interface to English, and with no override set the app follows the system.

**"奈何桥" is not translated.** `app_name` deliberately has no Chinese counterpart: the name is a proper
noun and the app is called that in every language, which falling through to `res/values/` guarantees
without anyone having to remember it later. Strings that use the word inside a sentence
(`notif_title`, `toast_turn_on_in_list`, the bridge-trust dialog) keep it too.

Everything the agent reads — tool names, descriptions, results — stays in English. The two audiences are
separate: the interface is the user's, and the tool surface is the model's.

## Development

```bash
./tools/gradle.sh assembleDebug
./tools/build-plugin.sh          # sample plugin → tools/plugin-demo/build/plugin.b64
```

`tools/gradle.sh` redirects `GRADLE_USER_HOME` into the repository's `.gradle-home/`: some
of the toolchain breaks when the Windows user directory contains non-ASCII characters.
Proxy settings go in `.gradle-home/gradle.properties`, which is not tracked.

JDK 17+ is required (this project was verified with JDK 22).

## Known limitations

- **The system screenshot route does not work on this device.** Android 16 removed
  `SurfaceControl.getPhysicalDisplayToken` and `getPhysicalDisplayIds` — runtime
  enumeration confirms neither method exists in this device's framework at all, so it is
  not a matter of how the reflection is written. `ScreenCapture.captureDisplay` needs a
  display token and there is no public way to get one. The root `screencap` route covers
  the capability: `screen_capture` with `mode=auto` first asks system_server why its last
  attempt failed, skips the dead end and goes straight to root (one confirmation). The
  other system capabilities (foreground tracking, events, input injection) are fine.
- There are no unit tests. Every verification was done by behaviour on a real device.
- **Run on the Scripts page requires the accessibility service to be running**, for the
  reason above: it switches you to the target app, which puts this app in the background,
  and without an accessibility binding the system freezes it there and the run never
  finishes. When accessibility is off the page says so directly rather than dressing it up
  as a script failure.
- **`app.native` reading or writing a bad address kills the target app along with it.**
  That is the inherent cost of reaching directly into another process's memory and there is
  no safety net; where a script's addresses come from determines how dangerous it is.
- **`lua_exec`'s instruction budget only constrains Lua itself.** A script that spends its
  time in slow Java calls (network, files) will not trip the budget and is only bounded by
  the bridge's request timeout — and after a timeout the script's thread still finishes the
  call it is in, the same as `plugin_invoke`.
- **A persistent hook can miss the very earliest calls of an app start.** Re-arming takes
  one round trip over the bridge (process starts → module connects → app pushes the
  definition back → installed), so calls as early as `Application.onCreate` are usually
  already past. Catching those would have to happen in the LSPosed scope layer, which is
  outside this tool's scope.
- **Hooks at the native layer are not implemented.** The `layer` field on the Hooks page
  can only ever read `dex` today — it is there because native is the next step, not because
  there are currently two values. An arm64 inline hook (overwriting a function prologue
  plus a trampoline) carries a lot of risk — a bad prologue or a missed instruction-cache
  flush takes the target process down — and deserves a round of its own.

### Two traps when debugging hidden APIs

- **`Class.getDeclaredMethods()` is filtered by the hidden API policy**: the list you get
  back contains only public members, which looks like "this method does not exist". The
  exemptions have to be installed in the process first
  (`VMRuntime.setHiddenApiExemptions`, see `HiddenApi.java`), or the whole reflection chain
  silently finds nothing.

  And that exemption **cannot always be installed**. From Android 11 the call which grants it is
  itself on the block list, so a process that is not already exempt cannot reach it — an ordinary
  app process can never bootstrap itself, and
  `NoSuchMethodException: VMRuntime.setHiddenApiExemptions` means exactly that and nothing worse.
  Measured on Android 15 with targetSdk 36: a scoped app process logs `hidden API exemptions
  installed` while this app's own process logs the refusal, and that is deliberate — the app is
  **not** in its own module scope, because being there would inject the module into the process
  that runs the confirmation gate, and one kept hook in that process could approve anything.
  A refusal is a state rather than a verdict: `HiddenApi` retries until it works instead of
  latching the failure, since a process refused once may still be the framework's guest later.
- The device's `/system/framework/framework.jar` is a **stub**: the dex inside has no real
  implementations and cannot be used to look up method signatures. Enumerating at runtime
  via `device_info`'s `displayProbe` is the only accurate way.

### And one about `Settings.Secure`

`Settings.Secure.getString` keeps a name/value cache **in the calling process**, and a write
from anywhere else does not reliably invalidate it. Measured: an app that read the enabled
accessibility services that way saw this service removed from the setting immediately, and
then never saw it put back — so a user who had just repaired accessibility would have been
told forever that it was still off. Query the provider directly instead; it is the one that
took the write.

## Status

Verified on OnePlus PLR110 / Android 16 / arm64-v8a / Magisk v27.2-kitsune-4 /
Zygisk-LSPosed 1.10.2 (7182):

- The module is loaded correctly by LSPosed; system_server takes the `SystemHooks` branch
  and connects to the bridge (`role=system`)
- MCP handshake, `tools/list`, and authentication (including the 401 rejection path)
- Root shell execution and the per-command confirmation dialog; a Chinese reason renders
  character-for-character correctly
- `ui_dump` works end to end
- The system route for **foreground app queries** and **input injection**
  (`InputManagerGlobal.injectInputEvent`, not through a shell), plus the
  `foreground.changed` / `screen.on|off` event stream
- `screen_capture` with `mode=auto` correctly falls back to root when the system route is
  unavailable
- **A plugin injected into `com.coloros.alarmclock`, hooked to `Activity.onResume`**,
  verified by behaviour (it returned that app's real activity lifecycle)
- Peer registration and routing for a multi-process app
- **The static analysis chain**: `apk_info` / `dex_classes` / `dex_search` fields agree
  with `dumpsys package`; a `smali_disassemble` → `smali_assemble` round trip preserves
  method signatures exactly
- **The runtime observation chain**: hooking `Activity.onResume` in the clock's process and
  switching foreground/background, after which `hook_records` read back 3 real calls (both
  thread and timestamps correct)
- **End-to-end injection**: disassembling the app's alarm-parsing function to recover the
  real Bundle contract, then injecting a plugin that calls the app's own `add_alarm`
  interface and created an alarm in the clock app reading **06:07 / label "PosEdMCP" /
  enabled**, cleaning up the temporary alarms it created along the way with `delete_alarm`
- **Keep-alive**: with accessibility enabled and this app in the background while another
  app is in front, measured **zero frozen threads** over 90 seconds with the MCP endpoint
  answering continuously (before the fix: 33/33 threads in `do_freezer_trap`, endpoint
  completely unreachable)
- **Root-free UI operation**: `launch_app` brought the GitHub app to the front (foreground
  window `com.github.android/.main.MainActivity`)
- **`lua_exec` runs inside `com.github.android`**: the script listed the 8 entries of that
  app's data directory and the 10 files under `shared_prefs`, and read `yunqinglt /
  com.github.android` out of its own `AccountManager` — while the same check run as a
  hand-written smali probe returned an empty string, and a positive control showed that to
  be a probe bug rather than the device's state
- **`app.db`**: read-only opening of the WAL database `com.github.android` was actively
  using, listing 15 tables and reading back real rows from `recent_searches`
  (`sunflower233`, `mlinux-project`, `micode`); a missing database and a missing table both
  produce specific errors
- **The Scripts page**: a script filed by `script_save` appears there with its name, target
  app, purpose and last run result; tapping Run really executes it over the bridge and
  writes the result back to the card (in that test the target process was frozen in the
  background, and the card honestly showed `FAILED … timed out`)
- **Persistent hooks (end to end, except for the one manual approval)**: with a hook
  definition placed in the hook library, **the target app was a freshly started process** —
  as soon as its module connected to the bridge the hook was installed by itself, and
  `hook_records` read back 3 real `Activity.onResume` calls (thread `main`, timestamps
  correct); a second process of the same app started later (`:clockWidget`) was armed
  automatically too. No confirmation dialog was raised anywhere in that run, which is
  exactly what persistence had to prove
- **Switching off takes effect immediately**: turning the switch off on the Hooks page
  unhooked the live process on the spot (`hook_records` became "nothing is hooked"), and
  **it did not come back when that app restarted**; turning it back on armed the running
  process again immediately
- **Lua hooks**: `app.hook{...}` registered and persisted; the callback fired on real calls,
  a local variable in the closure accumulated across calls (`#3` in the log), and
  `ctx.this` resolved to the real instance with `ctx.args` of length 0 — all as expected
- **The Hooks page**: grouped by app, collapsed by default, expanding into the second level
  to list `DEX · RULE` / `DEX · LUA`, the target method, a text description and
  `last armed <time>`, with a switch and a delete button
- **Real use, by the person who built it**: twelve Lua hooks on `com.coolapk.market` removing
  its ads — collapsing feed ad cards to zero height, dropping the embedded sponsor cards out
  of a post, closing the splash and interstitial activities on creation — registered through
  `hook_lua` and re-armed in every Coolapk process since
- **Hand-off mode, end to end**: the three warnings and the typed word (the arm button stays
  disabled for the wrong word and enables for the right one); arming it; the live countdown
  on the status tab; **`root_shell_exec` — the tool that otherwise always prompts — returning
  `uid=0` in 103 ms with no dialog**; `module_status` reporting `HAND_OFF_MODE` to the agent
  with the time left; the audit lines above; turning it off restoring the gate; and a service
  restart clearing it
- **The banners**: "Hand-off mode is ON" on arming; a burst of four root commands produced one
  banner reading `echo burst-4` / `(and 2 more since the last banner)`, so the burst is folded
  rather than machine-gunned and nothing is lost from the record; "Hand-off mode is off
  (turned off in the app)"; and "Hand-off mode is off (the service restarted)" after killing
  the process, which also showed that posting from the service's `onCreate` does not disturb
  the foreground-service startup. And the negative case: with hand-off **off** and the
  screen-capture confirmation relaxed, `screen_capture` ran with no prompt and posted **no**
  banner — the reports are scoped to hand-off, not to "anything that happened without a
  prompt"
- **The window outlives the app being killed, and not a reboot.** Armed, then the process
  killed — which is exactly what this ROM had done to it — the countdown came back where it
  was (`ON — 14:21 left` after the restart). The boot time written beside the deadline is
  what keeps a reboot, which should end it, in a different category.
- **The accessibility state is told apart properly.** After the ROM's cleaner killed the
  process, the service sat in the framework's crashed set: switched on, not running, and
  refusing to rebind — the state the user hit and reported as "此服务出现故障". Toggling the
  component out of `enabled_accessibility_services` and back cleared it (measured: the
  crashed set went from containing this component to empty, and the service bound again).
  The status tab now says which state it is in and offers that toggle as a button.
- **The second backend, on Vector 2.2**: the framework loads `VectorModule` through
  `VectorModuleManager` rather than pushing the classic entry through `VectorLegacyBridge`;
  `app.context()` returns the clock's real `DeskClockApp` where it had always been nil;
  `LibXposedHookApi` installed a hook that recorded a real `Activity.onResume` on the main
  thread; and the ported clock probe runs whole on it — its preference file, and its three
  alarms read back through its own provider.
- **And the old one, on the entry it did not expect.** LSPosed 1.10.2 takes the *modern* entry —
  `java_init.list` wins and there is no fallback — constructs it the pre-101 way, and runs it
  with the classic hook backend. A scoped app restarted on it is injected again, and `lua_exec`
  inside that app returns its real `AlarmClockApplication` as a Context. Both devices, one APK.
- **A kept hook inside `system_server`.** On the Xiaomi (Vector 2.2, Android 15): `hook_list`
  for `android` reports the system peer as running; `hook_method` came back
  `process: system, hooked: true`; and `hook_records` then showed real calls intercepted there,
  from `binder:3514_F` and `PowerManagerService` threads. After a reboot, with nobody asking,
  the hook re-armed itself inside `system_server` and was recording again. Switching it off on
  the Hooks page unhooked it live — `hook_records` immediately reported nothing — and the guard's
  marker went with it, which is what stops the rescue module acting on a device whose problem is
  something else.
- **`posedmcp-guard`, both stages.** Attribution came first and was tested first: three counted
  boots with no system hooks on disk made it stand down and reset rather than suspend. With the
  marker present, stage one set the property and the file, and the app then **refused a kept
  system hook before showing any dialog** — while still allowing `persist=false`; the Hooks page
  showed the reason with a Lift button, pressing it cleared the property and the file, and the
  guard stood back down to stage one. Stage two copied both hook libraries into `backup/` and
  moved them aside, and the app read the library back intact once it was restored.
- **`ui_dump` can no longer return a stale screen.** `uiautomator dump` leaves its output file
  untouched when it cannot reach an idle state, and the tool threw the dump's own output away,
  so a failed dump cat-ed back the *previous* tree while every check below it passed: the agent
  would then measure coordinates against a screen that was no longer there and send input to
  whatever was. The file is now removed first, so the same failure empties the output and is
  reported. Demonstrated with a sentinel file standing in for that earlier screen — the old
  shape returned it, the new one returns nothing.

### Not yet verified

- **The new device's failure path.** There is no test that the modern backend reports a missing
  framework, a hook that will not install, or a chain that throws, the way the classic one does.

- **The guard has never broken a real bootloop.** Every branch of it was exercised by running its
  scripts by hand against induced counters, and one ordinary reboot was watched from end to end —
  but nobody has yet made the device genuinely fail to finish booting with a bad system hook in
  place, which is the only way to know the timing works out when it matters.
- **Only an observing hook has been kept in `system_server`.** The verification used a hook on a
  method that cannot alter anything. One that *changes* behaviour there has not been run, so
  "it installs, and it survives a reboot" is established while "a bad one is survivable" is not.

- **The status tab's faulted branch has not been seen on screen.** The state is real — it is
  what prompted this — but this ROM re-binds the service quickly enough that it could not be
  held open long enough to read the page, across several attempts to force it (SIGKILL,
  `force-stop`, `settings put` while stopped). What the branch offers is the same two
  commands that were run by hand against a genuinely crashed service, and those worked.
- **Hand-off mode's expiry has not been watched to the end.** Everything around it was: the
  countdown ticks, `module_status` flips on the same comparison, and a restart clears it. But
  sitting on an open gate for a full fifteen minutes to watch the timer reach zero is not a
  good trade, so that last step is reasoned rather than observed. The "the window ran out"
  banner is on that same path and is likewise unseen. (The one attempt ended early for a
  better reason: the ROM killed the app eight minutes in, which is what turned up the restart
  flaw described above.)
- **Whether the skipped banners really stay silent is not observable from here.** The folding
  is verified — the text of the fourth call carries the count of the ones before it — but
  whether an intermediate update re-raises a heads-up is the system's documented behaviour of
  `setOnlyAlertOnce`, and `adb` cannot tell you whether a phone buzzed. If it turns out to
  re-alert, the symptom is four banners instead of one: noisy, not unsafe, and one flag to
  change.
- **A Lua hook's `set_result` / `set_arg` / `set_field` has no dedicated test.** The half that
  actually *changes* behaviour rather than observing it is plainly in use — several of the
  Coolapk effects are behavioural, and returning nil from `getDetailSponsorCard` cannot be
  done by observing — but this session verified it through that use rather than by a test of
  its own.
- The Hooks page's delete button, and `hook_method`'s registration path, have not been
  through a full round; `hook_lua`'s has, via the Coolapk hooks above.
- The accessibility routes for `ui_dump` / `screen_capture` / `input_inject` are
  implemented, compiled and wired up, but have not been through a full round on a real
  device (each one needs a human to tap the confirmation dialog). Root-free UI automation
  is the whole point of that route and is worth a first run —
  [docs/GITHUB_STAR_DEMO.md](docs/GITHUB_STAR_DEMO.md).
- In one experiment reading a tool result, Chinese text came back as U+FFFD. The same data
  was intact in the app's own log, so the most likely culprit is the test client rather
  than the server; until that is settled, anywhere text is read **back from the server** is
  worth a second look.

## Licence

None. A personal project.
