"""Use only runtime dependencies packaged by Maven, never plugin/test jars from the Maven cache."""
import hashlib
import pathlib
import sys
import zipfile

archive = pathlib.Path(sys.argv[1])
parent = pathlib.Path(sys.argv[2])
if not archive.is_file():
    raise SystemExit('Build verification executable JAR with the project reactor first')
directory = parent / 'classpath' / hashlib.sha256(archive.read_bytes()).hexdigest()
marker = directory / '.complete'
if not marker.exists():
    directory.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for item in z.infolist():
            name = pathlib.PurePosixPath(item.filename)
            parts = name.parts
            if len(parts)<3 or parts[0]!='BOOT-INF' or parts[1] not in ('classes','lib') or item.is_dir():
                continue
            if '..' in parts or name.is_absolute():
                raise SystemExit('Unsafe executable JAR entry')
            destination = directory.joinpath(parts[1],*parts[2:])
            destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_bytes(z.read(item))
    marker.write_text('verified ZIP CRCs; owned Maven runtime graph\n')
print(str(directory/'classes') + ':' + str(directory/'lib'/'*'))
