# Synthetic audio fixture

`sweep-radio-test-tone.wav` contains a generated 440 Hz tone for the first 0.2 seconds of each second, followed by silence, for 30 seconds. It is mono, 16-bit little-endian PCM at 16 kHz. The amplitude is 2,500; generation uses `int(2500 * sin(2 * pi * 440 * i / 16000))` for the tone samples and zero for silence.

This is software playback test data, not police or emergency radio audio. It is not bundled as a feed in the app. Use the source file's HTTPS URL only during an explicitly initiated playback test. The generated fixture is released under the repository's MIT license.
