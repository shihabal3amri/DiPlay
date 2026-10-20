# Navigation audio focus

Some head units require a navigation guidance audio focus request before their
navigation output becomes audible. The opt-in **Settings → Advanced → Navigation
audio focus (experimental)** addresses this firmware behavior. It is off by
default, independent of the media audio focus setting, and applies at the next
CarPlay connection.

When enabled, each navigation renderer requests
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with navigation guidance usage and speech
content before writing navigation PCM. It releases the request after 750 ms
without PCM once both the packet queue and playback buffer have drained, and
again during renderer cleanup if necessary. A later prompt reacquires focus.
The request result, changes and release are recorded through the existing audio
diagnostic callback. Music may duck or pause according to the head unit policy.

The application does not change system stream volume or audio mode, nor does
this option separate speech already mixed into the iPhone media stream.

The original diagnostic implementation was tried on a 2023 Hyundai Elantra N
head unit running Android 9. Navigation on `BUS02_NAV_GUIDANCE` was silent
without focus and audible with an approved focus request. Actual navigation
and independent navigation volume subsequently worked. Amap also needed to be
fully terminated after changing its CarPlay channel setting so that it created
a fresh audio session.

This source port extracts the focus behavior from the local
`0.2.20-nav-debug-1-b309eae` diagnostic build and adapts it to NPlay's settings
persistence and backup. It does not include the diagnostic audition UI or theme
changes. The port has not been built, automatically tested or retested on the
vehicle; automated tests were omitted at the user's request.
