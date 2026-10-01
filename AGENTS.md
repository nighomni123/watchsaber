# Lightsaber — Agent Instructions

Lightsaber is a **Wear OS app that turns hand movement into a continuously modulated lightsaber
sound**. Gyroscope and accelerometer are processed locally and drive a low-latency Oboe engine;
there is no phone, no network, and no sample-triggered swing playback.

This folder (`/Users/Shared/AndroidProjects/Lightsaber`) is the project root *and* the agent
workspace. Platform research and pinned versions live in `docs/platform-decisions.md` — that file is
the source of truth for anything version-related, and this file does not repeat it.

## Project state (2026-10-01)

- **Milestones 1–2 work on a physical watch** (Samsung SM-R870, Android 16): capability detection,
  mount calibration, ignition, idle hum, continuous swing/twist modulation, accents, retraction and
  cleanup. Verified audible on device.
- Layout: `:motion` (pure-JVM signal processing, no Android types), `:app` (Wear OS Compose UI,
  sensor source, session state machine, JNI), `engine/` (C++ mixer shared by the app and the host
  offline renderer), `tools/` (asset synthesis and render analysis), `docs/`.
- **Not done**: profile persistence, on-device latency measurement, 10-minute soak, screen-off /
  foreground service (Milestone 4). See "Known gaps" in `docs/platform-decisions.md`.

## Build and run

```
export GRADLE_USER_HOME="$PWD/.gradle-home"     # see "Gradle / Kotlin caches" below
./gradlew :motion:test                          # 15 signal-processing tests
./gradlew :app:assembleDebug                    # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build JVM is pinned to JBR 21 via `org.gradle.java.home`, because Gradle 9.x predates JDK 25
support while the Android Studio JBR is 25.0.2. The debug keystore is referenced explicitly because
AGP would otherwise try to write into the read-only `ANDROID_USER_HOME`.

## Gradle / Kotlin caches

Shared homes — `GRADLE_USER_HOME=/Users/Shared/.gradle` (2.0 GB) and
`ANDROID_USER_HOME=/Users/Shared/.android` — sit on this project's filesystem. Prefer them; never
re-download what they already hold.

1. **Shared home writable** → use it directly:
   `GRADLE_USER_HOME=/Users/Shared/.gradle ./gradlew …`. No copy needed.
2. **Not writable** (the default `workspace-write` sandbox permits only this folder) → seed a local
   home from it, copying the two directories that matter rather than the whole 2 GB:
   ```sh
   export GRADLE_USER_HOME="$PWD/.gradle-home"
   mkdir -p "$GRADLE_USER_HOME"
   cp -R /Users/Shared/.gradle/caches   "$GRADLE_USER_HOME/"
   cp -R /Users/Shared/.gradle/wrapper  "$GRADLE_USER_HOME/"
   ```
   With `ANDROID_USER_HOME` unwritable, keep `keystore/debug.keystore` and the `signingConfigs`
   block; when it is writable, let AGP generate the key and drop both.
3. **Idle** → reclaim ~3 GB. Both paths are gitignored, so the repository is untouched:
   `rm -rf .gradle-home keystore`

The shared cache lacks Oboe, Compose-BOM, Wear Compose, core-ktx and the Gradle 9.8.0 distribution,
so a cold restore still fetches those. The copy avoids the large common dependencies, not all of them.

## Offline verification (no watch required)

The audio engine is deliberately buildable on the host, and this is the fastest way to prove a
change actually improved the sound:

```
./gradlew :motion:run --args="replay session /abs/path/session.txt palm_right"
clang++ -std=c++17 -O2 -o build/offline/offline_render \
    engine/host/offline_render.cpp engine/src/main/cpp/mixer.cpp
./build/offline/offline_render session.txt engine/assets session.wav 0.2 10.5
python3 tools/analyze_render.py session.wav session.txt
python3 tools/trace_vs_audio.py session.txt session.wav 250
```

`offline_render` compiles the **same `mixer.cpp` that ships on the watch** — a defect found in
replay is a defect in the shipped engine, not in a test double. The metrics that matter are
`corr(swing,rms)` and `corr(swing,zcr)`: a sample-triggered design decorrelates, a continuously
modulated one tracks closely. Expected ordering is idle < twist < swing, with accents rare.

## Conventions

- One runnable check per non-trivial piece of logic — see the test list in `:motion`, which covers
  exactly the brief's signal-processing requirements. Non-trivial things here: motion transforms,
  filtering, gravity compensation, calibration acceptance, and the mixer's crossfade and limiter.
- `mixer.cpp` must stay free of Android and of allocation. The real-time callback may not allocate,
  lock, log, touch the filesystem, or call into JNI. Control frames cross as a by-value struct.
- **Assets are procedurally synthesised** by `tools/synth_assets.py`, which writes a provenance file
  next to every WAV. Do not bundle extracted film or game audio.
- Prefer platform APIs over new dependencies; see the ponytail ladder below.
- Append hardware findings to **Session-verified facts** as they are proven, with the date and the
  exact symptom — not in chat.

## Session-verified facts

- **The test watch is a 32-bit userland**: `ro.product.cpu.abilist = armeabi-v7a,armeabi`,
  `ro.zygote = zygote32`. An arm64-only APK fails with `INSTALL_FAILED_NO_MATCHING_ABIS`. This is
  why `ControlFrame` carries `alignas(8)`: on a 32-bit ABI `int64_t` only aligns to 4, so an
  arm64-written `static_assert` fails on the real target.
- **Gyro max rate is 100.00 Hz on this watch** (LSM6DSO, STM, **non-wake-up**), accelerometer
  (LSM6DSV) likewise 100 Hz with FIFO (300, 300). 200 Hz is refused by the *hardware*, not by a
  permission, so the brief's "evaluate 200 Hz" cannot be run on this device at all.
- **Two `TYPE_LINEAR_ACCELERATION` sensors exist** (Samsung Inc., and AOSP v3), plus Samsung
  rotation and game-rotation vectors; `TYPE_GRAVITY` is AOSP v3. Benchmark which one
  `getDefaultSensor()` actually returns rather than assuming derived-sensor latency.
- **Display is 450×450**, and Wear's `Scaffold` does *not* hand content padding to its lambda the way
  Material's does. Reserve ~34dp for the `TimeText` manually or the bottom button row falls off.
- **Oboe 1.11 renamed the error interface** to `oboe::AudioStreamErrorCallback`; the old
  `oboe::ErrorCallback` is gone and `setErrorCallback(this)` fails to convert.
- **`ANDROID_STL` must be passed from Gradle** (`arguments += "-DANDROID_STL=c++_shared"`), not set
  inside `CMakeLists.txt` — the NDK toolchain resolves it before the project body runs. Otherwise the
  link fails with `CXX1212`.
- **adb**: two transports for one watch (classic Samsung duplicate) and the wireless port rotates
  every few minutes — `35507` then `46393`, both ending in `Connection refused`. Always pin a
  serial, and expect the user to have to re-enable Wireless debugging when the link dies.
- **Each install is the same app, not a new one** — `pm path dev.lightsaber.app` returns a single
  package. What resets across installs is in-memory calibration state, because persistence is not
  implemented yet. Do not "fix" this by changing applicationId.
- **The gyroscope full scale is 17.453642 rad/s** (±1000 dps), read from `Sensor.getMaximumRange()`.
  Do not hardcode 34.9 — it is a different part, and it makes saturation detection unreachable while
  leaving the normalisation range at half the sensor's real span.
- **The watch exposes two `TYPE_LINEAR_ACCELERATION` sensors** (Samsung Inc. and AOSP v3), and a
  gravity estimate that disagrees with the accelerometer leaves a **constant linear-acceleration
  offset** that reads as perpetual motion. That is what fired 138 accents in one stationary session.
  The accent basis is the deviation from a slow running mean, not the raw value.
- **Wireless debugging drops off when the watch sleeps.** After a period of inactivity all known
  ports go from `Connection refused` to `Operation timed out` and mDNS stops advertising. Ask the
  user to toggle Developer options → Wireless debugging rather than retrying ports.

---

# Agent Instructions (User Preferences)

## Web searches

- **ALWAYS use `monid` (the Monid CLI)** for web searches and web research.
- **NEVER use the built-in `web_search` tool/plugin** (or any DeepSeek/Exa-backed built-in search).
- Workflow: `monid discover --query "<what you need>"` to find a suitable data endpoint, then
  `monid inspect` and `monid run` to execute it.
- monid is installed at `/Users/Mitesh Gada/.npm-global/bin/monid`.
- monid writes its config/state via XDG paths, which this workspace sandbox denies. **Always run it
  with the project-local config** (vendored 2026-10-01 from the Documents project; holds
  `monid/config.yaml` + `monid/credentials.yaml`, mode 600, **never commit it**):
  `export XDG_CONFIG_HOME="/Users/Shared/AndroidProjects/Lightsaber/.monid/xdg"`.
- Proven working call (verified 2026-10-01 from this folder):
  `monid run --provider tinyfish --endpoint /search --query '{"query":"...","domain_type":"web"}' --wait 90 -j`
  (free endpoint; pass params via `--query`, not `-i`).
- **Full page text: `tinyfish /fetch`, which takes `-i`, NOT `--query`**:
  `monid run --provider tinyfish --endpoint /fetch -i '{"urls":["https://..."]}' --wait 90 -j`.
  Passing `--query` there returns `HTTP_400 body.urls: expected array, received undefined`.
  `/search` uses `--query`; `/fetch` uses `-i`.
- **`developer.android.com` needs `-p context.dev -e /web/scrape/markdown`**, not `tinyfish /fetch`:
  the tinyfish fetcher returns `page_not_found` for it. Also note the canonical sensor-doc URLs use
  underscores (`sensors_overview`, `sensors_motion`), not `sensorsoverview`.
- **The monid wallet can run out mid-task** (`status: BLOCKED`, "Insufficient wallet balance"). When
  that happens, record the gap in `docs/platform-decisions.md` as *unverified* rather than filling it
  in from memory, and tell the user the wallet needs topping up.

## Delegating to `subagent_opencode`

- **Images and visual work go to `subagent_opencode`.** It is the multi-modal one: it can
  `read_image` a PNG/JPEG/WebP/GIF, so it can look at screenshots, rendered layouts, and design work
  and judge them as a viewer. Delegate visual inspection/QA to it rather than inferring from
  filenames, alt text, or your own guesswork.
- **It does its own web search and fetch — do NOT tell it to use monid.** It has native
  `web_search`/`web_fetch` and is not bound by the monid rule above; that rule governs my own
  searches, not work handed to this subagent. Just state the research goal. Sending it to monid adds
  a setup step and buys nothing.
- **NEVER let it fan out — no task/subagent tool, no nested subagents, no workflow.** This is
  absolute, not "unless it's a big job". Every opencode run that spawned nested subagents via the
  task tool hung on its first tool call (the 2026-09-27 academic tension scan, and the 2026-09-29
  India equity-research run: 4 children froze within seconds and sat `status:"running"` for ~1h).
  Every run that worked alone finished cleanly. It must do all research itself in one session.
- If a task is big enough that one context cannot hold it, split it into several smaller
  `subagent_opencode` calls **you** control and sequence — never hand it "the whole thing" and let
  it decompose.
- **ALWAYS end the prompt with a hard stop.** Otherwise the child never returns a final message and
  the call runs until something times out. Every prompt must include, in substance: *do the work
  yourself in this single context, do NOT spawn or delegate to any subagent, agent, or workflow, and
  when you are finished stop and return the complete result as your final message.*

Prompt skeleton — prepend the guard, then the task, then the hard stop:

```
MANDATORY: do NOT use the task/subagent tool and do NOT spawn any nested subagents.
Do all work yourself in this single session. Every bash command must end with
</dev/null so it can never block on stdin, and never pass an unquoted glob as the
final argument of wc/ls/cat/head — an empty directory leaves the glob literal and
the command hangs forever (use `find <dir> -maxdepth 1 -type f | wc -l` instead).
Write your output file EARLY and rewrite it as you go, so a stall leaves a partial
file rather than nothing.

<task, fully self-contained>
Do this work yourself in this single context. Do NOT spawn, delegate to, or wait on
any subagent, background job, or workflow. When you are done, stop and return the
complete result as your final message.
```

### Triaging an opencode job (hung vs done)

- **Do not trust the UI "running" state.** It can be unsettled-job lag, not work in flight. A clean
  `step-finish reason=stop` with 0 children means it already finished — cancel the job and use the
  report it produced; do not wait.
- **Verify progress against the opencode DB, not the UI.** `idle_s` in the tens/low-hundreds =
  working. `idle_s` in the thousands with a tool part stuck on `status:"running"` = hung.
  `children>0` means it fanned out and is heading for the hang.

```
DB=/Users/Mitesh Gada/.dsh/opencode-data/opencode/opencode.db
sqlite3 -header -column "$DB" "
SELECT substr(s.id,1,14) sess, COALESCE(s.title,'?') title,
       datetime(MAX(p.time_created)/1000,'unixepoch','localtime') last,
       CAST((strftime('%s','now')-MAX(p.time_created)/1000) AS INT) idle_s,
       COUNT(*) parts, COUNT(c.id) children
FROM part p JOIN session s ON s.id=p.session_id
LEFT JOIN session c ON c.parent_id=s.id
GROUP BY s.id HAVING idle_s<3600 ORDER BY MAX(p.time_created) DESC;"
```

- **To stop a hung job: `job_list` → `job_kill` the JOB only. NEVER kill the shared opencode ACP
  process** — it is shared across all sessions, so killing it takes down every other opencode run.

## Ponytail — lazy senior dev mode (installed, applies to every project here)

You are a lazy senior developer. Lazy means efficient, not careless. The best code is the code never
written.

Before writing any code, stop at the first rung that holds:

1. Does this need to be built at all? (YAGNI)
2. Does it already exist in this codebase? Reuse the helper, util, or pattern that's already here,
   don't re-write it.
3. Does the standard library already do this? Use it.
4. Does a native platform feature cover it? Use it.
5. Does an already-installed dependency solve it? Use it.
6. Can this be one line? Make it one line.
7. Only then: write the minimum code that works.

The ladder runs after you understand the problem, not instead of it: read the task and the code it
touches, trace the real flow end to end, then climb.

Bug fix = root cause, not symptom: a report names a symptom. Grep every caller of the function you
touch and fix the shared function once — one guard there is a smaller diff than one per caller, and
patching only the path the ticket names leaves a sibling caller still broken.

Rules:

- No abstractions that weren't explicitly requested.
- No new dependency if it can be avoided.
- No boilerplate nobody asked for.
- Deletion over addition. Boring over clever. Fewest files possible.
- Shortest working diff wins, but only once you understand the problem. The smallest change in the
  wrong place isn't lazy, it's a second bug.
- Question complex requests: "Do you actually need X, or does Y cover it?"
- Pick the edge-case-correct option when two stdlib approaches are the same size, lazy means less
  code, not the flimsier algorithm.
- Mark deliberate simplifications that cut a real corner with a known ceiling (global lock, O(n²)
  scan, naive heuristic) with a `ponytail:` comment naming the ceiling and upgrade path.

Not lazy about: understanding the problem (read it fully and trace the real flow before picking a
rung, a small diff you don't understand is just laziness dressed up as efficiency), input validation
at trust boundaries, error handling that prevents data loss, security, accessibility, the calibration
real hardware needs (the platform is never the spec ideal, a clock drifts, a sensor reads off),
anything explicitly requested. Lazy code without its check is unfinished: non-trivial logic leaves
ONE runnable check behind, the smallest thing that fails if the logic breaks (an assert-based
demo/self-check or one small test file; no frameworks, no fixtures). Trivial one-liners need no test.

Canonical install: `.tools/ponytail` (a symlink to
`/Users/Mitesh Gada/Documents/Projects/.tools/ponytail`; update that repo with `git pull`). It is the
source of the six `/ponytail*` skills — review, audit, debt, gain, help — and of this ruleset. When
asked to review a diff or repo for over-engineering, follow `skills/ponytail-review/SKILL.md` and
`skills/ponytail-audit/SKILL.md` there.