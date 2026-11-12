# Installing and trying SuperJavendo

SuperJavendo is a plain Java application: no native libraries, no installer. You build one jar and run it.

## 1. Requirements

| | |
|---|---|
| Java | JDK **25 or newer** (to build). A JRE 25+ is enough to only *run* a jar someone gave you |
| Build tool | Maven 3.9+ (only to build from source) |
| OS | Linux (tested). macOS and Windows should work but are **untested**: gamepads there go through the input4j library, see below |
| Display and sound | A desktop session (Swing window) and a working audio output |
| ROMs | **Your own** `.sfc` / `.smc` files (or `.zip` containing one). None are included and none ever will be |

Check your setup:

```sh
java -version     # must say 25 or higher
mvn -version
```

Install on common distributions:

```sh
# Arch
sudo pacman -S jdk-openjdk maven
# Debian / Ubuntu
sudo apt install openjdk-25-jdk maven
# Fedora
sudo dnf install java-25-openjdk-devel maven
```

## 2. Get and build

```sh
git clone git@github.com:sanjeyac/super-javendo.git
cd super-javendo
mvn -q package                 # compiles and runs the tests (about a minute)
# or, to skip the tests:
mvn -q package -DskipTests
```

The result is `target/superjavendo.jar`, a single self-contained file. To hand the emulator to someone who
does not want to build, give them just that jar: `java -jar superjavendo.jar` is all they need.

## 3. First run

```sh
./run.sh                         # opens the game library (rebuilds first if the sources changed)
# same thing without the script:
java -jar target/superjavendo.jar
```

1. Open the **ROM Manager** tab and add the folder that holds your ROMs. The emulator scans it (subfolders included).
2. Go back to the **Library**, pick a game and press Enter (or double-click).
3. Or skip the library and start a ROM directly:

```sh
java -jar target/superjavendo.jar /path/to/game.sfc
```

On the first run the emulator creates `~/.config/superjavendo/` (or `$XDG_CONFIG_HOME/superjavendo/`) with default
controller profiles: `keyboard`, `keyboard2`, `easysmx`, `xbox`. Existing files are never overwritten.
To start from scratch, delete that folder (it also holds save states and SRAM-related lists).

## 4. Controls

Default keyboard profile:

| Key | Action |
|---|---|
| arrows | D-pad |
| Z, X, A, S | B, A, Y, X |
| Q, W | L, R |
| Enter, Shift | Start, Select |
| `1`…`4` / Shift+`1`…`4` | save / load state slot 1…4 |
| F5 / F9 | save / load the quick slot |
| P | pause |
| F11 | full screen |
| Esc | quit (leaves full screen first) |

Remap anything in the **Controller** tab. Profiles are plain text files in `~/.config/superjavendo/joystick/`.

### Gamepad on macOS and Windows (untested)

On Linux the emulator reads the kernel joystick API directly. On macOS and Windows it uses the
[input4j](https://github.com/gurkenlabs/input4j) library (bundled in the jar, nothing to install). Differences:

- Button and axis numbers (`BUTTON_n`, `AXIS_n`) are the position of the control on the device, not the Linux numbers,
  so the default `easysmx`/`xbox` profiles will not match: open the **Controller** tab and map the buttons with "add input".
- It has never been run on a real Mac or Windows machine. If a pad is not detected, say which model and system you have.
- To try it on Linux: `java -Dsuperjavendo.gamepad=input4j -jar target/superjavendo.jar` (input4j reads `/dev/input/event*`,
  which may need udev rules or the `input` group).

### Gamepad (Linux)

Connect the pad before starting the emulator. It must show up as `/dev/input/js0`:

```sh
ls /dev/input/js*
jstest /dev/input/js0        # from the "joystick" / "linuxconsoletools" package, to see button numbers
```

If the device exists but the pad does not respond, your user probably cannot read it: check `ls -l /dev/input/js0`
and add yourself to the group that owns it (often `input`), then log in again. If there is no `js0` at all,
load the module: `sudo modprobe joydev`.

Then pick the profile in the Controller tab, or from the command line:

```sh
java -jar target/superjavendo.jar game.sfc -p1 easysmx -p2 keyboard2
```

## 5. Optional extras

- **Covers**: put images named like the ROM (`Game Name.png` or `.jpg`) in `~/.config/superjavendo/game-covers/`
  or in a `covers/` folder next to the ROMs. They are not distributed (copyright).
- **Metadata** (title, year, genre…): `~/.config/superjavendo/games.tsv`, keyed by CRC32.
- **No-Intro verification**: drop a No-Intro DAT (clrmamepro format) at `~/.config/superjavendo/nointro.dat`
  to see which dumps are verified in the ROM Manager.
- **Language**: Settings tab (English, Italiano, Español, Français, Deutsch, Português, Nederlands, Polski).
- **Special-chip games** (SuperFX, SA-1, DSP-1, Cx4, S-DD1, OBC1): detected automatically from the ROM header.
  MSU-1 games need their `<rom>.msu` and `<rom>-N.pcm` files next to the ROM.

## 6. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `Unsupported class file major version` or `release version 25 not supported` | Java is older than 25. Install JDK 25+ and check `java -version` / `mvn -version` use the same one |
| `mvn package` fails in tests | Run with `-DskipTests` and tell us which test failed (see below) |
| Window opens but is black or the app exits right away | The ROM may be corrupt or have an unsupported mapper; run it from a terminal and read the message |
| No sound | Check the system's default audio device and the volume in Settings; start from a terminal and look for `javax.sound` errors |
| Pad not detected | See the Gamepad section above |
| Stutter every now and then | Usually the JVM warming up in the first seconds; it settles. Report it if it persists |
| Headless server / no display | Use `--headless N --shot out.png` (see `README.md`) to run N frames and save a screenshot |

## 7. What to report when testing

Please include:

1. The game (name and region) and the exact command you ran.
2. What you expected and what happened; a screenshot helps.
3. Output of `java -version`, your distribution and the pad model, if input is involved.
4. The terminal output (errors and warnings go there).
5. If the game is stuck or glitched: a save state from just before it
   (`~/.config/superjavendo/states/<rom>.N.state`), and a screenshot taken with
   `java -jar target/superjavendo.jar --state-shot file.state out.png`.

Please do **not** send ROMs.
