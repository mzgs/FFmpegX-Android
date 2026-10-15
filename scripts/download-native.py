#!/usr/bin/env python3
"""Install the checksum-pinned FFmpeg executables and license notices."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')
BINARIES = {'jniLibs/{}/{}'.format(abi, name) for abi in ABIS
            for name in ('libffmpeg_exec.so', 'libffprobe_exec.so')}


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def mkdir(path):
    # JitPack's legacy image predates pathlib's exist_ok and os.PathLike support.
    os.makedirs(str(path), exist_ok=True)


def install(archive, expected, destination):
    if checksum(archive) != expected:
        raise ValueError('Native archive checksum mismatch; nothing was installed')
    mkdir(destination)
    with tempfile.TemporaryDirectory(prefix='ffmpeg-native-', dir=str(destination.parent)) as directory:
        stage = pathlib.Path(directory)
        seen = set()
        with tarfile.open(str(archive), 'r:gz') as bundle:
            for member in bundle:
                path = pathlib.PurePosixPath(member.name)
                allowed = (path.as_posix() in BINARIES or
                           path.parts[:2] == ('assets', 'ffmpeg-licenses'))
                if path.is_absolute() or '..' in path.parts or not allowed:
                    # The two parent directories are emitted by tar when packaging.
                    if member.isdir() and member.name.rstrip('/') in ('jniLibs', 'assets'):
                        continue
                    if member.isdir() and path.as_posix() in {'jniLibs/{}'.format(abi) for abi in ABIS}:
                        continue
                    raise ValueError('Unexpected archive path: {}'.format(member.name))
                if member.isdir():
                    continue
                if not member.isfile() or path.as_posix() in seen:
                    raise ValueError('Unexpected archive entry: {}'.format(member.name))
                seen.add(path.as_posix())
                target = stage / path
                mkdir(target.parent)
                with bundle.extractfile(member) as source, target.open('wb') as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o755 if path.as_posix() in BINARIES else 0o644)
        if not BINARIES <= seen:
            raise ValueError('Missing native binaries: {}'.format(sorted(BINARIES - seen)))
        with (ROOT / 'scripts/native-sources.txt').open() as sources:
            lines = sources.read().splitlines()
        for line in lines:
            name = line.split()[0]
            if not any(p.startswith('assets/ffmpeg-licenses/{}/'.format(name)) for p in seen):
                raise ValueError('Missing license notices for {}'.format(name))
        subprocess.check_call([sys.executable, '-E', str(ROOT / 'scripts/verify-native.py')] +
                              [str(stage / 'jniLibs' / abi) for abi in ABIS])
        for name in ('jniLibs', 'assets/ffmpeg-licenses'):
            target = destination / name
            mkdir(target.parent)
            if target.exists():
                shutil.rmtree(str(target))
            shutil.move(str(stage / name), str(target))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=pathlib.Path, help='Use a local copy of the pinned archive')
    args = parser.parse_args()
    with (ROOT / 'scripts/native-release.json').open() as manifest:
        pin = json.load(manifest)
    archive = args.archive
    if archive is None:
        cache = ROOT / '.native-build/prebuilt'
        mkdir(cache)
        archive = cache / (pin['sha256'] + '.tar.gz')
        if not archive.exists() or checksum(archive) != pin['sha256']:
            partial = archive.with_suffix('.part')
            subprocess.check_call(['curl', '--fail', '--location', '--retry', '3', '--proto', '=https',
                                   '--output', str(partial), pin['url']])
            if checksum(partial) != pin['sha256']:
                partial.unlink()
                raise ValueError('Downloaded native archive checksum mismatch')
            os.replace(str(partial), str(archive))
    install(archive, pin['sha256'], ROOT / 'ffmpegx/src/main')
    print('Installed pinned FFmpeg binaries and license notices for all four ABIs')


if __name__ == '__main__':
    main()
