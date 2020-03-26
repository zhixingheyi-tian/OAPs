#!/bin/bash
set -ex



function script {
	cd ${TRAVIS_BUILD_DIR}/oap-shuffle/remote-shuffle/
	mvn -q test
}

if [ "$1" = "script" ]; then
	script
else
	echo "Don't support this arg: $1"
fi

