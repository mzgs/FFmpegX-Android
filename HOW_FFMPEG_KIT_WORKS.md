# How FFmpeg-kit Executes on Android 10+

## The Problem
Android 10+ enforces W^X (Write XOR Execute) policy:
- You **cannot** execute binaries from writable directories (`/data/data/app/files/`)
- `execv()`, `system()`, and similar calls are blocked for security

## How FFmpeg-kit Solves This

FFmpeg-kit **does NOT use a standalone executable**. Instead:

### 1. FFmpeg is Compiled as a Shared Library
```bash
# Instead of building an executable:
./configure --enable-shared --disable-programs
make

# This creates libffmpeg.so, not ffmpeg executable
```

### 2. JNI Wrapper Calls FFmpeg Functions Directly
```c
// ffmpeg_kit.c
#include "ffmpeg.h"

JNIEXPORT int JNICALL 
Java_com_arthenica_ffmpegkit_FFmpegKitNative_run(JNIEnv *env, jobject obj, jobjectArray args) {
    // Convert Java args to C args
    char **argv = convertArgs(env, args);
    
    // Call FFmpeg's main function directly (no exec needed!)
    return ffmpeg_main(argc, argv);
}
```

### 3. Android Loads it as a Native Library
```kotlin
// This is allowed on all Android versions
System.loadLibrary("ffmpegkit")

// Call through JNI
val result = FFmpegKitNative.run(arrayOf("-version"))
```

## Key Insight

The difference is:

### ❌ Our Current Approach (Blocked):
1. Extract `ffmpeg` binary to `/data/data/app/files/`
2. Try to `exec()` it → **BLOCKED by Android 10+**

### ✅ FFmpeg-kit Approach (Works):
1. Package FFmpeg as `libffmpegkit.so`
2. Android loads it into memory with `System.loadLibrary()`
3. Call FFmpeg functions directly through JNI → **ALLOWED**

## Why This Works

- **Shared libraries are loaded into app's memory space** - no exec needed
- **Android allows JNI calls** - it's how all native Android apps work
- **No W^X violation** - the code is in read-only memory (APK)

## The Solution for Our Library

We have two options:

### Option 1: Rebuild FFmpeg as a Shared Library
```bash
# Rebuild FFmpeg with JNI wrapper
./configure \
    --target-os=android \
    --enable-shared \
    --disable-static \
    --disable-programs \  # Don't build ffmpeg executable
    --enable-jni \
    --prefix=$PREFIX

# Create JNI wrapper that exports ffmpeg_main()
```

### Option 2: Use Target SDK 28 (Current Solution)
- Already implemented
- Works on most devices
- Simpler but may not work on all Android 10+ devices

## Technical Details

FFmpeg-kit's structure:
```
libffmpegkit.so
├── FFmpeg code (libavcodec, libavformat, etc.)
├── JNI wrapper functions
└── Exports: Java_com_arthenica_ffmpegkit_*

When called:
Java → JNI → ffmpeg_main() → FFmpeg processing → Return to Java
```

No process spawning, no exec, just function calls within the app's process.

## Summary

FFmpeg-kit works because it **doesn't execute FFmpeg as a separate process**. It runs FFmpeg's code **inside the app's process** through JNI, which Android allows on all versions.