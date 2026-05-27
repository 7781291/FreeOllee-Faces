# Manual Verification — Active-Face UI Redesign

Build & install: `./gradlew :app:assembleDebug` then install the APK from
`app/build/outputs/apk/debug/`.

## Launch & default view
- [ ] App opens on the Home view showing the previously-active face (fresh install: Temperature).
- [ ] Upgrading from a build that had auto-update OFF lands on Temperature (migration).
- [ ] Upgrading from auto-update Temperature/Sun lands on that same face.

## Faces list & switching
- [ ] Tapping "Faces" opens the list; the active face shows a filled radio.
- [ ] Tapping a different face returns Home immediately on that face.
- [ ] Device back button from the list returns Home without changing the active face.

## Active = auto = watch
- [ ] Selecting Temperature/Sun starts auto-updates (verify a scheduled push lands on the watch).
- [ ] Selecting Custom stops auto-updates (no further scheduled pushes).

## Staleness on activation
- [ ] Activating Temperature with a recent (< interval) cached value pushes without a visible reload.
- [ ] Activating Temperature with a stale (> interval) value shows "Loading…" then a fresh value.
- [ ] "Update now" always reloads and pushes, even when the value is fresh.

## Timestamps
- [ ] Temperature shows "Updated <time>" and "Next update <time>".
- [ ] Changing the interval updates the "Next update" time.
- [ ] Sun shows "Updated <time>" and "Next: <sunrise/sunset> at <time>".

## Custom face
- [ ] Typing + Send pushes the string and shows "Sent '<text>' at <time>".
- [ ] On a failed send (watch off/unreachable) the status shows "Send failed: …" and NO "Sent '<text>' at <time>" line appears.
- [ ] The custom string survives an app relaunch (persisted).
- [ ] Activating Custom with a saved string re-pushes it; with none, it waits for input.

## Location fallback
- [ ] With location permission granted and a successful fix, no lat/lng fields appear.
- [ ] With permission denied (or a failed fix), the "Location unavailable" block with lat/lng appears on Temperature/Sun.
- [ ] Entering coordinates manually refreshes the value; "Grant location" re-requests permission.
- [ ] The fallback never appears on the Custom face.

## Watch selection
- [ ] "select/change" opens the bonded-devices dialog; picking a watch updates the label.
- [ ] After selecting a watch, the current active face is pushed to it.
