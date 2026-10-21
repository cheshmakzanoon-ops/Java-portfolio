#!/usr/bin/env python3
"""Exercise generated offline reports in Chromium; failures remain nonzero."""
import argparse
import hashlib
import json
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import threading
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright


def main():
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixtures', type=Path, default=project / 'report/target/ui-fixture')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--chromium', default='/usr/bin/chromium')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    observations = []
    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, format, *values):
            pass
        def do_GET(self):
            if self.path == '/favicon.ico':
                self.send_response(204)
                self.end_headers()
            else:
                super().do_GET()
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(Handler, directory=str(args.fixtures.resolve(strict=True))))
    serving = threading.Thread(target=server.serve_forever, daemon=True)
    serving.start()
    address = 'http://127.0.0.1:' + str(server.server_port)
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(executable_path=args.chromium, headless=True,
                                                args=['--disable-dev-shm-usage'])
            try:
                for name, viewport in [('desktop', {'width': 1280, 'height': 960}),
                                       ('mobile', {'width': 390, 'height': 844})]:
                    context = browser.new_context(viewport=viewport)
                    try:
                        page = context.new_page()
                        page.set_default_timeout(5000)
                        errors, remote = [], []
                        page.on('pageerror', lambda error: errors.append(str(error)))
                        page.on('console', lambda message: errors.append(message.text)
                                if message.type == 'error' else None)
                        page.on('request', lambda request: remote.append(request.url)
                                if urlsplit(request.url).scheme in ('http', 'https')
                                and urlsplit(request.url).hostname not in ('127.0.0.1', 'localhost') else None)
                        for file in ['report.html', 'comparison.html']:
                            epoch_cells = []
                            path = (args.fixtures / file).resolve(strict=True)
                            page.goto(address + '/' + file, wait_until='domcontentloaded', timeout=20000)
                            expect(page.locator('main h1')).to_be_visible()
                            expect(page.locator('nav')).to_be_visible()
                            assert page.locator('script,img,iframe,form').count() == 0
                            assert '<img src=x onerror=' in page.locator('main').inner_text()
                            page.keyboard.press('Tab')
                            expect(page.locator('.skip-link')).to_be_focused()
                            page.keyboard.press('Enter')
                            expect(page.locator('main')).to_be_focused()
                            links = page.locator('nav a').all()
                            for link in links:
                                fragment = link.get_attribute('href')
                                assert fragment and re.fullmatch(r'#[a-z-]+', fragment)
                                expect(page.locator(fragment)).to_have_count(1)
                                link.click()
                                expect(page).to_have_url(re.compile(re.escape(fragment) + '$'))
                                expect(page.locator(fragment)).to_be_in_viewport()
                            tables = page.locator('.table-scroll')
                            assert tables.count() > 0
                            assert page.locator('th:not([scope="col"])').count() == 0
                            overflow = page.evaluate('document.documentElement.scrollWidth > window.innerWidth + 1')
                            assert not overflow, 'report content overflows the viewport'
                            local_scroll = tables.first.evaluate('(element) => element.scrollWidth > element.clientWidth')
                            if name == 'mobile':
                                assert local_scroll, 'wide report tables need their own scroll region'
                                tables.first.focus()
                                page.keyboard.press('ArrowRight')
                                for attempt in range(20):
                                    if tables.first.evaluate('(element) => element.scrollLeft') > 0:
                                        break
                                    page.wait_for_timeout(50)
                                assert tables.first.evaluate('(element) => element.scrollLeft') > 0
                            if file == 'report.html':
                                epoch_cells = page.locator('td.epoch-nanos').evaluate_all(
                                    'elements => elements.map(element => {const range = document.createRange();'
                                    'range.selectNodeContents(element); return {text: element.textContent,'
                                    'width: element.clientWidth, lines: range.getClientRects().length,'
                                    'contentFits: element.scrollWidth <= element.clientWidth};})')
                                assert epoch_cells, 'timeline must expose epoch values'
                                assert all(cell['lines'] == 1 and cell['contentFits'] for cell in epoch_cells), epoch_cells
                                details = page.locator('details').first
                                details.locator('summary').first.click()
                                assert not details.evaluate('(element) => element.open')
                            assert not errors, errors
                            assert not remote, remote
                            screenshot = args.output / (name + '-' + file.replace('.html', '.png'))
                            page.screenshot(path=str(screenshot), full_page=True, timeout=20000)
                            observations.append({'viewport': name, 'report': file,
                                                 'html_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                                                 'navigation_targets': len(links),
                                                 'tables': tables.count(), 'global_overflow': overflow,
                                                 'local_table_scroll': local_scroll,
                                                 'epoch_cells': epoch_cells,
                                                 'console_errors': list(errors), 'remote_requests': list(remote),
                                                 'screenshot_sha256': hashlib.sha256(screenshot.read_bytes()).hexdigest()})
                    finally:
                        context.close()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        serving.join(2)
    result = {'passed': True, 'browser': args.chromium, 'observations': observations}
    (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
