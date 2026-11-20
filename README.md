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
2. On the sending phone, turn the media volume up, tap **Send** and pick a picture.
3. Hold the phones 10-50 cm apart in a quiet room. A transfer takes about 1-2 minutes.
4. The picture appears line by line and is saved to `Pictures/RoboDuck`.
5. After a picture arrives, the picture tools appear:
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

- The picture is shrunk to about 128 px and compressed to about 2.5 KB of JPG.
- Each 23 ms chirp sweeps up through 2.1-4.9 kHz, starting from one of 64 offsets,
  so it carries 6 bits (chirp spread spectrum, the same idea LoRa radios use).
- Reed-Solomon RS(63,47) over GF(64) fixes up to 8 bad chirps in every 63, and
  8 codewords are interleaved so a short noise burst is spread out.
- The receiver locks onto a preamble, follows clock drift between the phones,
  and checks a CRC32 so it only saves the image if every byte is correct.

Code: `app/src/main/java/com/roboduck/image/modem` is the sound modem (plain Kotlin,
no Android dependencies); the rest is the screen and audio input/output.

## Build

GitHub Actions builds `RoboDuck.apk` on every push to `main` and publishes it as
the `latest` release. To build locally, open the folder in Android Studio.
