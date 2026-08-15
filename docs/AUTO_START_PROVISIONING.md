# Orion Player — Auto-Start, Kiosk and Home-App Provisioning

How the player starts after power-on. **Home (or device owner) is the required path.**
The boot receiver is recovery only — it cannot reliably open the UI on Android 10+.

---

## 1. Required: Orion must be the Home app

Android always starts the default Home / launcher activity when the device boots or the
screen comes on. That is a background-activity-launch (BAL) exemption. Starting
`MainActivity` from `BOOT_COMPLETED` is **not**.

```
SCREEN / DEVICE ON → Android starts the default Home app
   → MainActivity (Orion)
   → lock-task if device owner
   → cached playlist plays immediately
   → CMS sync runs in the background

BootReceiver + PlayerForegroundService still start in parallel. They keep the process
alive and relaunch the Activity only if it never became visible (crash, OEM splash, kill).
```

What is guaranteed by the app on any device:

| Capability | Works from a plain install |
|---|---|
| `BOOT_COMPLETED` received | Yes |
| Foreground service restarted at boot | Yes |
| Cached playlist plays without network | Yes |
| Auto-launch retried with backoff for ~5 minutes | Yes |
| Crash-loop and watchdog-loop protection | Yes |
| Screen kept on, shown over the lock screen | Yes |
| Screen-on / user-present bring-to-front | Yes (still needs Home on Android 10+) |
| Guaranteed foreground launch after power-on | **No — set Home (section 4) or device owner (section 3)** |
| Lock Task (kiosk) mode | **No — needs section 3** |

### Why a plain APK is not enough

Since Android 10, an app in the background may not start an Activity unless it is exempt.
`BOOT_COMPLETED` is **not** an exemption. **Display over other apps (`SYSTEM_ALERT_WINDOW`)
is also not an exemption on Android 14+.** A foreground service is **not** a BAL exemption
either (measured on Android 15/16).

The pairing screen has a **Set as Home app** button. The player also asks once per boot
if it is not already Home. Device-owner devices skip the picker and pin Home themselves.

Heartbeat reports `defaultHome` and `deviceOwner`. `autoStart` is true only when the boot
receiver is registered **and** Orion is Home or device owner.

If every retry fails the player logs `PLAYER_AUTO_LAUNCH_BLOCKED` with `defaultHome=false`.
The foreground service, sync, downloads and cache keep running either way.

---

## 2. Verify auto-start on a device

```bash
adb install -r releases/orion-player-<version>-release.apk

# Watch the boot sequence in a second terminal, then reboot.
adb logcat -c
adb logcat -s OrionAutoStart:V OrionRecovery:V &
adb reboot
```

Expected output after the device comes back up:

```
OrionAutoStart: BOOT_RECEIVED action=android.intent.action.BOOT_COMPLETED
                Launching Orion Player
OrionAutoStart: PLAYER_AUTO_LAUNCH_START source=boot.android.intent.action.BOOT_COMPLETED attempt=1
OrionAutoStart: PLAYER_AUTO_LAUNCH_SUCCESS source=boot.android.intent.action.BOOT_COMPLETED
OrionAutoStart: BOOT_RECOVERY source=boot.android.intent.action.BOOT_COMPLETED
OrionAutoStart: PLAYER_INIT_START source=playback.start
OrionAutoStart: CACHE_PLAYBACK_START
                Playlist: <playlistId>
                Assets: <n>
OrionAutoStart: CMS_SYNC_START
OrionAutoStart: CMS_SYNC_SUCCESS outcome=updated
```

Offline boot is identical up to `CMS_SYNC_START`, then:

```
OrionAutoStart: CMS_SYNC_FAILED reason=... cachedPlaybackContinues=true
```

Cached playback must keep running — that is the offline requirement.

To simulate a boot without power-cycling:

```bash
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.orion.player
```

Note this only tests the receiver path. It does **not** test whether the OEM permits a real
background activity start at boot, because a shell-initiated broadcast runs with different
privileges. Only a physical reboot proves that.

---

## 3. Dedicated device (device owner) — the reliable path

A device owner is exempt from background-activity-start restrictions, can enter Lock Task
mode without any user prompt, and can be pinned as the Home app. This is the correct
configuration for production signage.

Requirements: a factory-reset device with **no** Google or other accounts added.

```bash
adb install -r releases/orion-player-<version>-release.apk
adb shell dpm set-device-owner com.orion.player/.receiver.OrionDeviceAdminReceiver
```

Expected: `Success: Device owner set to package com.orion.player`.

Common failures:

| Message | Cause |
|---|---|
| `Not allowed to set the device owner because there are already several users` | Remove all secondary users |
| `...because there are already some accounts on the device` | Factory reset, skip account setup |
| `Unknown admin` | The APK is not installed, or the package name is wrong |

Once provisioned, `OrionDeviceAdminReceiver.onEnabled` immediately allow-lists lock-task
and pins Orion as the persistent Home app. On the next launch:

```
OrionAutoStart: HOME_APP_STATUS defaultHome=true
OrionAutoStart: KIOSK_MODE_ENTERED lockTaskPermitted=true deviceOwner=true
```

Kiosk mode is on by default (`kiosk_mode_enabled`, default `true`) and is applied only when
the platform reports the package as lock-task permitted. On a non-provisioned device you
will instead see, once:

```
OrionAutoStart: KIOSK_MODE_NOT_AVAILABLE reason=device is not provisioned as a dedicated device...
```

That is not an error — the player runs normally, and `onUserLeaveHint` still pulls it back
to the front if someone presses Home.

To remove device owner (development only):

```bash
adb shell dpm remove-active-admin com.orion.player/.receiver.OrionDeviceAdminReceiver
```

---

## 4. First-time screen setup

### Path A — standard APK install (minimum)

1. Power on the panel. Skip or finish the Android wizard. Avoid a lock-screen PIN.
2. Install the Orion Player APK and open it.
3. Pair in Orion CMS with the on-screen code.
4. Tap **Set as Home app** (or accept the once-per-boot Home picker). On Android TV this
   may open Settings → Home screen — pick Orion Player and Always.
5. Optionally ignore battery optimization for Orion on OEM boxes that kill background apps.
6. Reboot once: Orion must open, not the stock TV launcher.

No adb, no factory reset. If step 4 is skipped, power-on start stays unreliable on
modern Android.

### Path B — dedicated signage device (production fleets)

1. Factory-reset the device. Add **no** Google or other accounts.
2. Install the Orion Player APK.
3. `adb shell dpm set-device-owner com.orion.player/.receiver.OrionDeviceAdminReceiver`
4. Open Orion or reboot. Persistent Home and lock-task apply automatically.
5. Pair in CMS as usual. Disable lock screen if the wizard left one on.
6. Reboot once: Orion comes up locked in front.

### Home / launcher details

`MainActivity` declares `CATEGORY_HOME` and `CATEGORY_DEFAULT`.

**Option A — installer chooses it** (Path A above). Android then starts Orion at boot
itself. This is the required fix for any device that logged `PLAYER_AUTO_LAUNCH_BLOCKED`.

**Option B — enforced by device owner** (Path B). Persistent Home is applied automatically
in `onEnabled` and again on every `MainActivity` start. No `home_app_mode_enabled` flag
is required. The flag is still set to `true` so a later opt-out can call
`clearPackagePersistentPreferredActivities`.

Verify with:

```bash
adb logcat -s OrionAutoStart | grep HOME_APP_STATUS
adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
```

---

## 5. Direct boot behaviour

`BootReceiver` is `directBootAware`, so it also runs on `LOCKED_BOOT_COMPLETED`, before the
user unlocks a device that has a secure lock screen.

At that point only device-protected storage exists. The encrypted preferences, the Room
database and the downloaded asset cache are all credential-protected and unreadable, and
`PlayerForegroundService` is deliberately **not** direct-boot aware. So locked boot only
records the event:

```
OrionAutoStart: BOOT_RECEIVED action=android.intent.action.LOCKED_BOOT_COMPLETED
                Locked boot — deferring launch until the user storage is unlocked
```

The real startup happens on `BOOT_COMPLETED`, which the platform delivers after unlock.
No credentials were moved into device-protected storage; the only thing kept there is
`orion_boot_state`, which holds boot timestamps and recovery counters and no content.

Most signage devices have no lock screen, so `BOOT_COMPLETED` arrives immediately and this
path is invisible.

---

## 6. Recovery behaviour and its limits

| Failure | Response |
|---|---|
| Activity not alive | Watchdog relaunches it; backoff 15s → 30s → 60s → … capped at 15 min |
| Playback frozen | Restarted in-process by `PlaybackRecoveryCoordinator`, same backoff; the app is *not* relaunched |
| Process killed | `START_STICKY` service returns; `PLAYER_PROCESS_RECOVERY` logged; cache-first restart |
| Uncaught crash | Immediate relaunch the first time; repeated crashes back off and relaunch via an alarm, logging `CRASH_LOOP_BACKOFF` |
| Boot broadcast repeated | De-duplicated on both wall clock and uptime; `BOOT_DUPLICATE_IGNORED` |
| Display wakes without reboot | `SCREEN_ON` / `USER_PRESENT` bring the player forward if it is not already visible |
| APK updated | `MY_PACKAGE_REPLACED` relaunches the player |

A backoff counter resets after 15 minutes without the failure recurring, so an isolated
problem still recovers instantly the next time.

Buffering, slow downloads and an unreachable CMS are **not** treated as freezes: the
watchdog keys off playback pulses, the slot loop, the video renderer and Proof-of-Play
generation, so a large asset download never triggers a restart.

---

## 7. Measured result on Android 16 TV (API 36)

Verified on the `android-36;google-tv;arm64-v8a` Android TV image, model
`sdk_google_atv64_amati_arm64`, APK 1.0.52.

**Plain install, no provisioning — auto-launch is blocked:**

```
OrionAutoStart: BOOT_RECEIVED action=android.intent.action.LOCKED_BOOT_COMPLETED
                Locked boot — deferring launch until the user storage is unlocked
OrionAutoStart: BOOT_RECEIVED action=android.intent.action.BOOT_COMPLETED
OrionAutoStart: PLAYER_AUTO_LAUNCH_START source=boot... attempt=1
OrionAutoStart: PLAYER_AUTO_LAUNCH_START source=boot.retry attempt=2   (+5s)
OrionAutoStart: PLAYER_AUTO_LAUNCH_START source=boot.retry attempt=3   (+10s)
OrionAutoStart: PLAYER_AUTO_LAUNCH_START source=boot.retry attempt=4   (+20s)
ActivityTaskManager: Background activity launch blocked! goo.gle/android-bal
    [callingPackage: com.orion.player; callingUidProcState: FOREGROUND_SERVICE;
     resultIfPiSenderAllowsBal: BAL_BLOCK]
```

No `PLAYER_AUTO_LAUNCH_SUCCESS`; the TV launcher stayed in the foreground. **Running a
foreground service is not a BAL exemption on Android 15/16.**

**Same device with Orion set as the Home app — auto-launch succeeds:**

```
OrionAutoStart: BOOT_RECEIVED action=android.intent.action.BOOT_COMPLETED
OrionAutoStart: PLAYER_AUTO_LAUNCH_START source=boot... attempt=1
OrionAutoStart: BOOT_RECOVERY source=boot... (resuming after device boot)
OrionAutoStart: PLAYER_AUTO_LAUNCH_SUCCESS source=boot...
```

`topResumedActivity=com.orion.player/.MainActivity`, on the first attempt, ~2s after
`BOOT_COMPLETED`. Repeated with airplane mode enabled: identical result, zero crashes.

**Conclusion for deployment:** on Android 13+ TV devices, Orion **must** be the Home app
(section 4, Path A) or device owner (section 3, Path B). A plain install without Home is
expected to stay on the stock launcher. Check logs for `PLAYER_AUTO_LAUNCH_BLOCKED` and
heartbeat `defaultHome` / `deviceOwner`.

---

## 8. Known OEM limitations

- **Background activity start.** Android 10+ can drop the boot launch, and Android 15/16
  reliably does (measured in section 7). Detected and logged as
  `PLAYER_AUTO_LAUNCH_BLOCKED`; fix with section 3 or 4.
- **Aggressive battery/memory managers** (Xiaomi, Huawei, Oppo and some TV vendors) may
  kill the foreground service or block autostart until the app is allow-listed by hand in
  the OEM's own security settings.
- **Lock Task without device owner** falls back to nothing rather than to user-confirmed
  screen pinning. This is intentional: pinning a device that was never provisioned traps
  the operator.
- **`android:showWhenLocked` needs API 27+.** Older devices fall back to the equivalent
  window flags, which some OEM keyguards ignore.
- **Fire OS / heavily skinned TV launchers** may not honour `CATEGORY_HOME`. Use device
  owner provisioning there.
