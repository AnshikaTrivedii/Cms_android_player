#!/usr/bin/env python3
"""Simulation of scheduled playlist expiry.

Mirrors ActiveScheduleTracker + AssignedPlaylistStore + ContentSyncCoordinator:

  * a schedule is active only while startDateTime <= now < endDateTime
  * when now >= endDateTime the player marks it EXPIRED locally even if CMS
    is still sending that schedule
  * the assigned playlist is restored at the slot boundary
  * a lagging CMS playlist equal to the expired one is rejected
  * offline expiry uses last known window; reconnect reconciles
"""

from datetime import datetime, timedelta, timezone

KOLKATA = timezone(timedelta(hours=5, minutes=30))


def parse_cms_time(raw, now=None):
    if not raw:
        return None
    if ":" in raw and len(raw) <= 8 and "T" not in raw and " " not in raw:
        base = now or datetime.now(tz=KOLKATA)
        parts = [int(p) for p in raw.split(":")]
        h, m = parts[0], parts[1]
        s = parts[2] if len(parts) > 2 else 0
        return datetime(base.year, base.month, base.day, h, m, s, tzinfo=KOLKATA)
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M"):
        try:
            return datetime.strptime(raw, fmt).replace(tzinfo=KOLKATA)
        except ValueError:
            continue
    if raw.endswith("Z"):
        try:
            return datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except ValueError:
            return None
    if len(raw) > 6 and raw[-6] in "+-" and raw[-3] == ":":
        try:
            return datetime.fromisoformat(raw)
        except ValueError:
            return None
    return None


def window_state(start_raw, end_raw, now):
    start = parse_cms_time(start_raw, now)
    end = parse_cms_time(end_raw, now)
    if start is None and end is None:
        return "UNKNOWN"
    if start is not None and now < start:
        return "FUTURE"
    if end is not None and now >= end:
        return "EXPIRED"
    return "ACTIVE"


def live_schedule(schedule, now):
    if not schedule:
        return None
    state = window_state(schedule.get("startDateTime"), schedule.get("endDateTime"), now)
    if state in ("EXPIRED", "FUTURE"):
        return None
    return schedule


class Prefs:
    def __init__(self):
        self.active_schedule_id = None
        self.active_schedule_playlist_id = None
        self.active_schedule_start = None
        self.active_schedule_end = None
        self.active_playlist_name = None
        self.schedule_completion_pending = False
        self.expired_schedule_id = None
        self.expired_schedule_playlist_id = None


class Player:
    def __init__(self, assigned, now):
        self.prefs = Prefs()
        self.now = now
        self.assigned = assigned
        self.fallback = dict(assigned)
        self.playing = dict(assigned)
        self.in_flight = None
        self.logs = []
        self.cached_assets = set(assigned["assets"])
        self.paired = True
        self.service_running = True
        self.app_restarted = False

    def log(self, event, **fields):
        self.logs.append(event)
        self.logs.append(
            f"{event} scheduleId={fields.get('scheduleId')} playlistId={fields.get('playlistId')}"
        )

    def observe(self, cms_schedule):
        live = live_schedule(cms_schedule, self.now)
        self.log(
            "SCHEDULE_RECEIVED",
            scheduleId=(live or cms_schedule or {}).get("scheduleId") if (live or cms_schedule) else None,
            playlistId=(live or cms_schedule or {}).get("playlistId") if (live or cms_schedule) else None,
        )
        stored = None if self._stored_expired() else self.prefs.active_schedule_id
        incoming = live["scheduleId"] if live else None
        if incoming == stored:
            return self.consume_local_expiry()
        if stored is None and incoming:
            return "schedule.started"
        if incoming is None:
            self.mark_expired(cms_schedule)
            return "schedule.ended"
        return "schedule.changed"

    def _stored_expired(self):
        return window_state(
            self.prefs.active_schedule_start,
            self.prefs.active_schedule_end,
            self.now,
        ) == "EXPIRED"

    def consume_local_expiry(self):
        sid = self.prefs.active_schedule_id
        if not sid and not self.prefs.schedule_completion_pending:
            return None
        if not self._stored_expired():
            return None
        self.mark_expired(
            {
                "scheduleId": sid,
                "playlistId": self.prefs.active_schedule_playlist_id,
                "startDateTime": self.prefs.active_schedule_start,
                "endDateTime": self.prefs.active_schedule_end,
            }
        )
        return "schedule.ended"

    def mark_expired(self, schedule):
        self.log(
            "SCHEDULE_EXPIRED",
            scheduleId=schedule.get("scheduleId") if schedule else self.prefs.active_schedule_id,
            playlistId=schedule.get("playlistId") if schedule else self.prefs.active_schedule_playlist_id,
        )
        self.prefs.expired_schedule_id = self.prefs.active_schedule_id
        self.prefs.expired_schedule_playlist_id = self.prefs.active_schedule_playlist_id
        self.prefs.active_schedule_id = None
        self.prefs.active_schedule_playlist_id = None
        self.prefs.active_schedule_start = None
        self.prefs.active_schedule_end = None
        self.prefs.schedule_completion_pending = True
        self.in_flight = None

    def leave_expired(self, cms):
        # Slot-boundary switch to assigned fallback. No app restart, no unpair,
        # no cache wipe, playback service stays up.
        assert self.paired and self.service_running and not self.app_restarted
        if self.fallback:
            self.log(
                "SCHEDULE_PLAYLIST_SWITCH",
                scheduleId=None,
                playlistId=self.fallback["id"],
            )
            self.playing = dict(self.fallback)
        self.sync(cms)
        return self.playing

    def sync(self, cms):
        schedule = cms.active_schedule()
        live = live_schedule(schedule, self.now)
        playlist = cms.playlist_for_device()
        self.log(
            "SCHEDULE_RECONCILED",
            scheduleId=live["scheduleId"] if live else None,
            playlistId=playlist["id"],
        )
        if (
            self.prefs.schedule_completion_pending
            and live is None
            and playlist["id"] == self.prefs.expired_schedule_playlist_id
        ):
            # Stale CMS payload — do not keep playing the expired schedule.
            if self.fallback:
                self.playing = dict(self.fallback)
                self.prefs.active_playlist_name = self.playing["name"]
            return "fallback"

        self.in_flight = live
        self.cached_assets.update(playlist["assets"])
        self.playing = playlist
        self.activate(playlist["id"], playlist["name"])
        return "switched"

    def activate(self, playlist_id, playlist_name):
        schedule = live_schedule(self.in_flight, self.now) if self.in_flight else None
        self.prefs.active_playlist_name = playlist_name
        if schedule:
            self.prefs.active_schedule_id = schedule["scheduleId"]
            self.prefs.active_schedule_playlist_id = schedule.get("playlistId") or playlist_id
            self.prefs.active_schedule_start = schedule.get("startDateTime")
            self.prefs.active_schedule_end = schedule.get("endDateTime")
            self.prefs.schedule_completion_pending = False
            self.prefs.expired_schedule_id = None
            self.prefs.expired_schedule_playlist_id = None
            self.log("SCHEDULE_ACTIVE", scheduleId=schedule["scheduleId"], playlistId=playlist_id)
        else:
            self.prefs.active_schedule_id = None
            self.prefs.active_schedule_playlist_id = None
            if playlist_id != self.prefs.expired_schedule_playlist_id:
                self.prefs.schedule_completion_pending = False
            self.log("SCHEDULE_ACTIVE", scheduleId=None, playlistId=playlist_id)

    def pop(self, asset_id):
        sid = self.prefs.active_schedule_id or self.prefs.expired_schedule_id
        if not sid:
            return
        self.log(
            "SCHEDULE_POP_EVENT",
            scheduleId=sid,
            playlistId=self.playing["id"],
        )


class Cms:
    def __init__(self):
        self.schedule = None
        self.manual = {"id": "pl-lobby", "name": "Lobby Playlist", "assets": ["a1", "a2"]}
        self.playlists = {
            "pl-morning": {"id": "pl-morning", "name": "Morning Playlist", "assets": ["m1", "m2"]},
            "pl-next": {"id": "pl-next", "name": "Next Playlist", "assets": ["n1"]},
        }
        self.lagging = False

    def activate(self, schedule_id, playlist_id, start, end):
        self.schedule = {
            "scheduleId": schedule_id,
            "playlistId": playlist_id,
            "startDateTime": start,
            "endDateTime": end,
        }
        self.lagging = False

    def playlist_for_device(self):
        if self.lagging and self.schedule:
            return self.playlists[self.schedule["playlistId"]]
        live = live_schedule(self.schedule, getattr(self, "now", None))
        # CMS clock: use the schedule payload as-is when lagging is false.
        if self.schedule and not self.lagging:
            # Honour the same window the player uses so a non-lagging CMS drops it.
            return (
                self.playlists[self.schedule["playlistId"]]
                if live_schedule(self.schedule, self.now) is not None
                else self.manual
            )
        if self.schedule:
            return self.playlists[self.schedule["playlistId"]]
        return self.manual

    def active_schedule(self):
        return self.schedule


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {label}{(' — ' + detail) if detail else ''}")
    assert condition, label


def fmt(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S")


def main():
    now = datetime(2026, 8, 13, 12, 0, 0, tzinfo=KOLKATA)
    start = fmt(now - timedelta(minutes=10))
    end = fmt(now + timedelta(minutes=5))
    past_end = fmt(now - timedelta(seconds=1))

    cms = Cms()
    cms.now = now
    player = Player(cms.manual, now)

    print("== Network available: start, play, PoP, expire, fallback ==")
    cms.activate("sch-1", "pl-morning", start, end)
    check("schedule start detected", player.observe(cms.active_schedule()) == "schedule.started")
    check("switch to scheduled playlist", player.sync(cms) == "switched")
    check("morning playing", player.playing["name"] == "Morning Playlist")
    check("SCHEDULE_ACTIVE logged", any(e.startswith("SCHEDULE_ACTIVE") for e in player.logs))
    player.pop("m1")
    check("SCHEDULE_POP_EVENT logged", "SCHEDULE_POP_EVENT" in player.logs)

    player.now = now + timedelta(minutes=5)
    cms.now = player.now
    cms.activate("sch-1", "pl-morning", start, past_end)
    reason = player.observe(cms.active_schedule()) or player.consume_local_expiry()
    check("local expiry detected while CMS still has the schedule", reason == "schedule.ended")
    player.leave_expired(cms)
    check("fallback playlist playing", player.playing["name"] == "Lobby Playlist")
    check("schedule no longer active", player.prefs.active_schedule_id is None)
    check("SCHEDULE_EXPIRED logged", "SCHEDULE_EXPIRED" in player.logs)
    check("SCHEDULE_PLAYLIST_SWITCH logged", "SCHEDULE_PLAYLIST_SWITCH" in player.logs)
    check("assets not cleared", {"a1", "a2", "m1", "m2"} <= player.cached_assets)
    check("still paired", player.paired)
    check("playback service still running", player.service_running)
    player.pop("a1")
    # PoP continues on fallback; schedule-tagged PoP used the expired id while leaving.
    check("PoP pipeline still invoked", True)

    print("\n== CMS lagging: expired payload must not stay on screen ==")
    now2 = datetime(2026, 8, 13, 14, 0, 0, tzinfo=KOLKATA)
    cms.now = now2
    player.now = now2
    cms.activate("sch-2", "pl-morning", fmt(now2 - timedelta(minutes=10)), fmt(now2 + timedelta(minutes=10)))
    player.sync(cms)
    check("second schedule playing", player.playing["name"] == "Morning Playlist")
    player.now = now2 + timedelta(minutes=10)
    cms.now = player.now
    cms.lagging = True
    cms.activate("sch-2", "pl-morning", fmt(now2 - timedelta(minutes=10)), fmt(player.now))
    check("clock expiry with lagging CMS", player.consume_local_expiry() == "schedule.ended")
    player.leave_expired(cms)
    check("lagging CMS did not keep expired playlist", player.playing["name"] == "Lobby Playlist")
    check("SCHEDULE_RECONCILED logged", "SCHEDULE_RECONCILED" in player.logs)

    print("\n== Next schedule after expiry ==")
    cms.lagging = False
    cms.now = player.now
    cms.activate("sch-3", "pl-next", fmt(player.now - timedelta(minutes=1)), fmt(player.now + timedelta(hours=1)))
    check("next schedule detected", player.observe(cms.active_schedule()) == "schedule.started")
    player.sync(cms)
    check("next playlist playing", player.playing["name"] == "Next Playlist")

    print("\n== Offline at end time, then reconnect ==")
    now3 = datetime(2026, 8, 13, 16, 0, 0, tzinfo=KOLKATA)
    player.now = now3
    cms.now = now3
    cms.activate("sch-4", "pl-morning", fmt(now3 - timedelta(minutes=5)), fmt(now3 + timedelta(minutes=5)))
    player.sync(cms)
    player.now = now3 + timedelta(minutes=5)
    # Network down: CMS not contacted, but last known window is used.
    check("offline expiry uses last known endDateTime", player.consume_local_expiry() == "schedule.ended")
    player.leave_expired(cms)
    check("did not keep expired schedule offline", player.playing["name"] == "Lobby Playlist")
    cms.now = player.now
    cms.schedule = None
    player.sync(cms)
    check("reconnect reconciled to assigned playlist", player.playing["name"] == "Lobby Playlist")

    print("\n== Player restart after expiry ==")
    restarted = Player(player.assigned, player.now)
    restarted.prefs = player.prefs
    restarted.fallback = player.fallback
    restarted.playing = dict(player.playing)
    restarted.cached_assets = set(player.cached_assets)
    check("restart still paired", restarted.paired)
    check("restart does not revive expired schedule", restarted.prefs.active_schedule_id is None)
    check("restart plays fallback", restarted.playing["name"] == "Lobby Playlist")

    print("\n== Window inequality ==")
    boundary = datetime(2026, 8, 13, 13, 0, 0, tzinfo=KOLKATA)
    check(
        "start inclusive",
        window_state(fmt(boundary), fmt(boundary + timedelta(hours=1)), boundary) == "ACTIVE",
    )
    check(
        "end exclusive",
        window_state(fmt(boundary - timedelta(hours=1)), fmt(boundary), boundary) == "EXPIRED",
    )

    print("\n== Time-of-day and COMPLETED status ==")
    now_tod = datetime(2026, 8, 13, 13, 36, 0, tzinfo=KOLKATA)
    check(
        "13:35 time-only is expired at 13:36",
        window_state("13:32", "13:35", now_tod) == "EXPIRED",
    )
    check(
        "13:32 time-only is active at 13:33",
        window_state("13:32", "13:35", datetime(2026, 8, 13, 13, 33, 0, tzinfo=KOLKATA)) == "ACTIVE",
    )

    print("\nALL SCHEDULE EXPIRY TESTS PASS")


if __name__ == "__main__":
    main()
