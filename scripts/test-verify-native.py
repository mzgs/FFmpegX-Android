#!/usr/bin/env python3
import importlib.util
import pathlib
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('verify_native', pathlib.Path(__file__).with_name('verify-native.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class NativeVerificationTest(unittest.TestCase):
    def test_rejects_wrong_machine_alignment_and_missing_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'arm64-v8a' / 'libffmpeg_exec.so'
            path.parent.mkdir()
            data = bytearray(1024)
            data[:7] = b'\x7fELF\x02\x01\x01'
            struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, 183, 1, 128, 64, 0, 0, 64, 56, 3, 0, 0, 0)
            struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, 1024, 1024, 16384)
            struct.pack_into('<IIQQQQQQ', data, 120, 3, 4, 400, 400, 0, 21, 21, 1)
            struct.pack_into('<IIQQQQQQ', data, 176, 2, 4, 448, 448, 0, 48, 48, 8)
            data[400:421] = b'/system/bin/linker64\0\0'
            struct.pack_into('<qQqQqQ', data, 448, 5, 512, 1, 0, 0, 0)
            data[512:520] = b'libc.so\0'
            path.write_bytes(data)
            verifier.verify(path)
            for offset, form, wrong in ((18, '<H', 62), (112, '<Q', 4096)):
                broken = data.copy()
                struct.pack_into(form, broken, offset, wrong)
                path.write_bytes(broken)
                with self.assertRaises(AssertionError): verifier.verify(path)
            data[512:531] = b'libc++_shared.so\0\0\0\0'
            path.write_bytes(data)
            with self.assertRaises(AssertionError): verifier.verify(path)


if __name__ == '__main__':
    unittest.main()
