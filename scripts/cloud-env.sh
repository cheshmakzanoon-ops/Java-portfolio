# Source this file in the managed cloud environment; portable local builds need only JDK 25.
export JAVA_HOME=/workspace/.tools/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_USER_HOME=/workspace/Java-portfolio/.work/wrapper
export MAVEN_OPTS='-Xmx512m -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
# Generated from the platform's proxy host/port, without copying credentials.
export MAVEN_ARGS='-s /workspace/Java-portfolio/.work/settings.xml'
