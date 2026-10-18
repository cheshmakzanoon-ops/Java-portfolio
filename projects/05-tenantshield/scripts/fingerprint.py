"""Hash owned executable inputs plus shared build contract; generated outputs/docs are excluded."""
import hashlib
from pathlib import Path

project = Path(__file__).resolve().parents[1]
root = project.parents[1]
h = hashlib.sha256()
files = [p for p in project.rglob('*') if p.is_file() and not any(part in ('target','evidence','docs','__pycache__') for part in p.relative_to(project).parts) and p.name != 'README.md']
files += [p for p in (root/'pom.xml',root/'scripts/cloud-env.sh',root/'.mvn/maven.config',root/'.mvn/wrapper/maven-wrapper.properties') if p.exists()]
for path in sorted(files):
    name = str(path.relative_to(root)).encode()
    contents = path.read_bytes()
    h.update(name + b'\0' + len(contents).to_bytes(8,'big') + contents)
print('working-tree-sha256:' + h.hexdigest())
