#!/usr/bin/env python3
"""Read the current run journal without opening or changing recordings."""
from pathlib import Path
import json
journal=Path('/workspace/Java-portfolio/.work/A08/reference-study-8/run-order.json')
rows=json.loads(journal.read_text())
completed=[row for row in rows if 'exitCode' in row]
print(json.dumps({'completed':len(completed),'active':[row['id'] for row in rows if 'exitCode' not in row],'failed':[row['id'] for row in completed if row['exitCode']]}))
