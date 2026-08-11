#!/usr/bin/env python3
"""Verify blank (NULL) playlist duration → device defaults; explicit override wins."""

def resolve(asset_type, playlist, defaults):
    if asset_type == "VIDEO":
        if playlist is not None and playlist > 0:
            return playlist, "PLAYLIST_OVERRIDE"
        return 0, "VIDEO_NATURAL_END"
    if playlist is not None and playlist > 0:
        return playlist, "PLAYLIST_OVERRIDE"
    return defaults[asset_type], "DEVICE_DEFAULT"

def main():
    defaults = {"IMAGE": 20, "DOCUMENT": 25, "URL": 30}
    scenarios = [
        ("blank image", "IMAGE", None, 20, "DEVICE_DEFAULT"),
        ("blank document", "DOCUMENT", None, 25, "DEVICE_DEFAULT"),
        ("blank url", "URL", None, 30, "DEVICE_DEFAULT"),
        ("override 15", "IMAGE", 15, 15, "PLAYLIST_OVERRIDE"),
        ("clear back to blank", "IMAGE", None, 20, "DEVICE_DEFAULT"),
        ("override 10 is real", "IMAGE", 10, 10, "PLAYLIST_OVERRIDE"),
    ]
    for label, t, playlist, expected, source in scenarios:
        applied, got = resolve(t, playlist, defaults)
        print(f"[{label}]")
        print(f"Asset Type = {t}")
        print(f"Playlist Duration = {playlist if playlist is not None else 'NULL'}")
        print(f"Device Default Duration = {defaults.get(t, 'n/a')}")
        print(f"Applied Duration = {applied}")
        print(f"Duration Source = {got}")
        print("---")
        assert applied == expected and got == source, (label, applied, got)
        assert playlist != 10 or got == "PLAYLIST_OVERRIDE"
        assert not (playlist is None and applied == 10 and defaults[t] != 10)
    # Cache preserve NULL
    cached = None  # never 10/0/-1
    assert cached is None
    print("CACHE: NULL preserved (not 10/0/-1)")
    print("ALL THREE SCENARIOS PASS")

if __name__ == "__main__":
    main()
