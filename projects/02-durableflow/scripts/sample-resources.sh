#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
exec python3 - "$PROJECT" <<'PY'
import pathlib,sys,os,time,json,datetime
p=pathlib.Path(sys.argv[1]);out=p/'target/resource-samples.jsonl'
with out.open('w') as stream:
 while True:
  processes=[];selected={}
  for file in (p/'target/runtime').glob('*.pid'):
   try:selected[int(file.read_text().strip())]=file.stem
   except (OSError,ValueError):pass
  for base in pathlib.Path('/proc').iterdir():
   if not base.name.isdigit():continue
   try:
    command=(base/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
    if str(p)+'/' in command and '-Dloader.main=io.github.cheshmakzanoon.portfolio.durableflow.WorkloadMain' in command:selected[int(base.name)]='driver'
   except (OSError,ValueError):pass
  for pid,role in selected.items():
   try:
    base=pathlib.Path('/proc')/str(pid)
    command=(base/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
    if str(p)+'/' not in command:continue
    stat=(base/'stat').read_text().split();status=(base/'status').read_text().splitlines()
    rss=next(int(l.split()[1])*1024 for l in status if l.startswith('VmRSS:'))
    processes.append(dict(role=role,pid=pid,rssBytes=rss,cpuTicks=int(stat[13])+int(stat[14])))
   except (OSError,ValueError,StopIteration):pass
  stream.write(json.dumps(dict(timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),clockTicksPerSecond=os.sysconf('SC_CLK_TCK'),processes=processes))+'\n');stream.flush();time.sleep(1)
PY
