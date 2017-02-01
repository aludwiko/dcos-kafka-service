#!/bin/bash

set +e 

#rm -rf tools/ && curl https://infinity-artifacts.s3.amazonaws.com/dcos-commons-tools.tgz | tar xz && mv dcos-commons-tools tools

FRAMEWORK_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD_DIR=$FRAMEWORK_DIR/build/distributions
PUBLISH_STEP=${1-none}
${FRAMEWORK_DIR}/tools/build_framework.sh $PUBLISH_STEP kafka $FRAMEWORK_DIR $BUILD_DIR/*.zip


tools/publish_aws.py \
  kafka \
  universe/ \
  build/distributions/*.zip \
  cli/dcos-kafka/dcos-kafka-darwin \
  cli/dcos-kafka/dcos-kafka-linux \
  cli/dcos-kafka/dcos-kafka.exe \
  cli/python/dist/*.whl
