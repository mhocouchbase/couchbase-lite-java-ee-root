

// Used for the android builds.
// Note that both flavors must use the same toolchain
final NDK_VERSION='20.1.5948944'
final CMAKE_VERSION='3.10.2.4988404'
final BUILD_TOOLS_VERSION='29.0.3'

def JOB_NAME = ""
def ANDROID_SDK = ""
def SDK_MGR = ""

pipeline {
    agent { label 'mobile-lite-android-03' }
    options { timestamps() }

    stages {
        stage('Setup') {
            steps {
                sh """ 
                    echo "======== Environment"
                    env
                    javac -version
                """

                script {
                    JOB_NAME = "${env.CHANGE_BRANCH.replace("/","_")}_${env.CHANGE_ID}"
                    currentBuild.displayName = "verify-cbl-java-${JOB_NAME}"
                    currentBuild.description = "${CHANGE_TITLE}"
                    ANDROID_SDK = "${env.ANDROID_HOME}"
                    SDK_MGR="${ANDROID_SDK}/tools/bin/sdkmanager"
                }
            }
        }

        stage('Download source') {
            steps {
                sh """ 
                    echo "======== Checkout Source `pwd`"
                    git checkout -b "${env.CHANGE_BRANCH}"
                    git submodule update --init --recursive
                """
            }
        }

        stage('Setup Toolchain') {
            steps {
                sh """ 
                    echo "======== Install Toolchain"
                    echo "yes" | ${SDK_MGR} --licenses > /dev/null 2>&1
                    ${SDK_MGR} --install "build-tools;${BUILD_TOOLS_VERSION}"
                    ${SDK_MGR} --install "ndk;${NDK_VERSION}"
                    ${SDK_MGR} --install "cmake;${CMAKE_VERSION}"
                """

                // must be done after the source is downloaded
                sh """
                    echo "======== Setup local.properties"
                    touch local.properties
                    cp local.properties ee/java
                    cp local.properties ce/java

                    echo "sdk.dir=${ANDROID_SDK}" >> local.properties
                    echo "ndk.dir=${ANDROID_SDK}/ndk/${NDK_VERSION}" >> local.properties
                    echo "cmake.dir=${ANDROID_SDK}/cmake/${CMAKE_VERSION}" >> local.properties
                    cp local.properties ee/android
                    cp local.properties ce/android
                """
             }
        }

        stage('Verify') {
            steps {
                sh """
                    echo "======== VERIFY Couchbase Lite Java Family, v`cat version.txt` ${JOB_NAME}"
                    ./gradlew ciCheck -PtargetAbis=arm64-v8a || exit 1
                    echo "======== VERIFY COMPLETE"
                """
            }
        }
    }
}
