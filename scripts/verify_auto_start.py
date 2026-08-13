#!/usr/bin/env python3
"""End-to-end simulation of the Orion auto-start / auto-launch state machine.

Mirrors the Kotlin implementation in:
  data/recovery/BootStateStore.kt
  data/recovery/RecoveryThrottle.kt
  data/recovery/AutoStartCoordinator.kt
  data/recovery/PlayerRuntimeConfig.kt
  receiver/BootReceiver.kt
  service/PlayerForegroundService.kt (boot retry + watchdog)
  data/recovery/CrashRecovery.kt
  ui/playback/PlaybackViewModel.startPlayback (cache-first startup)

This proves the *logic*: boot de-duplication, direct-boot deferral, launch retry with
backoff, crash-loop and watchdog throttling, and cache-first offline startup.
It cannot prove that a given OEM permits a background activity start — only a physical
device can, see docs/AUTO_START_PROVISIONING.md.

Run: python3 scripts/verify_auto_start.py
"""

from __future__ import annotations

import sys

# ── Constants mirrored from PlayerRuntimeConfig / RecoveryThrottle / BootStateStore ──

BOOT_LAUNCH_BACKOFF_MS = [5_000, 10_000, 20_000, 40_000, 60_000, 120_000]
WATCHDOG_INTERVAL_MS = 30_000
BOOT_DEDUPE_WINDOW_MS = 60_000
BASE_BACKOFF_MS = 15_000
MAX_BACKOFF_MS = 900_000
MAX_SHIFT = 6
QUIET_RESET_MS = 900_000
MIN_DISPATCH_GAP_MS = 3_000

ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"
ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
BOOT_ACTIONS = {ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT,
                "com.htc.intent.action.QUICKBOOT_POWERON", "android.intent.action.REBOOT"}


class BootStateStore:
    """Device-protected storage. Survives process death; readable before unlock."""

    def __init__(self) -> None:
        self.last_boot_at = 0
        self.last_boot_uptime = -1
        self.last_boot_action = None
        self.last_locked_boot_action = None
        self.boot_launch_pending = False
        self.boot_launch_attempts = 0
        self.player_running = False
        self.player_running_at = 0
        self.attempts = {}
        self.attempt_at = {}
        self.observed_at = {}

    def should_handle_boot(self, now, uptime):
        if self.last_boot_at <= 0 or self.last_boot_uptime < 0:
            return True
        wall_delta = now - self.last_boot_at
        uptime_delta = uptime - self.last_boot_uptime
        same_boot = (0 <= uptime_delta < BOOT_DEDUPE_WINDOW_MS
                     and 0 <= wall_delta < BOOT_DEDUPE_WINDOW_MS)
        return not same_boot

    def record_handled_boot(self, action, now, uptime):
        self.last_boot_action, self.last_boot_at = action, now
        self.last_boot_uptime = uptime

    def record_locked_boot(self, action, now):
        self.last_locked_boot_action = action

    def mark_boot_launch_pending(self):
        self.boot_launch_pending, self.boot_launch_attempts = True, 0

    def record_boot_launch_attempt(self):
        self.boot_launch_attempts += 1
        return self.boot_launch_attempts

    def clear_boot_launch_pending(self):
        self.boot_launch_pending, self.boot_launch_attempts = False, 0

    def consume_unclean_shutdown(self, now):
        if not self.player_running:
            return None
        self.player_running = False
        return max(0, now - self.player_running_at)

    def mark_player_running(self, now):
        self.player_running, self.player_running_at = True, now

    def mark_player_stopped(self):
        self.player_running = False


class RecoveryThrottle:
    KEY_CRASH = "crash_relaunch"
    KEY_ACTIVITY_RESTART = "watchdog_activity"
    KEY_PLAYBACK_RESTART = "watchdog_playback"

    def __init__(self, store: BootStateStore) -> None:
        self.store = store

    @staticmethod
    def _backoff_for(attempts):
        if attempts <= 0:
            return 0
        return min(BASE_BACKOFF_MS << min(attempts - 1, MAX_SHIFT), MAX_BACKOFF_MS)

    @staticmethod
    def _elapsed_since(timestamp, now):
        elapsed = now - timestamp
        return float("inf") if elapsed < 0 else elapsed

    def evaluate(self, key, now):
        last_observed = self.store.observed_at.get(key, 0)
        healed = last_observed <= 0 or self._elapsed_since(last_observed, now) >= QUIET_RESET_MS
        self.store.observed_at[key] = now

        if healed:
            self.store.attempts[key] = 1
            self.store.attempt_at[key] = now
            return (True, 1, self._backoff_for(1))

        attempts = self.store.attempts.get(key, 0)
        last_at = self.store.attempt_at.get(key, 0)
        required_gap = self._backoff_for(attempts)
        elapsed = float("inf") if last_at <= 0 else self._elapsed_since(last_at, now)
        if elapsed < required_gap:
            return (False, attempts, required_gap - elapsed)

        attempt = attempts + 1
        self.store.attempts[key] = attempt
        self.store.attempt_at[key] = now
        return (True, attempt, self._backoff_for(attempt))

    def reset(self, key):
        self.store.attempts.pop(key, None)
        self.store.attempt_at.pop(key, None)
        self.store.observed_at.pop(key, None)


class Device:
    """One simulated Android device + Orion process."""

    def __init__(self, user_unlocked=True, allow_activity_start=True, online=True,
                 has_cache=True, lock_task_permitted=False):
        # Wall clock starts at a realistic epoch; uptime resets on every reboot.
        self.now = 1_700_000_000_000
        self.uptime = 20_000
        self.log = []
        self.store = BootStateStore()
        self.throttle = RecoveryThrottle(self.store)

        self.user_unlocked = user_unlocked
        self.allow_activity_start = allow_activity_start
        self.online = online
        self.has_cache = has_cache
        self.lock_task_permitted = lock_task_permitted

        self.process_alive = False
        self.activity_visible = False
        self.service_running = False
        self.service_instances = 0
        self.activity_instances = 0
        self.playback_engines = 0
        self.pop_sessions = 0
        self.last_dispatch_at = -MIN_DISPATCH_GAP_MS
        self.pending_boot_timer = None

    # ── helpers ────────────────────────────────────────────────────
    def emit(self, event):
        self.log.append(event)

    def events(self, name):
        return [e for e in self.log if e == name or e.startswith(name + " ")]

    def count(self, name):
        return len(self.events(name))

    # ── AutoStartCoordinator ───────────────────────────────────────
    def _launch(self, source, attempt=1):
        if self.activity_visible:
            self.emit("PLAYER_ALREADY_RUNNING")
            return True
        if self.now - self.last_dispatch_at < MIN_DISPATCH_GAP_MS:
            return True
        self.last_dispatch_at = self.now
        self.emit(f"PLAYER_AUTO_LAUNCH_START attempt={attempt}")
        if self.allow_activity_start:
            self._start_activity(source)
        # A blocked background start fails silently: no exception, no activity.
        return True

    def _start_activity(self, source):
        self._ensure_process()
        if self.activity_instances == 0:  # singleTask: one instance, ever
            self.activity_instances = 1
        self.activity_visible = True
        self.emit("PLAYER_AUTO_LAUNCH_SUCCESS")
        self.store.clear_boot_launch_pending()
        self.store.mark_player_running(self.now)
        self._start_playback(source)
        self._apply_kiosk()

    def _apply_kiosk(self):
        if self.lock_task_permitted:
            self.emit("KIOSK_MODE_ENTERED")
        else:
            self.emit("KIOSK_MODE_NOT_AVAILABLE")

    # ── Application / service ──────────────────────────────────────
    def _ensure_process(self):
        if self.process_alive:
            return
        self.process_alive = True
        unclean = self.store.consume_unclean_shutdown(self.now)
        if unclean is not None and not self.store.boot_launch_pending:
            self.emit("PLAYER_PROCESS_RECOVERY")
        self._start_service()

    def _start_service(self):
        self._ensure_process_flag()
        if not self.service_running:
            self.service_running = True
            self.service_instances += 1  # one FGS instance, no matter how many starts
        self._schedule_boot_retry()

    def _ensure_process_flag(self):
        if not self.process_alive:
            self.process_alive = True

    def _schedule_boot_retry(self):
        if not self.store.boot_launch_pending:
            self.pending_boot_timer = None
            return
        idx = min(self.store.boot_launch_attempts, len(BOOT_LAUNCH_BACKOFF_MS) - 1)
        self.pending_boot_timer = self.now + BOOT_LAUNCH_BACKOFF_MS[idx]

    def _run_boot_retry(self):
        if not self.store.boot_launch_pending:
            self.pending_boot_timer = None
            return
        if self.activity_visible:
            self.store.clear_boot_launch_pending()
            self.pending_boot_timer = None
            return
        attempt = self.store.record_boot_launch_attempt()
        if attempt > len(BOOT_LAUNCH_BACKOFF_MS):
            self.store.clear_boot_launch_pending()
            self.pending_boot_timer = None
            self.emit("PLAYER_AUTO_LAUNCH_BLOCKED")
            return
        self._launch("boot.retry", attempt=attempt + 1)
        self.pending_boot_timer = self.now + BOOT_LAUNCH_BACKOFF_MS[attempt - 1]

    # ── PlaybackViewModel.startPlayback ────────────────────────────
    def _start_playback(self, source):
        if source.startswith("boot."):
            self.emit("BOOT_RECOVERY")
        self.emit("PLAYER_INIT_START")
        if self.playback_engines == 0:
            self.playback_engines = 1  # single authoritative controller
        if self.has_cache:
            self.emit("CACHE_PLAYBACK_START")
            self.pop_sessions += 1
        else:
            self.emit("CACHE_PLAYBACK_UNAVAILABLE")
        self.emit("CMS_SYNC_START")
        if self.online:
            self.emit("CMS_SYNC_SUCCESS")
        else:
            self.emit("CMS_SYNC_FAILED")

    # ── BootReceiver ───────────────────────────────────────────────
    def broadcast(self, action):
        if action == ACTION_LOCKED_BOOT_COMPLETED:
            self.emit("BOOT_RECEIVED deferred")
            self.store.record_locked_boot(action, self.now)
            return
        if action not in BOOT_ACTIONS:
            return
        self.emit("BOOT_RECEIVED")
        if not self.user_unlocked:
            self.store.record_locked_boot(action, self.now)
            return
        if not self.store.should_handle_boot(self.now, self.uptime):
            self.emit("BOOT_DUPLICATE_IGNORED")
            return
        self.store.record_handled_boot(action, self.now, self.uptime)
        self.store.mark_boot_launch_pending()
        self._start_service()
        self._launch(f"boot.{action}")
        self._schedule_boot_retry()

    def unlock(self):
        self.user_unlocked = True

    # ── Watchdog ───────────────────────────────────────────────────
    def watchdog_tick(self, activity_alive=True, playback_stuck=False):
        if not activity_alive:
            allowed, attempt, retry_in = self.throttle.evaluate(
                RecoveryThrottle.KEY_ACTIVITY_RESTART, self.now)
            if allowed:
                self.emit("WATCHDOG_RECOVERY activity")
                self._launch("watchdog.activity_restart")
            else:
                self.emit("WATCHDOG_RECOVERY_DEFERRED activity")
            return
        self.throttle.reset(RecoveryThrottle.KEY_ACTIVITY_RESTART)

        if playback_stuck:
            allowed, attempt, retry_in = self.throttle.evaluate(
                RecoveryThrottle.KEY_PLAYBACK_RESTART, self.now)
            if allowed:
                self.emit("WATCHDOG_RECOVERY playback")
                # In-process restart: reuses the one playback controller.
            else:
                self.emit("WATCHDOG_RECOVERY_DEFERRED playback")
        else:
            self.throttle.reset(RecoveryThrottle.KEY_PLAYBACK_RESTART)

    # ── Crash / kill ───────────────────────────────────────────────
    def crash(self):
        self.emit("CRASH")
        allowed, attempt, retry_in = self.throttle.evaluate(RecoveryThrottle.KEY_CRASH, self.now)
        relaunch_at = None
        if allowed:
            relaunch_at = self.now + 1_000
        else:
            self.emit("CRASH_LOOP_BACKOFF")
            relaunch_at = self.now + retry_in
        self._kill_process()
        return relaunch_at

    def kill_process(self):
        self.emit("PROCESS_KILLED")
        self._kill_process()

    def _kill_process(self):
        self.process_alive = False
        self.activity_visible = False
        self.activity_instances = 0
        self.service_running = False
        self.playback_engines = 0
        self.pending_boot_timer = None
        self.last_dispatch_at = -MIN_DISPATCH_GAP_MS

    def restart_service_sticky(self):
        """Android restarting a START_STICKY service after the process died."""
        self._ensure_process()

    def advance(self, ms):
        target = self.now + ms
        while self.pending_boot_timer is not None and self.pending_boot_timer <= target:
            self.uptime += self.pending_boot_timer - self.now
            self.now = self.pending_boot_timer
            self._run_boot_retry()
        self.uptime += target - self.now
        self.now = target

    def reboot(self, wall_clock_ms=None):
        """Power cycle: uptime restarts, and a box without an RTC may come up in 1970."""
        self._kill_process()
        self.uptime = 15_000
        if wall_clock_ms is not None:
            self.now = wall_clock_ms


# ── Test harness ───────────────────────────────────────────────────

RESULTS = []


def check(name, condition, detail=""):
    RESULTS.append((name, bool(condition), detail))
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {name}" + (f" — {detail}" if detail and not condition else ""))


def test(title):
    print(f"\n{title}")


# TEST 1 — normal reboot, online, cached content
test("TEST 1: Reboot with internet — cache plays first, then CMS sync")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
order = [e.split()[0] for e in d.log]
check("BOOT_RECEIVED logged", "BOOT_RECEIVED" in order)
check("PLAYER_AUTO_LAUNCH_START logged", "PLAYER_AUTO_LAUNCH_START" in order)
check("PLAYER_AUTO_LAUNCH_SUCCESS logged", "PLAYER_AUTO_LAUNCH_SUCCESS" in order)
check("CACHE_PLAYBACK_START logged", "CACHE_PLAYBACK_START" in order)
check("cache plays before CMS sync",
      order.index("CACHE_PLAYBACK_START") < order.index("CMS_SYNC_START"))
check("CMS_SYNC_SUCCESS after sync start",
      order.index("CMS_SYNC_SUCCESS") > order.index("CMS_SYNC_START"))
check("boot launch marker cleared", not d.store.boot_launch_pending)

# TEST 2 — power cycle offline
test("TEST 2: Power-on with NO internet — cached content still plays")
d = Device(online=False)
d.broadcast(ACTION_QUICKBOOT_POWERON := ACTION_QUICKBOOT)
check("cached playback started", d.count("CACHE_PLAYBACK_START") == 1)
check("sync failure recorded", d.count("CMS_SYNC_FAILED") == 1)
check("playback still running", d.playback_engines == 1)
check("no blocked-launch error", d.count("PLAYER_AUTO_LAUNCH_BLOCKED") == 0)

# TEST 3 — offline and no cache at all (first ever boot)
test("TEST 3: Boot offline with an empty cache")
d = Device(online=False, has_cache=False)
d.broadcast(ACTION_BOOT_COMPLETED)
check("cache unavailability reported", d.count("CACHE_PLAYBACK_UNAVAILABLE") == 1)
check("player still launched", d.activity_visible)
check("no PoP recorded without content", d.pop_sessions == 0)

# TEST 4 — duplicate boot broadcasts
test("TEST 4: Duplicate boot broadcasts — one launch only")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
d.advance(2_000)
d.broadcast(ACTION_QUICKBOOT)
d.advance(2_000)
d.broadcast("android.intent.action.REBOOT")
check("boot handled once", d.count("BOOT_DUPLICATE_IGNORED") == 2)
check("single activity instance", d.activity_instances == 1)
check("single service instance", d.service_instances == 1)
check("single playback engine", d.playback_engines == 1)
check("one PoP session", d.pop_sessions == 1)
check("cache playback started once", d.count("CACHE_PLAYBACK_START") == 1)

# TEST 5 — a genuinely separate boot later is handled again
test("TEST 5: A second real reboot is not swallowed by de-duplication")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
d.advance(BOOT_DEDUPE_WINDOW_MS + 1)
d.reboot()
d.broadcast(ACTION_BOOT_COMPLETED)
check("second boot handled", d.count("BOOT_RECEIVED") == 2)
check("no duplicate-ignore on real reboot", d.count("BOOT_DUPLICATE_IGNORED") == 0)
check("player relaunched", d.activity_visible)

# TEST 6 — direct boot
test("TEST 6: Direct boot — locked boot defers, BOOT_COMPLETED launches")
d = Device(user_unlocked=False)
d.broadcast(ACTION_LOCKED_BOOT_COMPLETED)
check("locked boot deferred", d.log == ["BOOT_RECEIVED deferred"])
check("no process started before unlock", not d.process_alive)
check("no credential storage touched", not d.store.boot_launch_pending)
d.advance(4_000)
d.unlock()
d.broadcast(ACTION_BOOT_COMPLETED)
check("player launched after unlock", d.activity_visible)
check("locked boot did not consume the dedupe window",
      d.count("BOOT_DUPLICATE_IGNORED") == 0)
check("cache playback started after unlock", d.count("CACHE_PLAYBACK_START") == 1)

# TEST 7 — OEM blocks background activity starts
test("TEST 7: OEM blocks background activity start — retries then reports blocked")
d = Device(allow_activity_start=False)
d.broadcast(ACTION_BOOT_COMPLETED)
d.advance(10 * 60_000)
check("retried the full backoff schedule",
      d.count("PLAYER_AUTO_LAUNCH_START") == len(BOOT_LAUNCH_BACKOFF_MS) + 1,
      f"got {d.count('PLAYER_AUTO_LAUNCH_START')}")
check("never falsely claimed success", d.count("PLAYER_AUTO_LAUNCH_SUCCESS") == 0)
check("blocked state reported once", d.count("PLAYER_AUTO_LAUNCH_BLOCKED") == 1)
check("retry loop terminated", d.pending_boot_timer is None)
check("service kept running for sync/cache", d.service_running)

# TEST 8 — slow boot: display ready only on the 3rd retry
test("TEST 8: Slow boot — launch succeeds on a later retry, then retries stop")
d = Device(allow_activity_start=False)
d.broadcast(ACTION_BOOT_COMPLETED)
d.advance(16_000)          # two retries consumed
d.allow_activity_start = True
d.advance(10 * 60_000)
check("launch eventually succeeded", d.count("PLAYER_AUTO_LAUNCH_SUCCESS") == 1)
check("no blocked error after success", d.count("PLAYER_AUTO_LAUNCH_BLOCKED") == 0)
check("retries stopped after success", d.pending_boot_timer is None)
check("only one playback engine", d.playback_engines == 1)
check("only one PoP session", d.pop_sessions == 1)

# TEST 9 — already running
test("TEST 9: Boot event while the player is already on screen")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
d.advance(BOOT_DEDUPE_WINDOW_MS + 1)
d.broadcast(ACTION_BOOT_COMPLETED)
check("already-running detected", d.count("PLAYER_ALREADY_RUNNING") == 1)
check("no duplicate activity", d.activity_instances == 1)
check("no duplicate playback engine", d.playback_engines == 1)
check("no duplicate PoP session", d.pop_sessions == 1)

# TEST 10 — process kill recovery
test("TEST 10: Process killed — recovers via sticky service and watchdog")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
d.advance(120_000)
d.kill_process()
d.restart_service_sticky()
check("process recovery reported", d.count("PLAYER_PROCESS_RECOVERY") == 1)
d.watchdog_tick(activity_alive=False)
check("watchdog relaunched the activity", d.count("WATCHDOG_RECOVERY activity") == 1)
check("player is back on screen", d.activity_visible)
check("cache-first restart", d.count("CACHE_PLAYBACK_START") == 2)
check("still a single playback engine", d.playback_engines == 1)

# TEST 11 — watchdog cannot loop
test("TEST 11: Watchdog restart loop protection")
d = Device(allow_activity_start=False)
d.broadcast(ACTION_BOOT_COMPLETED)
d.advance(10 * 60_000)
first_half = last_half = 0
for tick in range(120):  # one hour of 30s ticks with a permanently dead activity
    before = d.count("WATCHDOG_RECOVERY activity")
    d.watchdog_tick(activity_alive=False)
    fired = d.count("WATCHDOG_RECOVERY activity") - before
    if tick < 60:
        first_half += fired
    else:
        last_half += fired
    d.advance(WATCHDOG_INTERVAL_MS)
restarts = first_half + last_half
check("restarts are bounded", restarts <= 20, f"{restarts} restarts in 1h (unthrottled: 120)")
check("deferrals were logged", d.count("WATCHDOG_RECOVERY_DEFERRED activity") > 0)
check("backoff still allows recovery", restarts >= 5, f"only {restarts} restarts")
check("restart rate decays over time", last_half < first_half,
      f"first 30min={first_half}, last 30min={last_half}")

# TEST 12 — watchdog resets when healthy again
test("TEST 12: Watchdog backoff resets after the player recovers")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
for _ in range(4):
    d.watchdog_tick(activity_alive=False)
    d.advance(WATCHDOG_INTERVAL_MS)
d.watchdog_tick(activity_alive=True)
d.advance(WATCHDOG_INTERVAL_MS)
before = d.count("WATCHDOG_RECOVERY activity")
d.watchdog_tick(activity_alive=False)
check("recovery is immediate after a healthy period",
      d.count("WATCHDOG_RECOVERY activity") == before + 1)

# TEST 13 — playback freeze recovery does not restart the app
test("TEST 13: Playback freeze recovers in-process, with backoff")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
launches_before = d.count("PLAYER_AUTO_LAUNCH_START")
for _ in range(10):
    d.watchdog_tick(activity_alive=True, playback_stuck=True)
    d.advance(WATCHDOG_INTERVAL_MS)
check("playback restarts happened", d.count("WATCHDOG_RECOVERY playback") > 0)
check("app was not relaunched for a freeze",
      d.count("PLAYER_AUTO_LAUNCH_START") == launches_before)
check("freeze restarts are throttled",
      d.count("WATCHDOG_RECOVERY_DEFERRED playback") > 0)

# TEST 14 — crash loop protection
test("TEST 14: Startup crash loop is broken by backoff")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
relaunch_gaps = []
start = d.now
for _ in range(8):
    at = d.now
    next_at = d.crash()
    relaunch_gaps.append(next_at - at)
    d.advance(max(1_000, next_at - at))
    d.restart_service_sticky()
    d._launch("crash.relaunch")
throttled = [g for g in relaunch_gaps if g > 1_000]
check("crash loop backoff engaged", d.count("CRASH_LOOP_BACKOFF") > 0)
check("each throttled relaunch waits longer", throttled == sorted(set(throttled)),
      f"{throttled}")
check("8 crashes take much longer than 8 seconds", d.now - start > 200_000,
      f"{d.now - start}ms")
check("device stays recoverable", max(relaunch_gaps) <= MAX_BACKOFF_MS)
check("cached content preserved across crashes", d.count("CACHE_PLAYBACK_START") > 0)

# TEST 15 — isolated crash hours later recovers immediately
test("TEST 15: An isolated crash after a stable period recovers immediately")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
d.crash()
d.advance(QUIET_RESET_MS + 1)
d.restart_service_sticky()
crash_backoffs_before = d.count("CRASH_LOOP_BACKOFF")
d.crash()
check("no backoff after a long stable run",
      d.count("CRASH_LOOP_BACKOFF") == crash_backoffs_before)

# TEST 16 — kiosk on a non-provisioned device
test("TEST 16: Kiosk mode on an unprovisioned device degrades gracefully")
d = Device(lock_task_permitted=False)
d.broadcast(ACTION_BOOT_COMPLETED)
check("kiosk unavailability logged", d.count("KIOSK_MODE_NOT_AVAILABLE") == 1)
check("no lock task entered", d.count("KIOSK_MODE_ENTERED") == 0)
check("playback unaffected", d.count("CACHE_PLAYBACK_START") == 1)

# TEST 17 — kiosk on a provisioned dedicated device
test("TEST 17: Kiosk mode on a device-owner provisioned device")
d = Device(lock_task_permitted=True)
d.broadcast(ACTION_BOOT_COMPLETED)
check("lock task entered", d.count("KIOSK_MODE_ENTERED") == 1)
check("no false unavailability", d.count("KIOSK_MODE_NOT_AVAILABLE") == 0)

# TEST 18 — reboot while offline, repeated
test("TEST 18: Repeated offline reboots never duplicate playback or PoP")
d = Device(online=False)
for i in range(5):
    d.broadcast(ACTION_BOOT_COMPLETED)
    d.advance(BOOT_DEDUPE_WINDOW_MS + 1)
    d.reboot()
check("one launch per boot", d.count("PLAYER_AUTO_LAUNCH_SUCCESS") == 5)
check("one cache start per boot", d.count("CACHE_PLAYBACK_START") == 5)
check("no duplicate instances after final boot", d.activity_instances == 0)
check("never reported blocked", d.count("PLAYER_AUTO_LAUNCH_BLOCKED") == 0)

# TEST 19 — the full documented boot log sequence
test("TEST 19: The documented boot log sequence appears in order")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
names = [e.split()[0] for e in d.log]
expected = ["BOOT_RECEIVED", "PLAYER_AUTO_LAUNCH_START", "PLAYER_AUTO_LAUNCH_SUCCESS",
            "BOOT_RECOVERY", "PLAYER_INIT_START", "CACHE_PLAYBACK_START",
            "CMS_SYNC_START", "CMS_SYNC_SUCCESS"]
positions = [names.index(e) if e in names else -1 for e in expected]
check("all documented events present", all(p >= 0 for p in positions),
      f"missing: {[e for e, p in zip(expected, positions) if p < 0]}")
check("events are in the documented order", positions == sorted(positions),
      f"{list(zip(expected, positions))}")

# TEST 20 — box with no RTC battery: the clock jumps backwards across a reboot
test("TEST 20: Reboot where the clock resets to 1970 — boot is still handled")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
check("first boot handled", d.count("PLAYER_AUTO_LAUNCH_SUCCESS") == 1)
d.advance(6 * 60 * 60_000)          # runs for six hours, clock correct
d.reboot(wall_clock_ms=1_000)       # RTC lost: comes up in 1970
d.broadcast(ACTION_BOOT_COMPLETED)
check("boot not discarded as a duplicate", d.count("BOOT_DUPLICATE_IGNORED") == 0)
check("player relaunched after the clock reset", d.activity_visible)
check("cached content played again", d.count("CACHE_PLAYBACK_START") == 2)

# TEST 21 — reboot lands at the same uptime as the previous boot broadcast
test("TEST 21: Reboot at a near-identical uptime is not mistaken for a duplicate")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)   # handled at uptime 20s
d.advance(3 * 60 * 60_000)
d.reboot()                           # uptime restarts at 15s
d.advance(6_000)                     # uptime now 21s, ~1s from the stored value
d.broadcast(ACTION_BOOT_COMPLETED)
check("reboot handled despite matching uptime", d.count("BOOT_DUPLICATE_IGNORED") == 0)
check("player relaunched", d.activity_visible)

# TEST 22 — NTP correction shortly after boot must not spawn a second launch
test("TEST 22: Clock jumps forward right after boot — no second launch")
d = Device()
d.broadcast(ACTION_BOOT_COMPLETED)
d.now += 45 * 60_000                 # NTP corrects the clock by 45 minutes
d.broadcast(ACTION_QUICKBOOT)        # late OEM quick-boot broadcast, same boot
check("late duplicate still launches nothing new", d.activity_instances == 1)
check("no duplicate playback engine", d.playback_engines == 1)
check("no duplicate PoP session", d.pop_sessions == 1)

# ── Summary ────────────────────────────────────────────────────────
passed = sum(1 for _, ok, _ in RESULTS if ok)
total = len(RESULTS)
print(f"\n{'=' * 62}")
print(f"{passed}/{total} checks passed")
if passed != total:
    print("\nFAILURES:")
    for name, ok, detail in RESULTS:
        if not ok:
            print(f"  - {name} {detail}")
print("=" * 62)
sys.exit(0 if passed == total else 1)
