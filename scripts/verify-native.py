#!/usr/bin/env python3
"""Check executable ELF ABI, interpreter, dependencies and 16 KiB LOAD alignment."""
import pathlib
import struct
import sys

MACHINES = {'armeabi-v7a': 40, 'arm64-v8a': 183, 'x86': 3, 'x86_64': 62}
SYSTEM_LIBS = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libz.so', 'libandroid.so', 'libmediandk.so', 'libcamera2ndk.so'}


def verify(path):
    with path.open('rb') as stream:
        data = stream.read()
    assert data[:4] == b'\x7fELF', '{}: not ELF'.format(path)
    assert data[5] == 1, '{}: expected little endian'.format(path)
    expected_class = 2 if path.parent.name in ('arm64-v8a', 'x86_64') else 1
    assert data[4] == expected_class, '{}: wrong ELF class'.format(path)
    wide = data[4] == 2
    header = struct.unpack_from('<HHIQQQIHHHHHH' if wide else '<HHIIIIIHHHHHH', data, 16)
    assert header[0] == 3 and header[1] == MACHINES[path.parent.name], '{}: wrong type or ABI'.format(path)
    assert header[3] != 0, '{}: not an executable'.format(path)
    offset, size, count = header[4], header[8], header[9]
    loads, dynamic, interpreter = [], None, None
    for i in range(count):
        p = struct.unpack_from('<IIQQQQQQ' if wide else '<IIIIIIII', data, offset + i * size)
        kind = p[0]
        start, address, length, alignment = (p[2], p[3], p[5], p[7]) if wide else (p[1], p[2], p[4], p[7])
        if kind == 1:
            assert alignment >= 16384 and (start - address) % 16384 == 0, '{}: unaligned LOAD'.format(path)
            loads.append((address, start, length))
        elif kind == 2:
            dynamic = (start, length)
        elif kind == 3:
            interpreter = data[start:start + length].rstrip(b'\0')
    assert loads and interpreter == (b'/system/bin/linker64' if wide else b'/system/bin/linker'), '{}: missing Android interpreter'.format(path)
    assert dynamic, '{}: missing dynamic section'.format(path)
    entries = [struct.unpack_from('<qQ' if wide else '<iI', data, pos)
               for pos in range(dynamic[0], dynamic[0] + dynamic[1], 16 if wide else 8)]
    assert not any(tag == 22 or tag == 30 and value & 4 for tag, value in entries), '{}: text relocations'.format(path)
    strings_address = next(value for tag, value in entries if tag == 5)
    strings_offset = next(start + strings_address - address for address, start, length in loads
                          if address <= strings_address < address + length)
    needed = []
    for tag, value in entries:
        if tag == 1:
            end = data.index(b'\0', strings_offset + value)
            name = data[strings_offset + value:end].decode()
            assert name in SYSTEM_LIBS, '{}: unpackaged dependency {}'.format(path, name)
            needed.append(name)
    print('{}/{}: ELF executable, 16 KiB aligned; {}'.format(path.parent.name, path.name, ', '.join(needed)))


if __name__ == '__main__':
    roots = [pathlib.Path(p) for p in sys.argv[1:]] or [pathlib.Path('ffmpegx/src/main/jniLibs') / abi for abi in MACHINES]
    for root in roots:
        for name in ('libffmpeg_exec.so', 'libffprobe_exec.so'):
            verify(root / name)
