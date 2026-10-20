#!/usr/bin/env python3
"""Exercise the documented ingestion, pagination, suggestions and reindex API locally."""

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--url", default="http://127.0.0.1:18700")
    parser.add_argument("--repository", default="public")
    parser.add_argument("--source-path", default="example")
    args = parser.parse_args()
    endpoint = urllib.parse.urlsplit(args.url)
    if (endpoint.scheme != "http" or endpoint.hostname not in {"127.0.0.1", "localhost", "::1"}
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment
            or endpoint.path not in {"", "/"}):
        parser.error("this development example requires a loopback HTTP origin")
    token = args.token_file.read_text(encoding="utf-8").strip()

    def request(method, route, body=None):
        encoded = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(args.url.rstrip("/") + route, encoded, method=method,
                                     headers={"Authorization": "Bearer " + token,
                                              "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                return json.load(response)
        except urllib.error.HTTPError as failure:
            raise RuntimeError(f"{method} {route} returned HTTP {failure.code}") from None

    def completed(job):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            status = request("GET", "/v1/ingestions/" + job["id"])
            if status["state"] == "COMPLETE":
                return status
            if status["state"] in {"FAILED", "CANCELLED"}:
                raise RuntimeError("ingestion did not complete: " + status["state"])
            time.sleep(.1)
        raise TimeoutError("ingestion exceeded the example's 30-second deadline")

    job = request("POST", "/v1/ingestions", {
        "repository": args.repository, "revision": "example-v1",
        "idempotencyKey": "http-example-v1", "path": args.source_path,
        "archive": False, "complete": False, "deletions": []})
    completed(job)
    first = request("POST", "/v1/search", {
        "text": "atomic", "pageSize": 1, "cursor": None,
        "filters": {"repository": args.repository}})
    if not first["results"]:
        raise AssertionError("the prepared example must contain an atomic match")
    pages = 1
    cursor = first["cursor"]
    while cursor is not None:
        page = request("POST", "/v1/search", {
            "text": "atomic", "pageSize": 1, "cursor": cursor,
            "filters": {"repository": args.repository}})
        if page["generationId"] != first["generationId"]:
            raise AssertionError("pagination changed generations")
        pages += 1
        if pages > 500:
            raise AssertionError("pagination exceeded the candidate window")
        cursor = page["cursor"]
    suggestions = request("POST", "/v1/suggest", {"prefix": "atom"})
    if "atomic" not in suggestions:
        raise AssertionError("the prepared example must suggest its authorized atomic term")
    completed(request("POST", "/v1/ingestions/" + job["id"] + "/resume"))
    completed(request("POST", "/v1/reindexes"))
    metrics = request("GET", "/v1/metrics")
    print(json.dumps({"job": job["id"], "documents": first["totalHits"], "pages": pages,
                      "suggestions": suggestions, "metrics": metrics}, sort_keys=True))


if __name__ == "__main__":
    main()
