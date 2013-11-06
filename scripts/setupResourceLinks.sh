#!/bin/bash

set -e

while getopts c:d OPTION "$@"; do
    case $OPTION in
	c)
	    COMPONENTDIR=${OPTARG}
	    ;;
	d)
	    set -x
	    ;;
    esac
done

createRelativeSymlinks() {
    local sourceDir=$(readlink -e $1)
    local destDir=$(readlink -e $2)

    cd $destDir
    for file in $(find $sourceDir -type f) ; do
	local relPath=${file#${sourceDir}/}
	[ -h $relPath ] && continue
	local relDir=$(dirname $relPath)
	mkdir -p ${relDir}
	ln -rs $file $relPath
    done
}

if [[ -z "$COMPONENTDIR" ]]; then
    echo "usage: `basename $0` [-d] -c <componentDirectory>"
    exit 1
fi

. "$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )/setup.sh"

IS_MAIN=false
IS_MODULE=false
IS_TRANSPORT=false

COMPONENT=$(basename $COMPONENTDIR)
[[ $COMPONENT == main ]] && IS_MAIN=true
[[ $COMPONENT == module-* ]] && IS_MODULE=true
[[ $COMPONETN == transport-* ]] && IS_TRANSPORT=true

# Phase 1: The global shared resources
createRelativeSymlinks ${BASEDIR}/shared/res-global $COMPONENTDIR/res

# Phase2: The global shared source resources
[[ ! -d $COMPONENTDIR/res-src ]] && mkdir $COMPONENTDIR/res-src
createRelativeSymlinks ${BASEDIR}/shared/res-src-global $COMPONENTDIR/res-src
