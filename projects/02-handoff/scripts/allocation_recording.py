"""Read exact plain or gzip-compressed JFR JSON; ambiguity and corruption fail closed."""
import gzip
import json
import pathlib


def load_allocation_events(directory):
    directory = pathlib.Path(directory)
    plain = directory / 'allocation-samples.json'
    compressed = directory / 'allocation-samples.json.gz'
    present = [path for path in (plain, compressed) if path.exists()]
    if len(present) != 1:
        raise ValueError('Exactly one plain or gzip allocation recording is required')
    path = present[0]
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError('Allocation recording must be a nonempty file')
    opener = gzip.open if path == compressed else open
    # json.load reads through EOF, so gzip truncation/CRC errors are propagated.
    with opener(path, 'rt', encoding='utf-8') as stream:
        events = json.load(stream)['recording']['events']
    if not isinstance(events, list):
        raise ValueError('Allocation recording events must be an array')
    return events
