# SPDX-FileCopyrightText: 2018-2021 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
# SPDX-License-Identifier: Apache-2.0
#!/usr/bin/env bash
# Prerequisites:
#   * have logged in to docker
#   * valid kubeconfig.yml stored in samples root folder
#   * an empty namespace for deployments
# #1 argument can be used to set sample version #2 - namespace.
#   - In case they are missing you will prompted to enter these values at the runtime.
# The user and password to execute the test with can be provided as environment variables 'USER' and 'PASSWORD' - export USER=myUser export PASSWORD=myPassword.
#   - In case environment variables are not provided you will be prompted to enter username and password at the runtime.
# API access credentials need to be provided as the following environment variables: 'API_CLIENTID', 'API_CLIENTSECRET' and 'USER_ID' is the xsuaa ID of the test user
#-------------------------------------------------
# Required libs jq -> if missing brew install jq

# Colors
RED='\033[0;31m'
RS='\033[0m' # Reset style
GREEN='\033[0;32m'
CYAN='\033[96m'

# Test constants
REPOSITORY="cloud-security-integration.common.repositories.cloud.sap"
WORKING_DIR=$(pwd | sed 's/samples.*/samples/')
SAMPLES=("spring-security-hybrid-usage" "java-security-usage" "spring-security-basic-auth")
#SAMPLES=("spring-security-basic-auth")

source "$WORKING_DIR"/vars.sh

#Test environment setup
if [[ -z $USER || -z $PASSWORD ]]; then
  echo -n -e "Enter$CYAN username$RS you want to execute the test with: "
  read -r USER
  echo -n -e "Enter$CYAN password$RS for '$USER': "
  read -r PASSWORD
fi
if [[ $# -lt 2 ]]; then
  echo -n -e "Enter$CYAN namespace$RS where you want to deploy the samples: "
  read -r NAMESPACE
  echo -n -e "Enter$CYAN version$RS for samples that will be used to build the images and push to repository: "
  read -r VERSION
else
  VERSION=$1
  NAMESPACE=$2
fi

#Important to have the kubeconfig file in root folder of samples
export KUBECONFIG=${WORKING_DIR}/kubeconfig.yml

#compile and push the image to the repository #1 argument is sample name
prepare_image() {
  cd "${WORKING_DIR}/$1"
  if [[ "$1" == "java-security-usage" ]]; then
    docker build -t ${REPOSITORY}/"$1":"$VERSION" -f ./Dockerfile .
  else
    mvn spring-boot:build-image -Dspring-boot.build-image.imageName=${REPOSITORY}/"$1":"$VERSION"
  fi
  docker push ${REPOSITORY}/"$1":"$VERSION"
}

#prepare deployment file and deploy the app, first argument is sample name
deploy_app() {
  sed "s/.*containers.*/      imagePullSecrets:\n        - name: sap-repo-registry\n&/; s/<YOUR IMAGE REPOSITORY>/${REPOSITORY}\/$1:$VERSION/" ./k8s/deployment.yml | kubectl apply -f - -n "$NAMESPACE"
  sleep 20
}

#delete the deployed app, first argument is sample name
delete_deployment() {
  sed "s/.*containers.*/      imagePullSecrets:\n        - name: sap-repo-registry\n&/; s/<YOUR IMAGE REPOSITORY>/${REPOSITORY}\/$1:$VERSION/" ./k8s/deployment.yml | kubectl delete -f - -n "$NAMESPACE"
  sleep 7
}

get_request() {
  #Returns the status code of the request
  host=$1
  path=$2
  token=$3

  if [[ -z "$token" || "$token" == *"null"* ]]; then
    curl -I -s -X GET https://"$host"/"$path" | awk '/HTTP\/2/ {print $2}'
  else
    curl -I -s -X GET https://"$host"/"$path" -H "Authorization: ${token}" | awk '/HTTP\/2/ {print $2}'
  fi
}

get_token() {
  eval "declare -A serviceConfig="${1#*=}

  curl -s -X POST \
    "${serviceConfig[url]}"/oauth/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=password&client_id=${serviceConfig["clientid"]}&client_secret=${serviceConfig["clientsecret"]}&username=$USER&password=$PASSWORD" | jq -r '.access_token'
}

add_user_to_role() {
  eval "declare -A serviceConfig="${1#*=}
  role_collection=$2

  api_token=$(curl -s -X POST \
    "${serviceConfig[url]}"/oauth/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=client_credentials&client_id=$API_CLIENTID&client_secret=$API_CLIENTSECRET" | jq -r '.access_token')

  status=$(curl -w "%{http_code}\\n" -s -o /dev/null -X POST \
    "${serviceConfig[apiurl]}"/Groups/"$role_collection"/members \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $api_token" \
    --data-raw "{
          \"value\": \"$USER_ID\",
          \"origin\": \"sap.default\",
          \"type\": \"USER\"}")
  if [[ $status -ne 201 ]]; then
    echo "Couldn't assign user $USER to role role collection $role_collection, status=$status"
  fi
}

# Prepares the test result output
prepare_test_result() {
  status=$1
  expected=$2
  result=$(if test "${status}" = "$expected"; then echo -e "$GREEN""PASSED""$RS"; else echo -e "$RED"'FAILED'"$RS"; fi)
  echo "$expected $status $result"
}

execute_test() {
  host=$1
  path=$2
  expected=$3
  token=$4
  status="$(get_request "$host" "$path" "$token")"
  prepare_test_result "$status" "$expected"
}

#Table formatting
divider================================
divider=$divider$divider
end=-----------------------------------
header="| %-13s| %3s | %2s |\n"
format="| Expected %-3s | %3s | %s |\n"
width=31

print_table() {
  sampleName=$(echo -e "$CYAN""$1""$RS")
  shift
  testCases=("$@")
  printf "%$width.${width}s\n" "$end"
  printf " %32s \n" "$sampleName"
  printf "%$width.${width}s\n" "$divider"
  printf "$header" "Test" "Got" "Status"
  printf "%$width.${width}s\n" "$divider"
  for testCase in "${testCases[@]}"; do
    printf "$format" $testCase
  done
  printf "%$width.${width}s\n" "$end"
}

for sample in "${SAMPLES[@]}"; do
  declare -A serviceConfig
  resultList=()
  cd "${WORKING_DIR}/$sample"
  echo "Deploying $sample version:$VERSION in $NAMESPACE namespace"

  prepare_image "$sample"
  deploy_app "$sample"

  bindings=($(awk '/serviceBindingRef/{getline;print $2}' ./k8s/deployment.yml))

  host=$(kubectl get virtualservices -n "$NAMESPACE" -o jsonpath='{.items[*].spec.hosts[0]}')
  serviceConfig[clientid]=$(kubectl get secret "${bindings[0]}" -o jsonpath='{.data.clientid}' -n "$NAMESPACE" | base64 --decode)
  serviceConfig[clientsecret]=$(kubectl get secret "${bindings[0]}" -o jsonpath='{.data.clientsecret}' -n "$NAMESPACE" | base64 --decode)
  serviceConfig[url]=$(kubectl get secret "${bindings[0]}" -o jsonpath='{.data.url}' -n "$NAMESPACE" | base64 --decode)
  serviceConfig[apiurl]=$(kubectl get secret "${bindings[0]}" -o jsonpath='{.data.apiurl}' -n "$NAMESPACE" | base64 --decode)
  serviceConfig[certurl]=$(kubectl get secret "${bindings[0]}" -o jsonpath='{.data.certurl}' -n "$NAMESPACE" | base64 --decode)
  serviceConfig[cert]=$(kubectl get secret "${bindings[0]}" -o jsonpath='{.data.certificate}' -n "$NAMESPACE" | base64 --decode)
  serviceConfig[key]=$(kubectl get secret "${bindings[0]}" -o jsonpath='{.data.key}' -n "$NAMESPACE" | base64 --decode)

  case $sample in
  "spring-security-hybrid-usage")
    resultList[0]=$(execute_test "$host" "sayHello" 401)

    token=$(get_token "$(declare -p serviceConfig)")
    resultList[1]=$(execute_test "$host" "sayHello" 403 "Bearer $token")

    add_user_to_role "$(declare -p serviceConfig)" XSUAA-Viewer
    token=$(get_token "$(declare -p serviceConfig)")
    resultList[2]=$(execute_test "$host" "sayHello" 200 "Bearer $token")
    ;;
  "java-security-usage")
    resultList[0]=$(execute_test "$host" "java-security-usage/hello-java-security" 401)
    resultList[1]=$(execute_test "$host" "java-security-usage/hello-java-security-authz" 401)

    token=$(get_token "$(declare -p serviceConfig)")
    resultList[2]=$(execute_test "$host" "java-security-usage/hello-java-security-authz" 403 "Bearer $token")

    add_user_to_role "$(declare -p serviceConfig)" JAVA_SECURITY_SAMPLE_Viewer
    token=$(get_token "$(declare -p serviceConfig)")
    resultList[3]=$(execute_test "$host" "java-security-usage/hello-java-security-authz" 200 "Bearer $token")
    resultList[4]=$(execute_test "$host" "java-security-usage/hello-java-security" 200 "Bearer $token")
    ;;
  "spring-security-basic-auth")
    resultList[0]=$(execute_test "$host" "hello-token" 401)

    credentials=$(echo  "$USER:$PASSWORD" | base64)
    resultList[1]=$(execute_test "$host" "hello-token" 403 "Basic $credentials")

    add_user_to_role "$(declare -p serviceConfig)" BASIC_AUTH_API_Viewer
    resultList[2]=$(execute_test "$host" "hello-token" 200 "Basic $credentials")
    ;;
  esac

  print_table "$sample" "${resultList[@]}"

  delete_deployment "$sample"
done
exit 0