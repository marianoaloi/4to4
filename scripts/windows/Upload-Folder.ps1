[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $FolderPath
)

$ErrorActionPreference = 'Stop'

try {
    $folder = Get-Item -LiteralPath $FolderPath
    if (-not $folder.PSIsContainer) {
        throw "The selected path is not a folder: $FolderPath"
    }

    $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    $jarPath = Join-Path $repoRoot '4sharedUploader\target\4shared-uploader-1.0.0-jar-with-dependencies.jar'
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "Uploader JAR not found. Build it with: mvn -DskipTests package (from $repoRoot)"
    }

    $env:FOURSHARED_POST_UPLOAD_DIRECTORY = 'C:\tmp\backup'
    & java -jar $jarPath $folder.FullName
    exit $LASTEXITCODE
}
catch {
    [Console]::Error.WriteLine("4shared folder upload failed: {0}", $_.Exception.Message)
    exit 1
}
