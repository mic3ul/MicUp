# Third-party notices

## SoundTouch 2.4.0

MicUp uses the SoundTouch audio processing library for speech pitch shifting.

- Project: https://soundtouch.surina.net/
- Source mirror pinned by CMake: https://github.com/silvansky/soundtouch/tree/b8e2a5f3f04483691962ab1a36ffa166abdcd675
- License: GNU Lesser General Public License, version 2.1 (LGPL-2.1)
- License text: https://github.com/silvansky/soundtouch/blob/b8e2a5f3f04483691962ab1a36ffa166abdcd675/COPYING.TXT

SoundTouch is built as a separate shared library so it can be replaced independently. Anyone distributing a binary that includes it must comply with LGPL-2.1, including providing the corresponding library source and required relinking/replacement materials.
