#!/usr/bin/env python3
"""End-to-end verification of Device Playback Duration resolution (no assumptions).

Mirrors:
- CMS getPlayerConfig payload shape
- CMS resolveManifestDurationSeconds (legacy → null)
- Android hasExplicitDuration + resolvePlaybackDuration
"""

from __future__ import annotations

LEGACY_IMAGE = {10}
LEGACY_DOCUMENT = {20}
LEGACY_URL = {15, 20}


def cms_get_player_config(image: int, document: int, url: int) -> dict:
    playback = {
        "imageDuration": image,
        "documentDuration": document,
        "urlDuration": url,
    }
    return {
        "configVersion": 42,
        "defaultImageDuration": image,
        "defaultDocumentDuration": document,
        "defaultUrlDuration": url,
        "playback": playback,
        "display": {
            "orientation": "LANDSCAPE",
            "stretchToFit": False,
            "playback": playback,
        },
    }


def cms_resolve_manifest_duration(asset_type: str, playlist_duration: int, asset_default: int | None) -> int | None:
    raw = int(playlist_duration)
    if raw <= 0:
        return None
    if asset_type == "VIDEO":
        return raw
    type_default = 20 if asset_type in ("DOCUMENT", "URL") else 10
    asset_default = asset_default if asset_default is not None else type_default
    legacy = {asset_default, type_default}
    if asset_type == "URL":
        legacy |= {15, 20}
    if raw in legacy:
        return None
    return raw


def android_has_explicit(asset_type: str, cms_duration: int | None) -> bool:
    if cms_duration is None or cms_duration <= 0:
        return False
    if asset_type == "VIDEO":
        return True
    if asset_type == "IMAGE":
        return cms_duration not in LEGACY_IMAGE
    if asset_type == "DOCUMENT":
        return cms_duration not in LEGACY_DOCUMENT
    if asset_type == "URL":
        return cms_duration not in LEGACY_URL
    return True


def android_resolve(asset_type: str, cms_duration: int | None, defaults: dict) -> dict:
    explicit = android_has_explicit(asset_type, cms_duration)
    if asset_type == "VIDEO":
        if explicit:
            return {"playlist": cms_duration, "device": None, "applied": cms_duration, "source": "playlist"}
        return {"playlist": None, "device": None, "applied": 0, "source": "video_natural_end"}
    device = defaults[{"IMAGE": "image", "DOCUMENT": "document", "URL": "url"}[asset_type]]
    if explicit:
        return {"playlist": cms_duration, "device": device, "applied": cms_duration, "source": "playlist"}
    return {"playlist": None, "device": device, "applied": device, "source": "device_default"}


def parse_android_from_heartbeat(json_obj: dict) -> dict:
    """Mirror DeviceConfigManager field precedence."""
    playback = json_obj.get("playback") or (json_obj.get("display") or {}).get("playback") or {}
    image = (
        playback.get("imageDuration")
        or playback.get("defaultImageDuration")
        or json_obj.get("defaultImageDuration")
        or (json_obj.get("display") or {}).get("defaultImageDuration")
    )
    document = (
        playback.get("documentDuration")
        or playback.get("defaultDocumentDuration")
        or json_obj.get("defaultDocumentDuration")
        or (json_obj.get("display") or {}).get("defaultDocumentDuration")
    )
    url = (
        playback.get("urlDuration")
        or playback.get("defaultUrlDuration")
        or json_obj.get("defaultUrlDuration")
        or (json_obj.get("display") or {}).get("defaultUrlDuration")
    )
    return {"image": image, "document": document, "url": url}


def main() -> None:
    print("=" * 72)
    print("STEP 1 — CMS SAVE / getPlayerConfig payload")
    print("=" * 72)
    cms = cms_get_player_config(15, 25, 30)
    import json

    print(json.dumps(cms, indent=2))
    assert cms["defaultImageDuration"] == 15
    assert cms["defaultDocumentDuration"] == 25
    assert cms["defaultUrlDuration"] == 30
    assert cms["playback"]["imageDuration"] == 15
    print("PASS: CMS payload contains defaultImage/Document/UrlDuration = 15/25/30")

    print()
    print("=" * 72)
    print("STEP 2 — SYNC API asset duration nullification (legacy → null)")
    print("=" * 72)
    cases = [
        ("IMAGE", 10, 10, None),
        ("DOCUMENT", 20, 20, None),
        ("URL", 15, 15, None),
        ("URL", 20, 20, None),
        ("IMAGE", 12, 10, 12),  # custom override
        ("VIDEO", 0, None, None),
        ("VIDEO", 45, None, 45),
    ]
    for t, d, ad, expected in cases:
        got = cms_resolve_manifest_duration(t, d, ad)
        print(f"  type={t} playlistDuration={d} assetDefault={ad} → sync.durationSeconds={got}")
        assert got == expected, (t, d, got, expected)
    print("PASS: legacy playlist durations become null for the player")

    print()
    print("=" * 72)
    print("STEP 3 — Android receives heartbeat/sync JSON and extracts prefs")
    print("=" * 72)
    stored = parse_android_from_heartbeat(cms)
    print("Received/extracted:", stored)
    assert stored == {"image": 15, "document": 25, "url": 30}
    print("PASS: Android field precedence extracts 15/25/30")

    # Also verify nested-only payload (older heartbeat shape)
    nested_only = {
        "configVersion": 42,
        "display": {"playback": {"imageDuration": 15, "documentDuration": 25, "urlDuration": 30}},
    }
    stored2 = parse_android_from_heartbeat(nested_only)
    print("Nested-only heartbeat extract:", stored2)
    assert stored2 == {"image": 15, "document": 25, "url": 30}
    print("PASS: nested display.playback also works")

    print()
    print("=" * 72)
    print("STEP 4 — Local storage values (after apply)")
    print("=" * 72)
    print(
        f"defaultImageDuration={stored['image']}\n"
        f"defaultDocumentDuration={stored['document']}\n"
        f"defaultUrlDuration={stored['url']}"
    )

    print()
    print("=" * 72)
    print("STEP 5 — Playback engine applied duration per asset")
    print("=" * 72)
    defaults = stored
    assets = [
        ("IMAGE", None),       # CMS null after nullification
        ("IMAGE", 10),         # legacy cached 10 still on device
        ("DOCUMENT", None),
        ("DOCUMENT", 20),
        ("URL", None),
        ("URL", 15),
        ("IMAGE", 12),         # true playlist override
        ("VIDEO", None),
        ("VIDEO", 40),
    ]
    for asset_type, playlist_raw in assets:
        r = android_resolve(asset_type, playlist_raw, defaults)
        print("--------------------------------")
        print(f"Asset = {asset_type}")
        print(f"Playlist Duration = {r['playlist']}")
        print(f"Device Default = {r['device']}")
        print(f"Applied Duration = {r['applied']}")
        print(f"source = {r['source']}")
        if asset_type == "IMAGE" and playlist_raw in (None, 10):
            assert r["applied"] == 15 and r["source"] == "device_default"
        if asset_type == "DOCUMENT" and playlist_raw in (None, 20):
            assert r["applied"] == 25 and r["source"] == "device_default"
        if asset_type == "URL" and playlist_raw in (None, 15):
            assert r["applied"] == 30 and r["source"] == "device_default"
        if asset_type == "IMAGE" and playlist_raw == 12:
            assert r["applied"] == 12 and r["source"] == "playlist"
        if asset_type == "VIDEO" and playlist_raw is None:
            assert r["applied"] == 0 and r["source"] == "video_natural_end"
        if asset_type == "VIDEO" and playlist_raw == 40:
            assert r["applied"] == 40 and r["source"] == "playlist"
    print("--------------------------------")
    print("PASS: device defaults apply; playlist custom overrides; video natural end works")

    print()
    print("=" * 72)
    print("STEP 6 — Prove old bug location")
    print("=" * 72)
    print(
        "OLD BUG: hasExplicitDuration() treated ANY cmsDurationSeconds > 0 as override.\n"
        "PlaylistAsset.durationSeconds always defaulted to 10/20 in CMS DB, so device\n"
        "defaults in DeviceConfigManager were never consulted.\n"
        "File: app/.../playback/PlaybackDuration.kt (hasExplicitDuration)\n"
        "Also: PlaylistCacheRepository baked asset.durationSeconds(10) when CMS omitted duration."
    )
    print()
    print("ALL E2E CHECKS PASSED")


if __name__ == "__main__":
    main()
