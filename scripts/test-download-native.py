#!/usr/bin/env python3
"""Regression checks for checksum validation and safe native installation."""
import hashlib
import importlib.util
import io
import pathlib
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('download_native', pathlib.Path(__file__).with_name('download-native.py'))
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class NativeDownloadTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.archive = self.root / 'native.tar.gz'
        self.destination = self.root / 'main'
        self.files = {name: b'native fixture' for name in native.BINARIES}
        for line in (native.ROOT / 'scripts/native-sources.txt').read_text().splitlines():
            self.files[f'assets/ffmpeg-licenses/{line.split()[0]}/COPYING'] = b'license fixture'

    def pack(self, extra=None):
        with tarfile.open(self.archive, 'w:gz') as bundle:
            for name, contents in self.files.items():
                member = tarfile.TarInfo(name)
                member.size = len(contents)
                bundle.addfile(member, io.BytesIO(contents))
            if extra:
                bundle.addfile(extra)
        return hashlib.sha256(self.archive.read_bytes()).hexdigest()

    @mock.patch.object(native.subprocess, 'run')
    def test_installs_all_binaries_and_notices_after_validation(self, verify):
        native.install(self.archive, self.pack(), self.destination)
        verify.assert_called_once()
        for name in self.files:
            self.assertEqual((self.destination / name).read_bytes(), self.files[name])
        binary = self.destination / next(iter(native.BINARIES))
        self.assertEqual(binary.stat().st_mode & 0o777, 0o755)

    @mock.patch.object(native.subprocess, 'run')
    def test_checksum_mismatch_does_not_touch_installed_files(self, verify):
        self.pack()
        self.destination.mkdir()
        sentinel = self.destination / 'keep'
        sentinel.write_text('existing installation')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            native.install(self.archive, '0' * 64, self.destination)
        self.assertEqual(sentinel.read_text(), 'existing installation')
        verify.assert_not_called()

    def test_rejects_path_traversal_and_symlinks(self):
        for name, kind in (('../outside', tarfile.REGTYPE),
                           ('/tmp/outside', tarfile.REGTYPE),
                           ('assets/ffmpeg-licenses/ffmpeg/link', tarfile.SYMTYPE)):
            member = tarfile.TarInfo(name)
            member.type = kind
            member.linkname = '/tmp/outside' if kind == tarfile.SYMTYPE else ''
            with self.subTest(name=name), self.assertRaises(ValueError):
                native.install(self.archive, self.pack(member), self.destination)
        self.assertFalse((self.root / 'outside').exists())

    def test_rejects_missing_architecture_and_missing_notices(self):
        for name in (next(iter(native.BINARIES)), 'assets/ffmpeg-licenses/ffmpeg/COPYING'):
            contents = self.files.pop(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Missing'):
                native.install(self.archive, self.pack(), self.destination)
            self.files[name] = contents

    @mock.patch.object(native.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'verify-native'))
    def test_invalid_elf_keeps_existing_installation(self, verify):
        existing = self.destination / next(iter(native.BINARIES))
        existing.parent.mkdir(parents=True)
        existing.write_bytes(b'previous installation')
        with self.assertRaises(subprocess.CalledProcessError):
            native.install(self.archive, self.pack(), self.destination)
        self.assertEqual(existing.read_bytes(), b'previous installation')


class SourceCacheTest(unittest.TestCase):
    def test_new_pin_refreshes_both_archive_and_extracted_sources(self):
        script = (native.ROOT / 'build-ffmpeg.sh').read_text()
        start = script.index('while read -r name url; do')
        end_marker = 'done < "$ROOT/scripts/native-sources.txt"'
        stage = script[start:script.index(end_marker) + len(end_marker)]
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            downloads = root / '.native-build/downloads'
            downloads.mkdir(parents=True)
            source = root / '.native-build/sources/sample'
            source.mkdir(parents=True)
            (source / '.extracted').touch()
            (source / 'version.txt').write_text('old version')
            cached = downloads / 'sample.tar'
            cached.write_bytes(b'old archive')
            archive = root / 'new.tar.gz'
            with tarfile.open(archive, 'w:gz') as bundle:
                contents = b'new version'
                member = tarfile.TarInfo('sample/version.txt')
                member.size = len(contents)
                bundle.addfile(member, io.BytesIO(contents))
            digest = native.checksum(archive)
            (scripts / 'native-sources.txt').write_text(f'sample {archive.as_uri()}\n')
            (scripts / 'native-sources.sha256').write_text(f'{digest}  sample.tar\n')
            command = 'set -euo pipefail\nROOT=$1\nBUILD="$ROOT/.native-build"\n' + stage
            subprocess.run(['bash', '-c', command, 'cache-test', str(root)],
                           check=True, capture_output=True)
            self.assertEqual(native.checksum(cached), digest)
            self.assertEqual((source / 'version.txt').read_text(), 'new version')
            self.assertEqual((source / '.extracted').read_text().strip(), digest)


if __name__ == '__main__':
    unittest.main()
