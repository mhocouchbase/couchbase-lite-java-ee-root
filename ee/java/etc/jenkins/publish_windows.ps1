param (
    [Parameter(Mandatory=$true)]
	[string]$version,

    [Parameter(Mandatory=$true)]
	[string]$buildNumber,

    [Parameter(Mandatory=$true)]
    [string]$artifactsDir
)

Set-PSDebug -Trace 1

#Publish Couchbase Lite Java for Windows, Enterprise Edition
$product="couchbase-lite-java-ee"
$mavenUrl= "http://proget.build.couchbase.com/maven2/cimaven"
$status = 0

Write-Host "======== PUBLISH Couchbase Lite Java for Windows, Enterprise Edition"
$process = Start-Process -FilePath "$PSScriptRoot\..\..\gradlew.bat" -ArgumentList "ciPublish -PbuildNumber=$buildNumber" -PassThru -Wait
if($process.ExitCode -ne 0){
    $status = 5
}

Write-Host "======== Copy artifacts to staging directory"
Copy-Item "lib\build\distributions\$product-$version-$buildNumber.zip $artifactsDir\$product-$version-$buildNumber-windows.zip"

Write-Host "======== PUBLICATION COMPLETE" $status
exit $status