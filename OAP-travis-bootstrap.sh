#!/bin/bash
set -ex

sudo dpkg-reconfigure dash

modules=(
	oap-cache/oap/
	oap-cache/pegasus/
	oap-data-source/arrow/
	oap-native-sql/
	oap-shuffle/PMoF-shuffle/
	oap-shuffle/RPMEM-pool/
	oap-shuffle/memory-shuffle/
	oap-shuffle/remote-shuffle/
)




#if [ "$1" != "before_install" ] && [ "$1" != "install" ] && [ "$1" != "before_script" ] && [ "$1" != "script" ]
if [ "$1" != "script" ]
then
	echo "Don't support this arg: $1"
	exit 1
fi

for module in ${modules[@]}
do
	echo $module/travis-verify.sh
	if [ -f $module/travis-verify.sh ]
	then
		echo "exist!"
		bash $module/travis-verify.sh $1
	fi
done

