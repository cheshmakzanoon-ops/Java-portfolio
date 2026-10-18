#!/usr/bin/env python3
"""Actual local dispatcher UI checks against the live service; no mocked requests or Maven."""
import argparse
import datetime
import json
from pathlib import Path
import shutil
from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://127.0.0.1:19000')
parser.add_argument('--problem', default='routeweaver-60-42')
parser.add_argument('--expected-version', type=int, default=2)
parser.add_argument('--output')
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
runtime = project / '.runtime'
output = Path(args.output) if args.output else runtime / 'browser-check' / datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
output.mkdir(parents=True, exist_ok=False)
source = (runtime / 'running-source').read_text().strip()
chromium = shutil.which('chromium') or shutil.which('chromium-browser')
if not chromium:
    raise SystemExit('Installed Chromium is required for the real UI check; no browser is downloaded')
result = {'schemaVersion': 1, 'project': 'routeweaver', 'source': source, 'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'boundary': 'actual local Chromium rendering and live HTTP solve/publication'}
try:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=chromium, headless=True, args=['--disable-dev-shm-usage'])
        try:
            page = browser.new_page(viewport={'width': 1440, 'height': 1100})
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(args.base, wait_until='networkidle')
            page.locator('#problem').fill(args.problem)
            page.get_by_role('button', name='Load current published plan', exact=True).click()
            page.wait_for_function("document.querySelectorAll('#timeline tr').length === 6")
            initial = json.loads(page.locator('#state').inner_text())
            score = json.loads(page.locator('#score').inner_text())
            assert score['inputVersion'] == args.expected_version and score['score']['hard'] == 0 and score['feasibility'] == 'PARTIAL_FEASIBLE', score
            assert 'STRANDED_CARGO' in page.locator('#unassigned').inner_text()
            assert 'URGENT' in page.locator('#timeline').inner_text()
            assert page.locator('#map polyline').count() == 6
            page.get_by_role('button', name='Solve active input', exact=True).click()
            page.wait_for_function("document.querySelector('#state').textContent.includes('COMPLETED')", timeout=40000)
            solved = json.loads(page.locator('#score').inner_text())
            assert 'baseline' in solved and solved['score']['hard'] == 0
            page.get_by_role('button', name='Publish validated incumbent', exact=True).click()
            page.wait_for_function("expected => { try { return JSON.parse(document.querySelector('#state').textContent).planRevision === expected; } catch { return false; } }", arg=initial['planRevision'] + 1)
            status_text = page.locator('#state').inner_text()
            assert len(status_text) < 500, 'publication status must not dump full route/validation internals'
            assert not errors, errors
            page.screenshot(path=str(output / 'dispatcher.png'), full_page=True)
            (output / 'dispatcher.html').write_text(page.content())
            result.update({'status': 'PASS', 'browser': browser.version, 'score': solved, 'publicationStatus': json.loads(status_text), 'statusCharacters': len(status_text), 'checks': ['published version/zero hard/partial', 'six route polylines', 'six timelines', 'visible stranded cargo', 'visible urgent job', 'same-input baseline', 'live UI solve', 'publication CAS', 'compact status', 'no page errors']})
        finally:
            browser.close()
except BaseException as failure:
    result.update({'status': 'FAIL', 'error': str(failure)})
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    raise
(output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'status': 'PASS', 'source': source, 'artifact': str(output / 'result.json')}))
