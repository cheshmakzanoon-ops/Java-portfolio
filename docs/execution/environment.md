# Observed execution environment

2026-10-17: Linux x86_64; 5 available CPUs; approximately 33 GiB available RAM; 30 GiB available disk at preflight. Docker 28.4.0; hello-world image ran successfully. Runtime supports coordinator plus 6 workers; use two waves for 10 assignments. Initial checkout was branch work at 8284f1572045523d933502a24e5634b1c895f30b, switched to new local main preserving the commit. Remote main matched. Java 25 Temurin 25.0.4.1+1, Maven 3.10.0 downloaded with published checksums verified.

Cgroup inspection: memory.max=34359738368 (32 GiB), cpu.max=400000/100000 (4 CPU quota). nproc reports5 logical CPUs; CPU-heavy tests must respect the4-CPU quota.
