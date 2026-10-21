# Source this file in the managed cloud environment. Local builds only need JDK 25.
portfolio_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export JAVA_HOME="${PORTFOLIO_TOOLS_DIR:-/workspace/.tools}/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_USER_HOME="$portfolio_root/.work/wrapper"
export MAVEN_OPTS='-Xmx512m -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
if [[ -n ${HTTPS_PROXY:-} ]]; then
  mkdir -p "$portfolio_root/.work"
  PORTFOLIO_PROXY_SETTINGS="$portfolio_root/.work/settings.xml" python3 - <<'PY'
import os
import tempfile
from pathlib import Path
from urllib.parse import urlparse
from xml.sax.saxutils import escape
proxy = urlparse(os.environ['HTTPS_PROXY'])
if not proxy.hostname or not proxy.port:
    raise SystemExit('HTTPS_PROXY must specify a proxy hostname and port')
if proxy.username or proxy.password:
    raise SystemExit('Authenticated proxy requires a secure Maven credential binding; do not copy credentials')
settings = (
    '<settings><mirrors><mirror><id>verified-central</id><mirrorOf>*</mirrorOf>'
    '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors><proxies><proxy><id>cloud-egress</id><active>true</active><protocol>http</protocol>'
    f'<host>{escape(proxy.hostname)}</host><port>{proxy.port}</port>'
    '<nonProxyHosts>localhost|127.0.0.1|[::1]</nonProxyHosts></proxy></proxies></settings>')
target = Path(os.environ['PORTFOLIO_PROXY_SETTINGS'])
with tempfile.NamedTemporaryFile(mode='w', dir=target.parent, prefix='settings-', delete=False) as output:
    temporary = Path(output.name)
    try:
        output.write(settings)
        output.flush()
        os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)
PY
  export MAVEN_ARGS="-s $portfolio_root/.work/settings.xml"
fi
