# Validation environment

The managed Linux x86-64 workspace provides a four-CPU cgroup quota and 32 GiB memory limit. The installed toolchain is Eclipse Temurin 25.0.4.1+1 and checksum-pinned Maven 3.10.0. Docker 28.4.0 and Compose v2.40.3 are available. Disk space is ephemeral and must be checked before large recordings or performance studies.

Use the existing checkout and source `scripts/cloud-env.sh` before Maven. The script prepares secure proxy settings and system CA trust without copying credentials into the repository. Runtime access and credentials are supplied by environment settings; do not infer current readiness from a historical observation.

The Maven wrapper admits at most three builds through a FIFO lock queue. Application Docker stacks are limited to two at once. Performance measurements require a quiet interval with unrelated builds and services stopped. Native Windows and macOS execution remain unverified.

Historical records in this directory describe their named source and run only. Raw data under ignored `.work/` paths may not exist in a new checkout. Benchmark and security failures remain valid historical outcomes; an environment snapshot or later source change does not turn them into a pass. See [development](../development.md) for current commands and [release criteria](../release.md) for outstanding gates.
