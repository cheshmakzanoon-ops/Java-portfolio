#!/usr/bin/env python3
"""Summarize actual outputs only; absent suites/studies remain NOT_RUN."""
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

project=Path(__file__).resolve().parents[1]
root=project.parents[1]
modules=['probe','analysis','jfr','agent','report','workloads','cli','compatibility']
fingerprint=hashlib.sha256()
paths=[p for p in project.rglob('*') if p.is_file() and 'target' not in p.parts and p.suffix in {'.java','.xml','.json','.yaml','.sh','.py'} and 'evidence' not in p.parts]
paths += [root/'pom.xml',root/'mvnw',*sorted((root/'.mvn').rglob('*'))]
for path in sorted(set(paths)):
 if path.is_file():fingerprint.update(str(path.relative_to(root)).encode());fingerprint.update(path.read_bytes())
suites=[]
for module in modules:
 for path in sorted((project/module/'target/surefire-reports').glob('TEST-*.xml')):
  suite=ET.parse(path).getroot();suites.append({'module':module,'suite':suite.attrib['name'],**{k:int(suite.attrib[k]) for k in ['tests','failures','errors','skipped']}})
coverage={}
path=project/'analysis/target/site/jacoco/jacoco.xml'
if path.exists():
 for counter in ET.parse(path).getroot().findall('counter'):
  kind=counter.attrib['type'];covered=int(counter.attrib['covered']);missed=int(counter.attrib['missed']);coverage[kind]={'covered':covered,'missed':missed,'ratio':covered/(covered+missed) if covered+missed else None}
mutation={'status':'NOT_RUN'}
path=project/'analysis/target/pit-reports/mutations.xml'
if path.exists():
 mutants=ET.parse(path).getroot().findall('mutation');counts=Counter(m.attrib['status'] for m in mutants);detected=sum(m.attrib.get('detected')=='true' for m in mutants);mutation={'status':'OBSERVED','mutations':len(mutants),'detected':detected,'detectedRatio':detected/len(mutants) if mutants else None,'outcomes':dict(sorted(counts.items()))}
status='PASS' if suites and not any(s[k] for s in suites for k in ['failures','errors','skipped']) else 'FAIL' if suites else 'NOT_RUN'
report={'schemaVersion':1,'project':'threadlens','sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'workingTreeFingerprint':fingerprint.hexdigest(),'sourceIdentityNote':'Fingerprint covers project implementation/contracts/scripts and root build inputs; final invoked build must be recorded in STATUS. Git HEAD alone does not identify uncommitted source.','unitStatus':status,'suites':suites,'analysisCoverage':coverage,'targetedMutation':mutation,'otherPlatformStatus':{'Windows25x86-64':'UNTESTED','macOS25arm64':'UNTESTED'}}
output=project/'evidence/verification.json';output.write_text(json.dumps(report,indent=2)+'\n');print(output)
