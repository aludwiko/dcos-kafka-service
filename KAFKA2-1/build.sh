#!/bin/bash

set +e 

rm -rf tools/ && curl https://infinity-artifacts.s3.amazonaws.com/dcos-commons-tools.tgz | tar xz && mv dcos-commons-tools tools

FRAMEWORK_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD_DIR=$FRAMEWORK_DIR/build/distributions
PUBLISH_STEP=${1-none}
${FRAMEWORK_DIR}/tools/build_framework.sh $PUBLISH_STEP kafka $FRAMEWORK_DIR $BUILD_DIR/*.zip
