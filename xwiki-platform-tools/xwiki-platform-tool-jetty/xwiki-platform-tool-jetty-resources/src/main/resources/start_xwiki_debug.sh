#!/bin/bash
# ---------------------------------------------------------------------------
# See the NOTICE file distributed with this work for additional
# information regarding copyright ownership.
#
# This is free software; you can redistribute it and/or modify it
# under the terms of the GNU Lesser General Public License as
# published by the Free Software Foundation; either version 2.1 of
# the License, or (at your option) any later version.
#
# This software is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with this software; if not, write to the Free
# Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
# 02110-1301 USA, or see the FSF site: http://www.fsf.org.
# ---------------------------------------------------------------------------

# ----------------------------------------------------------------------------------------------------------------
# Optional ENV vars
# -----------------
#   XWIKI_OPTS - parameters passed to the Java VM when running XWiki e.g. to increase the memory allocated to the
#       JVM to 1GB, use set XWIKI_OPTS=-Xmx1024m
#   JETTY_PORT - the port on which to start Jetty.
#   JETTY_STOP_PORT - the port on which Jetty listens for a Stop command.
#
# Optional Parameters
# -------------------
#   -p, --port: The Jetty HTTP port to use. Overrides any value from JETTY_PORT. Defaults to 8080.
#   -sp, --stopport: The Jetty stop port to use. Overrides any value from JETTY_STOP_PORT. Defaults to 8079.
#   -ld, --lockdir: The directory where the executing process id is stored to verify that that only one instance is
#       started. Defaults to /var/tmp.
#   -k, --kill: If set then kills any already executing XWiki instance before starting a new one.
#   -yp, --yourkitpath: The path where Yourkit can find the agent. If not passed then YourKit won't be enabled.
#       For example: "/Applications/YourKit Java Profiler 7.0.11.app/bin/mac"
#       or "/home/User/yjp-11.0.8/bin/linux-x86-64/"
#
# Example
# -------
#   start_xwiki_debug.sh -yp "/Applications/YourKit Java Profiler 7.0.11.app/bin/mac"
# ----------------------------------------------------------------------------------------------------------------

# Ensure that the commands below are always started in the directory where this script is located.
# To do this we compute the location of the current script.
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done
PRGDIR=`dirname "$PRG"`
cd "$PRGDIR"

JETTY_HOME=jetty

# If no XWIKI_OPTS env variable has been defined use default values.
if [ -z "$XWIKI_OPTS" ] ; then
  XWIKI_OPTS="-Xmx512m -XX:MaxPermSize=196m"
fi
XWIKI_OPTS="$XWIKI_OPTS -Xdebug -Xnoagent -Djava.compiler=NONE"
XWIKI_OPTS="$XWIKI_OPTS -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"

# The port on which to start Jetty can be defined in an environment variable called JETTY_PORT
if [ -z "$JETTY_PORT" ]; then
  JETTY_PORT=8080
fi

# The port on which Jetty listens for a Stop command can be defined in an environment variable called JETTY_STOP_PORT
if [ -z "$JETTY_STOP_PORT" ]; then
  JETTY_STOP_PORT=8079
fi

# The location where to store the process id
XWIKI_LOCK_DIR="/var/tmp"

# Parse script parameters
while [[ $# > 0 ]]; do
  key="$1"
  shift
  case $key in
    -p|--port)
      JETTY_PORT="$1"
      shift
      ;;
    -sp|--stopport)
      JETTY_STOP_PORT="$1"
      shift
      ;;
    -ld|--lockdir)
      XWIKI_LOCK_DIR="$1"
      shift
      ;;
    -k|--kill)
      XWIKI_KILL="true"
      ;;
    -yp|--yourkitpath)
      YOURKIT_PATH="$1"
      shift
      ;;
    *)
      # unknown option
      ;;
  esac
done

# Check if a lock file already exists for the specified port  which means an XWiki instance is already running
XWIKI_LOCK_FILE="${XWIKI_LOCK_DIR}/xwiki-${JETTY_PORT}.lck"

if [ -e $XWIKI_LOCK_FILE ]; then
  if ps -p `cat $XWIKI_LOCK_FILE` > /dev/null; then
    # An XWiki instance is still running
    if [ "$XWIKI_KILL" == "true" ]; then
      echo An XWiki instance is already running on port ${JETTY_PORT}, stopping it!
      /bin/bash stop_xwiki.sh $JETTY_STOP_PORT
    else
      echo An XWiki instance is already running on port ${JETTY_PORT}. Aborting...
      echo Consider calling stop_xwiki.sh to stop it.
      exit 1
    fi
  else
    echo An XWiki lock file exists at ${XWIKI_LOCK_FILE} but no XWiki is executing. Removing lock file...
    rm -f $XWIKI_LOCK_FILE
  fi
fi

# Enabling YourKit Profiling.
if [ -n "$YOURKIT_PATH" ]; then
  XWIKI_OPTS="$XWIKI_OPTS -agentlib:yjpagent"
  # Linux
  export LD_LIBRARY_PATH="$LD_LIBRARY_PATH:${YOURKIT_PATH}"
  # Mac
  export DYLD_LIBRARY_PATH="$DYLD_LIBRARY_PATH:${YOURKIT_PATH}"
fi

echo Starting Jetty on port ${JETTY_PORT}, please wait...

# Location where XWiki stores generated data and where database files are.
XWIKI_DATA_DIR=${xwikiDataDir}
XWIKI_OPTS="$XWIKI_OPTS -Dxwiki.data.dir=$XWIKI_DATA_DIR"

# Ensure the data directory exists so that XWiki can use it for storing permanent data.
mkdir -p $XWIKI_DATA_DIR 2>/dev/null

# Ensure the logs directory exists as otherwise Jetty reports an error
mkdir -p $XWIKI_DATA_DIR/logs 2>/dev/null

# Specify port on which HTTP requests will be handled
XWIKI_OPTS="$XWIKI_OPTS -Djetty.port=$JETTY_PORT"

# Specify Jetty's home directory
XWIKI_OPTS="$XWIKI_OPTS -Djetty.home=$JETTY_HOME"

# Specify port and key to stop a running Jetty instance
XWIKI_OPTS="$XWIKI_OPTS -DSTOP.KEY=xwiki -DSTOP.PORT=$JETTY_STOP_PORT"

# Specify the encoding to use
XWIKI_OPTS="$XWIKI_OPTS -Dfile.encoding=UTF8"

# In order to avoid getting a "java.lang.IllegalStateException: Form too large" error when editing large page in
# XWiki we need to tell Jetty to allow for large content since by default it only allows for 20K. We do this by
# passing the org.eclipse.jetty.server.Request.maxFormContentSize property.
# Note that setting this value too high can leave your server vulnerable to denial of service attacks.
XWIKI_OPTS="$XWIKI_OPTS -Dorg.eclipse.jetty.server.Request.maxFormContentSize=1000000"

# In order to get the PID, start java in the background , get the PID and wait for it to be finished.
# However doing this prevents sending any signal to the JVM (since it's running in the background).
# Thus we trap the INT signal (which is sent when the user presses ctrl-c) to the JVM process with the trap command.
java $XWIKI_OPTS -jar $JETTY_HOME/start.jar ${JETTY_HOME}/etc/jetty.xml ${JETTY_HOME}/etc/jetty-*.xml &
XWIKI_PID=$!
echo $XWIKI_PID > $XWIKI_LOCK_FILE
trap "kill ${XWIKI_PID}; exit 1" INT
wait $XWIKI_PID 

# Remove XWiki lock file
rm -f $XWIKI_LOCK_FILE