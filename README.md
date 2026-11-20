# RoboDuck

Send a picture from one Android phone to another using sound only. The picture
is turned into cute robot chirps, and the other phone listens and turns them back
into the exact same JPG.

## Install

On each phone, open
`https://github.com/karthiksreenivasanp/robo-duck-image/releases/latest/download/RoboDuck.apk`,
download it, open it and allow "install unknown apps" when asked.

Requires Android 10 or newer.

## Use

1. On the receiving phone, tap **Receive**. Allow the microphone.
2. On the sending phone, tap **Send** and pick a picture.
3. Hold the phones 10-50 cm apart. A transfer takes about a minute; the screen
   shows the time left.
4. The receiver shows each note it hears as a little pixel swoop and the
   picture building up from the top. It is saved to `Pictures/RoboDuck` (JPG).
5. If a few parts were missed (someone talked loudly, say), the receiver keeps
   listening: send the same picture again and only the gaps get filled.
6. After a picture arrives, the picture tools appear:
   **Auto**, **Sharpen**, **Smooth**, **Contrast**, **Denoise**, **Undo**,
   **Original** and **Save** (saves the edited copy as `..._edit.jpg`).

## Picture tools (ImageJ)

ImageJ itself is a desktop program and cannot run on Android, so its filter
code is ported in `enhance/ImageJ.kt` (ImageJ is public domain). Each filter
was checked against the original ImageJ and gives the same pixels:

| Button | ImageJ command |
|---|---|
| Sharpen | Process > Filters > Unsharp Mask (radius 2, weight 0.6) |
| Smooth | Process > Filters > Gaussian Blur (sigma 1.5) |
| Contrast | Process > Enhance Contrast (0.35% saturated) |
| Denoise | Process > Noise > Remove Outliers + Process > Filters > Median |
| (all) | Image > Scale, bicubic, 4x before editing |

**Auto** combines Remove Outliers, bicubic scaling and Unsharp Mask with this
app's own JPEG deblocking and edge-preserving smoothing, and a gentler contrast
stretch. The tools make a picture look clearer; they cannot add detail that was
never sent.

## How it works

- **Picture:** shrunk and saved as WebP (about 25% smaller than JPEG at the
  same quality). The app tries several sizes, each at the best quality that
  fits 1.2 KB, and keeps the one that looks most like the original (SSIM).
  The receiver gets exactly that picture and saves it as a JPG.
- **Sound:** each note is a 23 ms "wee-oo" swoop between 1.4 and 4.8 kHz with a
  slight warble, starting at one of 128 points of the swoop (7 bits per note,
  chirp spread spectrum). Notes are joined by 2 ms pitch glides, so phrases are
  smooth and click-free, and phrases are separated by short breaths, R2-D2 style.
- **Staying in step:** every phrase opens with the same note; the receiver
  re-locks on it (taking the earliest sharp match, and ignoring matches that
  don't stand out from noise), and tracks clock drift between the phones.
- **Repairs:** Reed-Solomon RS(127,99) over GF(128). Blocks go out 4 at a time,
  interleaved note by note but in picture order, so a burst of noise is spread
  thin and the picture still builds from the top. Unsure notes (much weaker
  than usual, or right after an audio skip) are treated as erasures. A CRC32
  checks the result; if a block is still missing, the receiver keeps the good
  blocks and fills the gaps from the next send of the same picture.

Code: `app/src/main/java/com/roboduck/image/modem` is the sound modem (plain
Kotlin, no Android dependencies); the rest is the screen and audio.

## Checks

- Simulations (noise, rooms with long echo, clock drift, audio skips, loud
  bursts, false alarms) run on a computer against the modem code.
- `app/src/androidTest/.../DeviceCheck.kt` runs on an Android emulator on every
  push (`.github/workflows/device-check.yml`): Android's own WebP encoder and
  partial decoder, a full sound round trip on the device, the ImageJ tools,
  speaker and microphone start-up, and screenshots in light and dark mode. The
  report and screenshots are published as the `device-check` release.

## Build

GitHub Actions builds `RoboDuck.apk` on every push to `main` and publishes it as
the `latest` release. To build locally, open the folder in Android Studio.
