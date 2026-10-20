# Synthetic Native Media Fixtures

These original test patterns and tones are used only by M2DeviceTest and are packaged only in the Android test APK.

- `source.mp4`: approximately 3 seconds, 160x120 at 15 fps, H.264 video with a 440 Hz AAC soundtrack.
- `silent.mp4`: the same video without an audio track, used to reject silent video-with-sound results.
- `music.mp3`: approximately 5 seconds of an independent 880 Hz tone, used to distinguish platform music from the complete video soundtrack.
- `image.png`: a 64x96 test pattern used for original-image saving and native selection screenshots.

The files were generated with the host's existing FFmpeg command-line tool. No FFmpeg executable, library, source code or application dependency is included in the app. The production implementation uses Android media APIs.
