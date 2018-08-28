#!/bin/bash
#
# Copyright (c) 2018 Oracle and/or its affiliates. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -eo pipefail

# Path to this script
if [ -h "${0}" ] ; then
  SCRIPT_PATH="$(readlink "$0")"
else
  SCRIPT_PATH="${0}"
fi

# Current directory
MY_DIR=$(cd $(dirname -- "${SCRIPT_PATH}") ; pwd -P)

EXAMPLE_DIR="${MY_DIR}/.."

EXAMPLES=" \
  helidon-quickstart-se \
  helidon-quickstart-mp \
"

# Create archetypes from example projects
bash ${MY_DIR}/create-archetypes.sh 

# Deploy the archetypes
for _ex in ${EXAMPLES}; do

  echo "========== Deploying ${pom_file} =========="
  pom_file="${EXAMPLE_DIR}/${_ex}/target/generated-sources/archetype/pom.xml"
  if [ -f "${pom_file}" ]; then
      mvn -f "${pom_file}" \
        clean deploy -B -DskipTests \
        -DaltDeploymentRepository=gf-internal-releases::default::https://oss.sonatype.org/service/local/staging/deploy/maven2/
  else
    echo "${pom_file} does not exist. Skipping."
  fi

done