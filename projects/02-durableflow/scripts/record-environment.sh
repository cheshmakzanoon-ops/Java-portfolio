#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
python3 - "$PROJECT" <<'PY'
import pathlib,hashlib,json,platform,subprocess,datetime,os
p=pathlib.Path(__import__('sys').argv[1]); h=hashlib.sha256()
for f in sorted(p.rglob('*')):
 if f.is_file() and 'target' not in f.parts and '.jqwik-database' not in f.parts and '__pycache__' not in f.parts and 'docs' not in f.relative_to(p).parts and 'evidence' not in f.relative_to(p).parts and f.name!='README.md':
  h.update(str(f.relative_to(p)).encode());h.update(b'\0');h.update(f.read_bytes());h.update(b'\0')
cpu=next((l.split(':',1)[1].strip() for l in pathlib.Path('/proc/cpuinfo').read_text().splitlines() if l.startswith('model name')),'unknown')
limits={}
for name in ['memory.max','cpu.max']:
 f=pathlib.Path('/sys/fs/cgroup')/name
 if f.exists():limits[name]=f.read_text().strip()
revision=subprocess.run(['git','rev-parse','HEAD'],cwd=p,capture_output=True,text=True,check=True).stdout.strip()
data=dict(rootPomSha256=hashlib.sha256((p.parents[1]/'pom.xml').read_bytes()).hexdigest(),gitRevision=revision,timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),os=platform.platform(),cpu=cpu,hostVisibleProcessors=os.cpu_count(),jvmSystemSettings=subprocess.run(['java','-XshowSettings:system','-version'],capture_output=True,text=True).stderr.strip(),cgroupLimits=limits,jdk=subprocess.run(['java','-version'],capture_output=True,text=True).stderr.strip(),jvmFlags='workers/provider/schedulers -Xmx128m; API -Xmx256m; driver -Xmx512m',sourceFingerprint='sha256:'+h.hexdigest(),seed=42,containerImage='postgres:18.6-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873',boundary='JDBC application commands plus independent HTTP provider',sharedHost=True,warmupExecutions=0)
(p/'target/environment.json').write_text(json.dumps(data,indent=2)+'\n')
print(data['sourceFingerprint'])
PY
