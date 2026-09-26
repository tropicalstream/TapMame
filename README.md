# TapMame

TapMame is a RayNeo X3 Pro AR glasses port of MAME4droid (Current), David Valdeita's Android build of the MAME arcade emulator, paired with a companion Android app that turns a phone into the controller. The glasses app renders the emulator across the X3 Pro's full binocular display, splitting the panel into a matched left- and right-eye image so the game reads correctly through the waveguide optics. Since the glasses have no touchscreen input of their own, all control, ROM management, and MAME's networked play feature are handled from the companion phone app, which also wraps upstream's peer-to-peer NetPlay (rollback and lockstep syncing, direct IPv6 connections, UPnP/hole-punching) into a simple invite-code flow. TapMame ships no games; ROMs are supplied by the user.

## Controls

- Pair the TapMame Pad companion app with the glasses over the local network
- Use the companion app's on-screen controller to play, browse the game list, and manage settings
- Push and manage ROM files from the companion app's file browser
- Start or join a NetPlay match with an invite code shared from the companion app

## Demo

[![TapMame Trailer](https://i.ytimg.com/vi/ioQzMMbrP0E/hqdefault.jpg)](https://youtu.be/ioQzMMbrP0E)

## Download

The glasses APK is published as a GitHub release asset (too large for the repo itself): [TapMame.apk](https://github.com/tropicalstream/TapMame/releases/download/v1.37.7/TapMame.apk). The phone companion, [TapMamePad.apk](TapMamePad.apk), is required for input and is included in the repo.

## Credits

TapMame is a fork of [MAME4droid (Current)](https://github.com/seleuco/MAME4droid-Current) by David Valdeita (Seleuco), licensed under GPL-2.0 — see [COPYING](COPYING) and [UPSTREAM-README.md](UPSTREAM-README.md) for the full upstream project description. TapMame's own changes are documented in [README-TAPMAME.md](README-TAPMAME.md).
