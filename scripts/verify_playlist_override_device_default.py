#!/usr/bin/env python3
"""End-to-end check of playlist-override-then-device-default resolution.

Mirrors PlaybackDuration.kt / DevicePlaybackDurations.kt so the rule can be
verified without a device: playlist duration wins when set, otherwise the
device default for that asset type is applied at playback time. Cached playlist
durations must stay exactly as the CMS sent them (NULL stays NULL).
"""

PLAYLIST_OVERRIDE = "PLAYLIST_OVERRIDE"
DEVICE_DEFAULT = "DEVICE_DEFAULT"
VIDEO_NATURAL_END = "VIDEO_NATURAL_END"


def cache_duration(cms_duration):
    """PlaylistCacheRepository: store the CMS value verbatim, never 10/0/-1."""
    return cms_duration if (cms_duration or 0) > 0 else None


def resolve(asset_type, playlist_duration, defaults):
    """PlaybackDuration.resolvePlaybackDuration."""
    device_default = defaults.get(asset_type)
    if (playlist_duration or 0) > 0:
        return playlist_duration, PLAYLIST_OVERRIDE
    if device_default is not None:
        return device_default, DEVICE_DEFAULT
    return 0, VIDEO_NATURAL_END


def play(playlist, defaults):
    applied = []
    for name, asset_type, playlist_duration in playlist:
        cached = cache_duration(playlist_duration)
        assert cached == playlist_duration, f"{name}: cache mutated {playlist_duration} -> {cached}"
        seconds, source = resolve(asset_type, cached, defaults)
        print(f"Asset: {name} ({asset_type})")
        print(f"Playlist Duration: {playlist_duration if playlist_duration is not None else 'NULL'}")
        print(f"Device Default: {defaults.get(asset_type) if defaults.get(asset_type) is not None else 'NULL'}")
        print(f"Applied Duration: {seconds if seconds > 0 else 'natural end'}")
        print(f"Source: {source}")
        print("-" * 34)
        applied.append((name, seconds, source))
    return applied


def main():
    # Playlist rows are never rewritten between the two rounds.
    playlist = [
        ("Image 1", "IMAGE", 20),
        ("Image 2", "IMAGE", None),
        ("Video 1", "VIDEO", 20),
        ("Video 2", "VIDEO", None),
        ("Video 3", "VIDEO", None),
        ("Doc 1", "DOCUMENT", 30),
        ("Doc 2", "DOCUMENT", None),
        ("Url 1", "URL", 30),
        ("Url 2", "URL", None),
    ]

    print("=== Device defaults: image=10 video=10 document=25 url=35 ===")
    round_one = play(
        playlist,
        {"IMAGE": 10, "VIDEO": 10, "DOCUMENT": 25, "URL": 35},
    )
    assert round_one == [
        ("Image 1", 20, PLAYLIST_OVERRIDE),
        ("Image 2", 10, DEVICE_DEFAULT),
        ("Video 1", 20, PLAYLIST_OVERRIDE),
        ("Video 2", 10, DEVICE_DEFAULT),
        ("Video 3", 10, DEVICE_DEFAULT),
        ("Doc 1", 30, PLAYLIST_OVERRIDE),
        ("Doc 2", 25, DEVICE_DEFAULT),
        ("Url 1", 30, PLAYLIST_OVERRIDE),
        ("Url 2", 35, DEVICE_DEFAULT),
    ], round_one

    print("=== CMS changes defaults to image=15 video=15 (no playlist edit) ===")
    round_two = play(
        playlist,
        {"IMAGE": 15, "VIDEO": 15, "DOCUMENT": 25, "URL": 35},
    )
    assert round_two == [
        ("Image 1", 20, PLAYLIST_OVERRIDE),
        ("Image 2", 15, DEVICE_DEFAULT),
        ("Video 1", 20, PLAYLIST_OVERRIDE),
        ("Video 2", 15, DEVICE_DEFAULT),
        ("Video 3", 15, DEVICE_DEFAULT),
        ("Doc 1", 30, PLAYLIST_OVERRIDE),
        ("Doc 2", 25, DEVICE_DEFAULT),
        ("Url 1", 30, PLAYLIST_OVERRIDE),
        ("Url 2", 35, DEVICE_DEFAULT),
    ], round_two

    print("=== CMS never sends a video default ===")
    legacy = play(
        [("Video 4", "VIDEO", None), ("Video 5", "VIDEO", 45)],
        {"IMAGE": 10, "VIDEO": None, "DOCUMENT": 25, "URL": 35},
    )
    assert legacy == [
        ("Video 4", 0, VIDEO_NATURAL_END),
        ("Video 5", 45, PLAYLIST_OVERRIDE),
    ], legacy

    # Playlist data is untouched by either round.
    assert [row[2] for row in playlist] == [20, None, 20, None, None, 30, None, 30, None]
    print("PASS: playlist overrides preserved, NULL resolves to live device defaults")


if __name__ == "__main__":
    main()
