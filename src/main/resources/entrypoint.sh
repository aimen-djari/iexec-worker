#!/bin/sh
if [ ! -z $IEXEC_HTTP_PROXY_HOST ] && [ ! -z  $IEXEC_HTTP_PROXY_PORT ]; then
	HTTP_PROXY_OPTIONS="-Dhttp.proxyHost=$IEXEC_HTTP_PROXY_HOST -Dhttp.proxyPort=$IEXEC_HTTP_PROXY_PORT"
	JAVA_OPTIONS=$HTTP_PROXY_OPTIONS
fi

if [ ! -z $IEXEC_HTTPS_PROXY_HOST ] && [ ! -z  $IEXEC_HTTPS_PROXY_PORT ]; then
	HTTPS_PROXY_OPTIONS="-Dhttps.proxyHost=$IEXEC_HTTPS_PROXY_HOST -Dhttps.proxyPort=$IEXEC_HTTPS_PROXY_PORT"
	JAVA_OPTIONS=$HTTPS_PROXY_OPTIONS
fi

if [ ! -z $IEXEC_HTTP_PROXY_HOST ] && [ ! -z  $IEXEC_HTTP_PROXY_PORT ] && [ ! -z $IEXEC_HTTPS_PROXY_HOST ] && [ ! -z  $IEXEC_HTTPS_PROXY_PORT ]; then
    JAVA_OPTIONS="$HTTP_PROXY_OPTIONS $HTTPS_PROXY_OPTIONS"
fi

java $JAVA_OPTIONS -jar /iexec-worker.jar