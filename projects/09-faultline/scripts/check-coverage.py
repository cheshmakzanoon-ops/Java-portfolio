#!/usr/bin/env python3
"""Gate original core coverage aggregated from unit + trusted corpus/boundary tests."""
from pathlib import Path
import json,sys,xml.etree.ElementTree as ET
project=Path(__file__).resolve().parents[1]
report=project/'evaluation/target/site/jacoco-aggregate/jacoco.xml'
root=ET.parse(report).getroot()
groups=[g for g in root.findall('group') if g.attrib.get('name')=='faultline-analysis-core']
if len(groups)!=1:raise SystemExit('Missing nonempty aggregate core coverage group')
counters={c.attrib['type']:(int(c.attrib['covered']),int(c.attrib['missed'])) for c in groups[0].findall('counter')}
ratios={k:v[0]/sum(v) for k,v in counters.items() if sum(v)}
result={'schemaVersion':1,'module':'faultline-analysis-core','counters':counters,'ratios':ratios,'required':{'LINE':0.85,'BRANCH':0.75}}
(project/'evaluation/target/coverage-summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
assert ratios.get('LINE',0)>=0.85,'Core line coverage below 85%'
assert ratios.get('BRANCH',0)>=0.75,'Core branch coverage below 75%'
