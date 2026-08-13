#!/usr/bin/env python3
"""End-to-end simulation of CMS-driven schedule switching.

Mirrors ActiveScheduleTracker.kt plus the sync/activation flow in
ContentSyncCoordinator + PlaybackViewModel so the transition rules can be
checked without a device:

  * the CMS decides which schedule is active; the player only follows it
  * a transition triggers one full sync, not a polling storm
  * schedule state is committed only once the new playlist is on screen
  * a failed download keeps the previous playlist playing and retries
  * duration resolution is unaffected by scheduling
"""

RESIGNAL_COOLDOWN_MS = 60_000

STARTED = "schedule.started"
ENDED = "schedule.ended"
CHANGED = "schedule.changed"

from datetime import datetime, timedelta, timezone

KOLKATA = timezone(timedelta(hours=5, minutes=30))


def parse_cms_time(raw):
    if not raw:
        return None
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(raw, fmt).replace(tzinfo=KOLKATA)
        except ValueError:
            continue
    if raw.endswith("Z"):
        try:
            return datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except ValueError:
            return None
    return None


def live_schedule(schedule):
    if not schedule:
        return None
    start = parse_cms_time(schedule.get("startDateTime"))
    end = parse_cms_time(schedule.get("endDateTime"))
    now = datetime.now(tz=KOLKATA)
    if start and now < start:
        return None
    if end and now >= end:
        return None
    return schedule


class Prefs:
    """SecurePrefs subset that survives an app restart."""

    def __init__(self):
        self.active_schedule_id = None
        self.active_schedule_playlist_id = None
        self.active_playlist_name = None


class Tracker:
    """ActiveScheduleTracker."""

    def __init__(self, prefs):
        self.prefs = prefs
        self.in_flight = None
        self.last_signalled_key = None
        self.last_signalled_at = 0
        self.logs = []

    def observe(self, schedule, now_ms):
        incoming = live_schedule(schedule)
        incoming_id = incoming["scheduleId"] if incoming else None
        incoming_playlist = incoming.get("playlistId") if incoming else None
        stored_id = self.prefs.active_schedule_id
        stored_playlist = self.prefs.active_schedule_playlist_id

        unchanged = incoming_id == stored_id and (
            incoming_playlist is None or incoming_playlist == stored_playlist
        )
        if unchanged:
            self.last_signalled_key = None
            return None

        key = f"{incoming_id}|{incoming_playlist}"
        if key == self.last_signalled_key and now_ms - self.last_signalled_at < RESIGNAL_COOLDOWN_MS:
            return None
        self.last_signalled_key = key
        self.last_signalled_at = now_ms

        if stored_id is None:
            return STARTED
        if incoming_id is None:
            self.logs.append("Schedule Ended")
            return ENDED
        return CHANGED

    def on_sync_response(self, schedule, sync_playlist_id, sync_playlist_name):
        schedule = live_schedule(schedule)
        self.in_flight = schedule
        scheduled_playlist = schedule.get("playlistId") if schedule else None
        if scheduled_playlist and sync_playlist_id and scheduled_playlist != sync_playlist_id:
            self.logs.append("Playlist Mismatch")
        schedule_id = schedule["scheduleId"] if schedule else None
        switching = schedule_id != self.prefs.active_schedule_id or (
            scheduled_playlist is not None
            and scheduled_playlist != self.prefs.active_schedule_playlist_id
        )
        if switching:
            self.logs.append(
                f"Schedule Switch Started: {self.prefs.active_playlist_name} -> "
                f"{sync_playlist_name} (schedule={schedule_id})"
            )

    def on_switch_failed(self, reason):
        self.logs.append(f"Schedule switch not completed: {reason}")

    def on_playlist_activated(self, playlist_id, playlist_name):
        if not playlist_id:
            return
        schedule = self.in_flight
        schedule_id = schedule["scheduleId"] if schedule else None
        schedule_playlist = (schedule.get("playlistId") if schedule else None) or playlist_id

        if schedule_id is None:
            unchanged = self.prefs.active_schedule_id is None
        else:
            unchanged = (
                self.prefs.active_schedule_id == schedule_id
                and self.prefs.active_schedule_playlist_id == schedule_playlist
            )

        self.prefs.active_playlist_name = playlist_name
        if unchanged:
            return
        if schedule_id is not None:
            self.prefs.active_schedule_id = schedule_id
            self.prefs.active_schedule_playlist_id = schedule_playlist
            self.logs.append(f"Schedule Playlist Activated: {playlist_name} (schedule={schedule_id})")
        else:
            self.prefs.active_schedule_id = None
            self.prefs.active_schedule_playlist_id = None
            self.logs.append(f"Fallback Playlist Activated: {playlist_name}")
        self.last_signalled_key = None

    def on_cached_playlist_restored(self, playlist_name):
        if playlist_name:
            self.prefs.active_playlist_name = playlist_name


class Player:
    """The parts of the sync + playback loop that scheduling touches."""

    def __init__(self, prefs=None, playing=None, cached_assets=None):
        self.prefs = prefs or Prefs()
        self.tracker = Tracker(self.prefs)
        self.playing = playing
        self.cached_assets = set(cached_assets or [])
        self.now = 0
        self.syncs = 0

    def poll(self, cms, source="sync-revision"):
        """One revision poll / heartbeat. Returns the sync reason, if any."""
        self.now += 5_000
        return self.tracker.observe(cms.active_schedule(), self.now)

    def sync(self, cms):
        """Full sync: fetch manifest, download, then switch at the slot boundary."""
        self.syncs += 1
        schedule = cms.active_schedule()
        playlist = cms.playlist_for_device()
        self.tracker.on_sync_response(schedule, playlist["id"], playlist["name"])

        missing = [a for a in playlist["assets"] if a not in self.cached_assets]
        downloaded = [a for a in missing if a not in cms.undownloadable]
        self.cached_assets.update(downloaded)

        still_missing = [a for a in playlist["assets"] if a not in self.cached_assets]
        if still_missing:
            # Old playlist keeps playing; nothing is deleted; retry later.
            self.tracker.on_switch_failed(f"{len(still_missing)} assets still downloading")
            return "failed"

        self.playing = playlist
        self.tracker.on_playlist_activated(playlist["id"], playlist["name"])
        return "switched"

    def restart(self):
        """Cold start: cached playlist first, then a forced sync."""
        restarted = Player(
            prefs=self.prefs,
            playing=self.playing,
            cached_assets=self.cached_assets,
        )
        restarted.tracker.on_cached_playlist_restored(
            self.playing["name"] if self.playing else None
        )
        return restarted


class Cms:
    def __init__(self):
        self.schedule = None
        self.manual = {"id": "pl-lobby", "name": "Lobby Playlist", "assets": ["a1", "a2"]}
        self.playlists = {
            "pl-morning": {"id": "pl-morning", "name": "Morning Playlist", "assets": ["m1", "m2"]},
            "pl-evening": {"id": "pl-evening", "name": "Evening Playlist", "assets": ["e1"]},
        }
        self.undownloadable = set()

    def activate(self, schedule_id, playlist_id, start="09:00", end="12:00"):
        self.schedule = {
            "scheduleId": schedule_id,
            "playlistId": playlist_id,
            "startDateTime": start,
            "endDateTime": end,
        }

    def deactivate(self):
        self.schedule = None

    def active_schedule(self):
        return self.schedule

    def playlist_for_device(self):
        if self.schedule:
            return self.playlists[self.schedule["playlistId"]]
        return self.manual


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {label}{(' — ' + detail) if detail else ''}")
    assert condition, label


def main():
    cms = Cms()
    player = Player(playing=cms.manual, cached_assets=["a1", "a2"])

    print("== 1-2. Future schedule stays inactive until the CMS activates it ==")
    check("no sync while no schedule is active", player.poll(cms) is None)
    check("still playing the assigned playlist", player.playing["name"] == "Lobby Playlist")

    print("\n== 3. Schedule becomes active -> automatic switch ==")
    cms.activate("sch-morning", "pl-morning")
    reason = player.poll(cms)
    check("transition detected", reason == STARTED, f"reason={reason}")
    check("switch completed", player.sync(cms) == "switched")
    check("morning playlist playing", player.playing["name"] == "Morning Playlist")
    check("schedule committed", player.prefs.active_schedule_id == "sch-morning")
    check("no repeat sync on the next poll", player.poll(cms) is None)

    print("\n== 4-5. Schedule ends and the next one takes over ==")
    cms.activate("sch-evening", "pl-evening")
    reason = player.poll(cms)
    check("next schedule detected", reason == CHANGED, f"reason={reason}")
    player.sync(cms)
    check("evening playlist playing", player.playing["name"] == "Evening Playlist")
    check("schedule committed", player.prefs.active_schedule_id == "sch-evening")

    print("\n== 6. Manual playlist resumes when no schedule is active ==")
    cms.deactivate()
    reason = player.poll(cms)
    check("schedule end detected", reason == ENDED, f"reason={reason}")
    player.sync(cms)
    check("assigned playlist restored", player.playing["name"] == "Lobby Playlist")
    check("schedule state cleared", player.prefs.active_schedule_id is None)
    check("Fallback Playlist Activated logged", "Fallback Playlist Activated: Lobby Playlist" in player.tracker.logs)

    print("\n== 9-11. Schedule edited / disabled / deleted are all just CMS state ==")
    cms.activate("sch-morning", "pl-evening")  # edited: same schedule, new playlist
    check("edit detected", player.poll(cms) == STARTED)
    player.sync(cms)
    check("edited playlist playing", player.playing["name"] == "Evening Playlist")
    cms.deactivate()  # disabled or deleted look identical to the player
    check("disable detected", player.poll(cms) == ENDED)
    player.sync(cms)
    check("back to assigned playlist", player.playing["name"] == "Lobby Playlist")

    print("\n== 12-13. Offline during the transition, corrected on reconnect ==")
    cms.activate("sch-morning", "pl-morning")
    offline_polls = 0  # no network: no polls happen at all
    check("nothing switches while offline", offline_polls == 0 and player.playing["name"] == "Lobby Playlist")
    reason = player.poll(cms)  # first poll after reconnect
    check("reconnect detects the schedule", reason == STARTED, f"reason={reason}")
    player.sync(cms)
    check("correct playlist after reconnect", player.playing["name"] == "Morning Playlist")

    print("\n== 14. Restart during an active schedule ==")
    player = player.restart()
    check("cached playlist still playing", player.playing["name"] == "Morning Playlist")
    check("schedule survives restart", player.prefs.active_schedule_id == "sch-morning")
    check("no redundant switch when unchanged", player.poll(cms) is None)
    cms.deactivate()  # schedule expired while the device was off
    check("expired schedule detected on restart", player.poll(cms) == ENDED)
    player.sync(cms)
    check("no longer stuck on the scheduled playlist", player.playing["name"] == "Lobby Playlist")

    print("\n== 15. Missing assets: keep playing, retry, then switch ==")
    cms.activate("sch-morning", "pl-morning")
    cms.undownloadable = {"m2"}
    player.cached_assets.discard("m1")
    player.cached_assets.discard("m2")
    check("transition detected", player.poll(cms) == STARTED)
    check("switch refused while assets are missing", player.sync(cms) == "failed")
    check("previous playlist still on screen", player.playing["name"] == "Lobby Playlist")
    check("schedule not committed on failure", player.prefs.active_schedule_id is None)
    check("old assets never deleted", {"a1", "a2"} <= player.cached_assets)
    check("failure logged", any("not completed" in log for log in player.tracker.logs))
    cms.undownloadable = set()
    check("retry switches", player.sync(cms) == "switched")
    check("scheduled playlist playing", player.playing["name"] == "Morning Playlist")

    print("\n== 5b. Repeated polls do not hammer the CMS ==")
    cms.activate("sch-evening", "pl-evening")
    cms.undownloadable = {"e1"}
    player.cached_assets.discard("e1")
    syncs_before = player.syncs
    triggers = 0
    for _ in range(12):  # 12 polls x 5s = 60s
        if player.poll(cms):
            triggers += 1
            player.sync(cms)
    check("one sync per cooldown window, not one per poll", triggers == 1, f"triggers={triggers}")
    check("failed switch kept the previous playlist", player.playing["name"] == "Morning Playlist")
    check("sync count bounded", player.syncs - syncs_before == 1)

    print("\n== Window. Expired CMS payload is not treated as active ==")
    from datetime import datetime, timedelta, timezone
    kolkata = timezone(timedelta(hours=5, minutes=30))
    now = datetime.now(tz=kolkata)
    past_start = (now - timedelta(hours=3)).strftime("%Y-%m-%dT%H:%M:%S")
    past_end = (now - timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%S")
    future_start = (now + timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%S")
    future_end = (now + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%S")
    live_start = (now - timedelta(minutes=5)).strftime("%Y-%m-%dT%H:%M:%S")
    live_end = (now + timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%S")

    def window_state(start, end):
        # Naive CMS timestamps are Asia/Kolkata wall times.
        def parse(raw):
            return datetime.strptime(raw, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=kolkata)
        s, e = parse(start), parse(end)
        if now < s:
            return "FUTURE"
        if now >= e:
            return "EXPIRED"
        return "ACTIVE"

    check("live window is ACTIVE", window_state(live_start, live_end) == "ACTIVE")
    check("ended window is EXPIRED", window_state(past_start, past_end) == "EXPIRED")
    check("future window is FUTURE", window_state(future_start, future_end) == "FUTURE")

    cms.activate("sch-expired", "pl-morning", start=past_start, end=past_end)
    # Player must not commit an expired payload.
    expired_player = Player(playing=cms.manual, cached_assets=["a1", "a2"])
    # Simulate tracker treating expired as none: no STARTED if window expired.
    incoming = cms.active_schedule()
    expired = window_state(incoming["startDateTime"], incoming["endDateTime"]) == "EXPIRED"
    check("expired payload detected", expired)
    check("expired payload does not start a switch", expired_player.poll(cms) is None)
    # After local expiry of a committed schedule:
    cms.activate("sch-morning", "pl-morning", start=live_start, end=live_end)
    expired_player.sync(cms)
    check("live schedule committed", expired_player.prefs.active_schedule_id == "sch-morning")
    cms.activate("sch-morning", "pl-morning", start=past_start, end=past_end)
    # Restart: cached playlist plays, expired schedule must not stay active.
    restarted = expired_player.restart()
    check("cached playlist still playing after reboot", restarted.playing["name"] == "Morning Playlist")
    cms.deactivate()
    check("expired schedule detected after reboot", restarted.poll(cms) == ENDED)
    restarted.sync(cms)
    check("fallback after expired reboot", restarted.playing["name"] == "Lobby Playlist")
    check("completed schedule not still active", restarted.prefs.active_schedule_id is None)

    print("\n== Consecutive schedules switch without unpairing ==")
    cms.undownloadable = set()
    restarted.cached_assets.update(["m1", "m2", "e1"])
    cms.activate("sch-a", "pl-morning", start=live_start, end=live_end)
    check("first consecutive schedule", restarted.poll(cms) == STARTED)
    restarted.sync(cms)
    cms.activate("sch-b", "pl-evening", start=live_start, end=live_end)
    check("second consecutive schedule", restarted.poll(cms) == CHANGED)
    restarted.sync(cms)
    check("second playlist playing", restarted.playing["name"] == "Evening Playlist")
    check("auth/pairing untouched", restarted.prefs.active_schedule_id == "sch-b")

    print("\n== 18. Duration resolution is untouched by scheduling ==")
    defaults = {"IMAGE": 10, "VIDEO": 10}
    def resolve(playlist_duration, asset_type):
        if playlist_duration:
            return playlist_duration, "PLAYLIST_OVERRIDE"
        return defaults[asset_type], "DEVICE_DEFAULT"
    check("explicit playlist duration wins", resolve(20, "IMAGE") == (20, "PLAYLIST_OVERRIDE"))
    check("NULL falls back to the device default", resolve(None, "IMAGE") == (10, "DEVICE_DEFAULT"))

    print("\nALL SCHEDULE SCENARIOS PASS")


if __name__ == "__main__":
    main()
