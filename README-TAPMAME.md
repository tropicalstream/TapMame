# TapMame — MAME for the RayNeo X3 Pro glasses

TapMame is a fork of [MAME4droid-Current](https://github.com/seleuco/MAME4droid-Current)
(GPL-2.0, by David Valdeita / Seleuco) adapted for the RayNeo X3 Pro AR
glasses and a companion phone app.

## What's different from upstream

**Glasses app** (`android-MAME4droid`, applicationId `com.tapmame`):
- **Binocular SBS presentation** — the X3 panel is 1280×480 with the left
  half feeding the left eye and the right half the right eye. The emulator
  view spans the whole panel; the MAME core is told the window is one eye
  (640×480) and the GLES3 renderer picks that up from the GL viewport; after
  each native frame the left eye is duplicated into the right
  (`glCopyTexSubImage2D` → FBO blit; default-FB self-blit is rejected by the
  driver). See `render/GLNativeRenderer.java`, `views/EmulatorViewGL.java`.
- GLES3 renderer path forced on (the GLES1 legacy path can't do SBS).
- On-screen touch controller disabled on glasses — input comes from paired
  controllers and the TapMame companion phone app.
- The Java sources keep the upstream `com.seleuco.mame4droid` package on
  purpose: the prebuilt core resolves JNI symbols against those names, and
  it keeps the fork mergeable with upstream.

**NetPlay** (lockstep + rollback, UPnP, hole punching, IPv6) is upstream's —
both players need their own copy of the same ROM; version mismatches are
refused. TapMame wraps it in an invite-code flow from the companion app.

## Building

The 348MB prebuilt MAME core is not in the repo. Fetch it once:

    ./fetch_core.sh

then build the glasses app:

    cd android-MAME4droid && ./gradlew :app:assembleDebug

No NDK is required (the JNI shim is also taken prebuilt from the upstream
release; re-enable `externalNativeBuild` in `app/build.gradle` to rebuild it
from `src/main/jni`).

## ROMs

TapMame ships no games. For testing there are freely-distributable ROMs at
<https://www.mamedev.org/roms/> (Gridlee, Robby Roto, ...). ROMs live in
`Android/data/com.tapmame/files/roms` on the glasses.

## License

GPL-2.0, same as upstream — see `COPYING` and the upstream license headers.
