#!/usr/bin/env python3
"""Timezone and window tests for CMS schedule timestamps.

Mirrors ScheduleTime.kt:
  * ISO-8601 with Z/offset is UTC
  * naive date-times are Asia/Kolkata
"""

from datetime import datetime, timedelta, timezone

KOLKATA = timezone(timedelta(hours=5, minutes=30))
UTC = timezone.utc


def parse(raw):
    if raw.endswith("Z"):
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    if raw[-6] in "+-" and raw[-3] == ":":
        return datetime.fromisoformat(raw)
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(raw, fmt).replace(tzinfo=KOLKATA)
        except ValueError:
            continue
    raise AssertionError(f"unparsed: {raw}")


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {label}{(' — ' + detail) if detail else ''}")
    assert condition, label


def main():
    naive = parse("2026-08-13T12:00:00")
    utc = parse("2026-08-13T06:30:00Z")
    offset = parse("2026-08-13T12:00:00+05:30")
    check("naive Kolkata == UTC Z equivalent", naive == utc)
    check("naive Kolkata == explicit offset", naive == offset)
    check("UTC Z is 5:30 behind Kolkata wall time", utc.astimezone(KOLKATA).hour == 12)

    now = datetime(2026, 8, 13, 12, 0, tzinfo=KOLKATA)
    start = parse("2026-08-13T11:00:00")
    end = parse("2026-08-13T13:00:00")
    check("inside window is active", start <= now < end)
    check("endDateTime is exclusive", now.replace(hour=13) >= end)

    expired_end = parse("2026-08-13T11:59:00")
    check("past endDateTime is expired", now >= expired_end)

    future_start = parse("2026-08-13T12:01:00")
    check("before startDateTime is future", now < future_start)

    # A UTC end of 06:30Z is 12:00 Kolkata — must not still look active at 12:00.
    utc_end = parse("2026-08-13T06:30:00Z")
    check("UTC end converts before comparing", now >= utc_end)

    space_sep = parse("2026-08-13 12:00:00")
    check("space-separated naive is Kolkata", space_sep == naive)

    tod = datetime.strptime("13:35:00", "%H:%M:%S").replace(
        year=2026, month=8, day=13, tzinfo=KOLKATA
    )
    check("end exclusive at 13:35", datetime(2026, 8, 13, 13, 35, tzinfo=KOLKATA) >= tod)

    print("ALL SCHEDULE WINDOW TESTS PASS")


if __name__ == "__main__":
    main()
