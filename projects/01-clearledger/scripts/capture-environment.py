#!/usr/bin/env python3
"""Capture reproducible, nonsensitive machine/build metadata, never environment values."""
import datetime,hashlib,json,os,pathlib,platform,subprocess,sys,xml.etree.ElementTree as E
root=pathlib.Path(__file__).resolve().parents[3] if pathlib.Path(__file__).parent.name=='scripts' else pathlib.Path('/workspace/Java-portfolio')
project=root/'projects/01-clearledger'
def run(args):
 r=subprocess.run(args,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True);return r.stdout.strip()
def read(path):
 p=pathlib.Path(path);return p.read_text().strip() if p.exists() else 'unavailable'
ns={'m':'http://maven.apache.org/POM/4.0.0'};pom=E.parse(root/'pom.xml').getroot()
versions={d.findtext('m:groupId',namespaces=ns)+':'+d.findtext('m:artifactId',namespaces=ns):d.findtext('m:version',namespaces=ns) for d in pom.findall('m:dependencyManagement/m:dependencies/m:dependency',ns)}
sourceHash=hashlib.sha256();manifest={}
for relative in sorted(set(run(['git','ls-files','--cached','--others','--exclude-standard','projects/01-clearledger']).splitlines())):
 f=root/relative
 if not f.is_file() or '/evidence/' in relative or '/docs/' in relative:continue
 content=f.read_bytes();sourceHash.update(relative.encode()+b'\0'+content+b'\0');manifest[relative]=hashlib.sha256(content).hexdigest()
data={'sourceRevision':'working-tree:'+sourceHash.hexdigest(),'sourceManifest':manifest,'schemaVersion':1,'capturedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceCommit':run(['git','rev-parse','HEAD']),'rootPomSha256':hashlib.sha256((root/'pom.xml').read_bytes()).hexdigest(),'platform':platform.platform(),'cpuQuota':read('/sys/fs/cgroup/cpu.max'),'memoryLimit':read('/sys/fs/cgroup/memory.max'),'cpuModel':next((s for s in read('/proc/cpuinfo').splitlines() if s.startswith('model name')),'unavailable'),'logicalCpus':os.cpu_count(),'java':run(['java','-version']),'dependencyPins':versions,'rootProperties':{c.tag.split('}')[-1]:c.text for c in pom.find('m:properties',ns) if c.tag.split('}')[-1] in ['maven.compiler.release','project.build.sourceEncoding','spring-boot.version','junit.version']},'postgresImage':run(['docker','image','inspect','postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873','--format','{{json .RepoDigests}}']),'runningContainers':run(['docker','ps','--format','{{.Names}} {{.Image}} {{.Status}}']),'privacy':'No environment values or credentials recorded.'}
limits=subprocess.run(['docker','inspect','--format','{{.State.Running}} {{.HostConfig.Memory}} {{.HostConfig.NanoCpus}} {{.HostConfig.CpuQuota}} {{.HostConfig.CpuPeriod}}','java-portfolio-clearledger-postgres-1'],text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,check=False)
data['ownedDatabaseContainerLimits']={'available':limits.returncode==0,'fields':'running memoryBytes nanoCpus cpuQuota cpuPeriod','value':limits.stdout.strip() if limits.returncode==0 else None}
data['launcherCommand']=sys.argv[2:]
out=pathlib.Path(sys.argv[1]);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(data,indent=2)+'\n')
