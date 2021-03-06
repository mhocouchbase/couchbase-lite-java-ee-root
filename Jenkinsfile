

// Used for the android builds.
// Note that both flavors must use the same toolchain
final NDK_VERSION='22.0.7026061'
final CMAKE_VERSION='3.18.1'
final BUILD_TOOLS_VERSION='32.0.0'

def JOB_NAME = ""
def ANDROID_SDK = ""
def SDK_MGR = ""

pipeline {
    agent { label 'mobile-lite-android' }
    options { timestamps() }

    stages {
        stage('Setup') {
            steps {
                sh """ 
                    set +x
                    echo "======== Environment"
                    env
                    javac -version
                """

                script {
                    currentBuild.displayName = "cbl-java-verify-${env.JOB_BASE_NAME} #${env.CHANGE_ID}"
                    currentBuild.description = "${CHANGE_TITLE}"
                    ANDROID_SDK = "${env.ANDROID_HOME}"
                    SDK_MGR="${ANDROID_SDK}/cmdline-tools/latest/bin/sdkmanager --channel=1"
                }
            }
        }

        stage('Download source') {
            steps {
                sh """ 
                    set +x
                    echo "======== Checkout Source `pwd`"
                    git checkout -b "${env.CHANGE_BRANCH}"
                    git submodule update --init --recursive
                """
            }
        }

        stage('Setup Toolchain') {
            steps {
                sh """ 
                    set +x
                    echo "======== Install Toolchain"
                    echo "yes" | ${SDK_MGR} --licenses > /dev/null 2>&1
                    ${SDK_MGR} --install "build-tools;${BUILD_TOOLS_VERSION}"
                    ${SDK_MGR} --install "ndk;${NDK_VERSION}"
                    ${SDK_MGR} --install "cmake;${CMAKE_VERSION}"
                """

                // must be done after the source is downloaded
                sh """
                    set +x
                    echo "======== Setup local.properties"
                    touch local.properties
                    cp local.properties ee/java
                    cp local.properties ce/java

                    echo "sdk.dir=${ANDROID_SDK}" >> local.properties
                    cp local.properties ee/android
                    cp local.properties ce/android
                """
             }
        }

        stage('Download Core') {
            steps {
                sh """
                    echo "======== Download Core"
                    common/tools/fetch_android_litecore.sh -e EE -n "http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore"
                """
            }
        }

//      For now, "verification" means verify the Android build.  Sigh.
        stage('Verify') {
            steps {
                sh """
                    cd ee/android
                    set +x
                    echo "======== VERIFY Couchbase Lite Java Family v`cat version.txt` (${env.CHANGE_BRANCH}) ${env.CHANGE_URL}"
                    ./gradlew ciCheck || exit 1
                    echo "======== VERIFY COMPLETE"
                """
            }
        }
    }
}
