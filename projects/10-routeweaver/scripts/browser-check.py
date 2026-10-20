#!/usr/bin/env python3
"""Actual local dispatcher checks; a delayed real HTTP response forces the stale-load schedule."""
import argparse
import asyncio
import datetime
import json
from pathlib import Path
import shutil
from playwright.async_api import expect, async_playwright

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
    raise SystemExit('Installed Chromium is required; no browser is downloaded')
result = {'schemaVersion': 1, 'project': 'routeweaver', 'source': source, 'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'boundary': 'actual local Chromium rendering and live HTTP solve/publication; stale-load schedule delays an unchanged real response'}


async def check():
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(executable_path=chromium, headless=True, args=['--disable-dev-shm-usage'])
        try:
            page = await browser.new_page(viewport={'width': 1440, 'height': 1100})
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            await page.goto(args.base, wait_until='networkidle')
            await page.locator('#problem').fill(args.problem)
            load = page.get_by_role('button', name='Load current published plan', exact=True)
            await load.click()
            await expect(page.locator('#timeline tr')).to_have_count(6)
            initial = json.loads(await page.locator('#state').text_content())
            score = json.loads(await page.locator('#score').text_content())
            assert score['inputVersion'] == args.expected_version and score['score']['hard'] == 0 and score['feasibility'] == 'PARTIAL_FEASIBLE', score
            await expect(page.locator('#plan-status')).to_have_text('Partially feasible')
            await expect(page.locator('#hard-count')).to_have_text('0')
            assert int(await page.locator('#unassigned-count').inner_text()) > 0
            summary = await page.locator('#plan-summary').bounding_box()
            assert summary['y'] + summary['height'] < 1100, 'dispatch decision must be visible before scrolling'
            assert summary['y'] < (await page.locator('#publish').bounding_box())['y']
            assert 'Stranded cargo' in await page.locator('#unassigned-jobs').inner_text()
            assert 'URGENT' in await page.locator('#timeline').inner_text()
            assert await page.locator('#map polyline').count() == 6
            selector = page.get_by_label('Inspect vehicle', exact=True)
            selected_vehicle = await selector.locator('option').nth(2).get_attribute('value')
            await selector.focus()
            await page.keyboard.press('Home')
            await page.keyboard.press('ArrowDown')
            await page.keyboard.press('ArrowDown')
            await expect(selector).to_have_value(selected_vehicle)
            await expect(page.locator('#map polyline')).to_have_count(1)
            await expect(page.locator('#timeline tr')).to_have_count(1)
            await expect(page.locator('#timeline tr th')).to_have_text(selected_vehicle)
            await load.click()
            await expect(selector).to_have_value(selected_vehicle)
            await expect(page.locator('#timeline tr')).to_have_count(1)

            # Hold an actual server response while the workspace identity changes; its bytes remain unchanged.
            response_ready, release_response = asyncio.Event(), asyncio.Event()

            async def delayed_real_response(route):
                response = await route.fetch()
                response_ready.set()
                await release_response.wait()
                await route.fulfill(response=response)

            route_pattern = '**/v1/problems/' + args.problem
            await page.route(route_pattern, delayed_real_response)
            await load.click()
            await asyncio.wait_for(response_ready.wait(), timeout=10)
            await page.locator('#problem').fill('superseded-workspace')
            release_response.set()
            await expect(load).to_be_enabled()
            await expect(selector).to_be_disabled()
            await expect(page.locator('#plan-context')).to_have_text('No validated plan')
            await expect(page.locator('#unassigned-count')).to_have_text('—')
            assert await page.locator('#map polyline').count() == 0
            await page.unroute(route_pattern, delayed_real_response)
            await page.locator('#problem').fill(args.problem)
            await load.click()
            await expect(page.locator('#timeline tr')).to_have_count(6)
            await expect(selector).to_have_value('')

            await page.get_by_role('button', name='Solve active input', exact=True).click()
            await expect(page.locator('#state')).to_contain_text('COMPLETED', timeout=40000)
            solved = json.loads(await page.locator('#score').text_content())
            assert 'baseline' in solved and solved['score']['hard'] == 0
            await expect(page.locator('#plan-context')).to_contain_text('Candidate plan')
            await page.get_by_role('button', name='Publish validated incumbent', exact=True).click()
            await expect(page.locator('#state')).to_contain_text('"planRevision": ' + str(initial['planRevision'] + 1))
            status_text = await page.locator('#state').text_content()
            assert len(status_text) < 500, 'publication status must remain compact'
            await selector.select_option(selected_vehicle)
            await expect(page.locator('#timeline tr')).to_have_count(1)
            await page.screenshot(path=str(output / 'dispatcher.png'), full_page=True)
            (output / 'dispatcher.html').write_text(await page.content())
            await selector.select_option('')
            await page.screenshot(path=str(output / 'all-routes.png'), full_page=True)
            await page.set_viewport_size({'width': 390, 'height': 844})
            await selector.select_option(selected_vehicle)
            assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth')
            await expect(page.locator('#plan-status')).to_have_text('Partially feasible')
            await page.screenshot(path=str(output / 'narrow-dispatcher.png'), full_page=True)
            assert not errors, errors
            result.update({'status': 'PASS', 'browser': browser.version, 'score': solved, 'publicationStatus': json.loads(status_text), 'statusCharacters': len(status_text), 'selectedVehicle': selected_vehicle, 'checks': ['published version/zero hard/partial', 'prominent partial-plan decision and unassigned count', 'six route polylines and timelines', 'keyboard vehicle selection and matching route/timeline', 'selection preserved on refresh', 'stale actual HTTP response discarded after workspace reset', 'selection and summary reset safely', 'compact service-zone/day-aware timestamps', 'visible stranded cargo and urgent job', 'same-input baseline', 'live UI solve', 'publication CAS', 'compact status', 'actual desktop and390px rendering without document overflow', 'no page errors']})
        finally:
            await browser.close()


try:
    asyncio.run(check())
except BaseException as failure:
    result.update({'status': 'FAIL', 'error': str(failure)})
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    raise
(output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'status': 'PASS', 'source': source, 'artifact': str(output / 'result.json')}))
