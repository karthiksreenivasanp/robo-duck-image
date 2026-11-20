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
3. Hold the phones 10-50 cm apart in a quiet room. A transfer takes about 1.5 minutes; the screen shows the time left.
4. The receiver shows each whistle it hears as a little pixel swoop, and the
   picture builds up from the top with a lime scan line. It is saved to
   `Pictures/RoboDuck` when complete.
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

- The picture is shrunk (up to 160 px) and saved as a JPG of about 2 KB. The
  standard JPEG tables every phone already knows are left out of the sound
  (deflate with a shared dictionary, see `modem/Payload.kt`), so about 1.6 KB
  travels; the receiver rebuilds the exact same JPG.
- Each 23 ms whistle sweeps up or down through 1.9-6.9 kHz with a slight warble,
  starting at one of 32 points: 1 + 5 = 6 bits per whistle (chirp spread spectrum,
  the same idea LoRa radios use). Whistles come in short phrases with pauses in
  between, which gives the R2-D2 style chatter.
- Reed-Solomon RS(63,47) over GF(64) fixes up to 8 bad chirps in every 63, and
  2 codewords are interleaved so a short noise burst is spread out; unsure
  whistles are treated as erasures, which cost half as much to repair.
- The receiver locks onto a preamble, follows clock drift between the phones,
  and checks a CRC32 so it only saves the image if every byte is correct.

Code: `app/src/main/java/com/roboduck/image/modem` is the sound modem (plain Kotlin,
no Android dependencies); the rest is the screen and audio input/output.

## Build

GitHub Actions builds `RoboDuck.apk` on every push to `main` and publishes it as
the `latest` release. To build locally, open the folder in Android Studio.
