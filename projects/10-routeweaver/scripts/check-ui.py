#!/usr/bin/env python3
"""Browser-only UI regressions with deterministic API fixtures; not a service integration test.

Requires the existing Playwright Python package and installed Chromium. Does not download browsers.
"""
import asyncio
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shutil
from threading import Thread

from playwright.async_api import async_playwright, expect

STATIC = Path(__file__).resolve().parents[1] / 'routeweaver-service/src/main/resources/static'


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def end_headers(self):
        self.send_header('Content-Security-Policy',
                         "default-src 'self'; object-src 'none'; base-uri 'none'; "
                         "frame-ancestors 'none'; form-action 'self'")
        super().end_headers()


def fixtures():
    vehicle = {'id': 'van-1', 'failed': False, 'onboard': [], 'currentLocation': 0}
    problem = {
        'id': 'routeweaver-60-42', 'version': 1, 'serviceZone': 'America/Halifax',
        'matrix': {'version': 'synthetic-v1'}, 'objective': {'version': 'v1'},
        'vehicles': [vehicle], 'depot': 0,
    }
    validation = {
        'violations': [], 'candidate': {'inputHash': 'fixture-hash', 'unassigned': {'stranded-job': 'UNASSIGNED', '<img src=x onerror=alert(1)>': 'UNASSIGNED'}},
        'score': {'hard': 0, 'unassignedPriority': 2, 'operatingCost': 10, 'disruption': 0},
        'explanations': {'stranded-job': 'STRANDED_CARGO', '<img src=x onerror=alert(1)>': 'SKILLS_OR_VEHICLE_UNAVAILABLE'},
        'timelines': [{'vehicleId': 'van-1', 'returnedAt': '2026-10-20T13:00:00Z',
                       'stops': [{'jobId': '<img src=x onerror=alert(1)>', 'location': 1,
                                  'serviceStart': '2026-10-20T12:00:00Z'}]}],
    }
    plan = {'revision': 1, 'problemVersion': 1, 'solution': validation}
    job = {'id': 'fixture-job', 'problemVersion': 1, 'state': 'COMPLETED',
           'stopCondition': 'STEP_LIMIT', 'incumbent': validation}
    return problem, validation, plan, job


async def check(base):
    problem, validation, plan, job = fixtures()
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(
            executable_path=shutil.which('chromium') or shutil.which('chromium-browser'),
            headless=True, args=['--disable-dev-shm-usage'])
        try:
            page = await browser.new_page(viewport={'width': 1280, 'height': 1000})
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            hold_poll = False
            poll_started = asyncio.Event()
            release_poll = asyncio.Event()
            poll_requests = 0

            async def route_api(route):
                nonlocal poll_requests
                path = route.request.url.split(base, 1)[1]
                if path.endswith('/solve'):
                    value = dict(job, state='QUEUED', incumbent=None)
                elif '/solve-jobs/' in path and path.endswith('/baseline'):
                    value = validation
                elif '/solve-jobs/' in path:
                    poll_requests += 1
                    if hold_poll:
                        poll_started.set()
                        await release_poll.wait()
                    value = job
                elif path.endswith('/publish'):
                    value = dict(plan, revision=2)
                elif '/v1/problems/' in path:
                    value = problem
                elif '/v1/plans/' in path:
                    value = plan
                else:
                    raise AssertionError(path)
                await route.fulfill(status=200, content_type='application/json', body=json.dumps(value))

            await page.route('**/v1/**', route_api)
            await page.goto(base, wait_until='networkidle')
            assert await page.locator('#solve').is_disabled()
            assert await page.locator('#publish').is_disabled()
            await page.keyboard.press('Tab')
            assert await page.locator('.skip-link').evaluate('(element) => element === document.activeElement')
            await page.get_by_role('button', name='Load current published plan', exact=True).click()
            await expect(page.locator('#timeline tr')).to_have_count(1)
            await expect(page.locator('#solve')).to_be_enabled()
            assert '<img src=x onerror=alert(1)>' in await page.locator('#timeline').inner_text()
            assert await page.locator('#timeline img').count() == 0
            assert 'Stranded cargo' in await page.locator('#unassigned-jobs').inner_text()
            assert 'No available vehicle with the required skills' in await page.locator('#unassigned-jobs').inner_text()
            assert 'STRANDED_CARGO' not in await page.locator('#unassigned-jobs').inner_text()
            assert await page.locator('#unassigned-jobs img').count() == 0
            assert await page.locator('#map title').count() == 1
            await page.get_by_role('button', name='Solve active input', exact=True).click()
            await expect(page.locator('#publish')).to_be_enabled()
            score = json.loads(await page.locator('#score').text_content())
            assert score['baseline']['hard'] == 0
            assert 'Partially feasible' in await page.locator('#score-metrics').inner_text()
            await page.get_by_role('button', name='Publish validated incumbent', exact=True).click()
            await expect(page.locator('#notice')).to_contain_text('revision 2 published')
            assert len(await page.locator('#state').text_content()) < 500
            await page.set_viewport_size({'width': 390, 'height': 844})
            assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth')

            # A pending progress response must not revive a workspace after its identity changes.
            hold_poll = True
            await page.get_by_role('button', name='Solve active input', exact=True).click()
            await asyncio.wait_for(poll_started.wait(), timeout=10)
            requests_at_start = poll_requests
            await page.locator('#problem').fill('another-problem')
            await asyncio.sleep(0.75)
            assert poll_requests == requests_at_start, 'polls must not overlap a pending request'
            release_poll.set()
            await expect(page.locator('#state')).to_have_text('{}')
            assert await page.locator('#solve').is_disabled()
            assert await page.locator('#map polyline').count() == 0
            assert await page.evaluate('localStorage.length + sessionStorage.length') == 0
            assert not errors, errors
            print('PASS: keyboard access, mobile layout, safe text rendering, solve/publication states, '
                  'readable unassigned reasons, same-input baseline, serial polling, stale response isolation, no stored credentials')
        finally:
            await browser.close()


def main():
    if not (shutil.which('chromium') or shutil.which('chromium-browser')):
        raise SystemExit('Installed Chromium is required; no browser is downloaded.')
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(STATIC)))
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        asyncio.run(check(f'http://127.0.0.1:{server.server_port}'))
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == '__main__':
    main()
