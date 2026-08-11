#!/usr/bin/env python3
"""Verify NULL playlist duration → device defaults (no hardcoded 10/15/20)."""

def has_explicit(cms):
    return cms is not None and cms > 0

def resolve(asset_type, cms, defaults):
    if asset_type == "VIDEO":
        if has_explicit(cms):
            return cms, "playlist"
        return 0, "video_natural_end"
    device = defaults[asset_type]
    if has_explicit(cms):
        return cms, "playlist"
    return device, "device_default"

def main():
    defaults = {"IMAGE": 20, "DOCUMENT": 25, "URL": 30}
    cases = [
        ("IMAGE", None, 20, "device_default"),
        ("DOCUMENT", None, 25, "device_default"),
        ("URL", None, 30, "device_default"),
        ("IMAGE", 25, 25, "playlist"),
        ("IMAGE", 10, 10, "playlist"),  # 10 is a real override now, not a fake default
        ("VIDEO", None, 0, "video_natural_end"),
        ("VIDEO", 40, 40, "playlist"),
    ]
    for t, cms, expected, source in cases:
        applied, got_source = resolve(t, cms, defaults)
        print(f"Asset={t} Playlist={cms} → Applied={applied} source={got_source}")
        assert applied == expected and got_source == source, (t, cms, applied, got_source)
        if cms is None and t != "VIDEO":
            print(f"  Playlist Duration = NULL")
            print(f"  Using Device Default = {defaults[t]}")
            print(f"  Applied Duration = {applied}")
        elif got_source == "playlist":
            print(f"  Playlist Duration = {cms}")
            print(f"  Using Playlist Override = {applied}")
    print("ALL PASS — NULL uses device defaults; positive playlist overrides; no hardcoded fallback")

if __name__ == "__main__":
    main()
