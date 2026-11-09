[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $FolderPath
)

$ErrorActionPreference = 'Stop'

try {
    if ([string]::IsNullOrWhiteSpace($FolderPath)) {
        $FolderPath = Read-Host 'Folder to upload'
    }
    $FolderPath = $FolderPath.Trim()
    if ($FolderPath.Length -ge 2 -and $FolderPath.StartsWith('"') -and $FolderPath.EndsWith('"')) {
        $FolderPath = $FolderPath.Substring(1, $FolderPath.Length - 2)
    }
    if ([string]::IsNullOrWhiteSpace($FolderPath)) {
        throw 'No folder path was provided.'
    }

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
    & "C:\Program Files\Java\jdk-21.0.12.1\bin\java.exe" -cp $jarPath br.com.aloi.uploader.MainSimple $folder.FullName
    exit $LASTEXITCODE
}
catch {
    [Console]::Error.WriteLine("4shared folder upload failed: {0}", $_.Exception.Message)
    exit 1
}
