# On-device WhatsApp Superpack inspector

This helper is a read-only extraction step for the user's own installed WhatsApp APK split. It does not patch, resign, install, or modify WhatsApp, and it does not transmit files automatically.

## Inputs

The user selects the matching `split_config.arm64_v8a.apk` using Android's Storage Access Framework. MicUp copies only these exact entries into its app-private storage:

- `lib/arm64-v8a/libs.so`
- `lib/arm64-v8a/libsuperpack.so`

Both files are checked as ELF64 little-endian AArch64 (`e_machine = 183`). The decoder is loaded from private storage with `System.load`.

## Native interface

The Java declarations under `com.facebook.superpack` and `com.whatsapp.superpack` mirror the JNI method names and descriptors inspected in the matching WhatsApp DEX. Their purpose is to let the selected `libsuperpack.so` register its native entry points in MicUp's process. No proprietary native binary is committed to this repository.

The extraction call mirrors WhatsApp's own DEX call site:

```java
AssetDecompressor.decompress_from_so(
    libsSoPath,
    outputDirectory,
    "spo"
);
```

## Output

The helper enumerates extracted `.so` files, records their names, sizes, and SHA-256 hashes, and creates `whatsapp-native-analysis.zip` in MicUp's cache. The ZIP includes the manifest and libraries whose names match WhatsApp, Opus, Ogg, or `libc++_shared.so`. The user must explicitly tap **Share analysis bundle** to choose where the ZIP is sent.

## Safety and current status

- Uses Android's document picker; no broad storage permission is needed by this helper.
- Reads the selected APK split and writes only to MicUp's private directory/cache.
- Does not change WhatsApp files, account state, messages, or signing.
- Does not automatically upload or share extracted libraries.
- The helper has been added to the app source; successful CI compilation does not prove that the native JNI registration and extraction succeed on the target phone. That runtime validation remains outstanding.
- The next stage depends on receiving and statically inspecting the generated bundle. No WhatsApp recorder patch is included at this stage.
