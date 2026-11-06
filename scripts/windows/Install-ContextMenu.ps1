[CmdletBinding()]
param(
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'
$menuName = '4sharedUploadFolder'
$menuLabel = 'Upload folder to 4shared (recursive)'
$scriptPath = Join-Path $PSScriptRoot 'Upload-Folder.ps1'
$menuLocations = @(
    "Registry::HKEY_CURRENT_USER\Software\Classes\Directory\shell\$menuName",
    "Registry::HKEY_CURRENT_USER\Software\Classes\Directory\Background\shell\$menuName"
)

if ($Uninstall) {
    foreach ($menuLocation in $menuLocations) {
        Remove-Item -LiteralPath $menuLocation -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host 'Removed the 4shared folder upload context menu for the current Windows user.'
    exit 0
}

if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw "Upload script not found: $scriptPath"
}

$quotedScript = '"' + $scriptPath + '"'
$powershell = Join-Path $PSHOME 'powershell.exe'
foreach ($menuLocation in $menuLocations) {
    New-Item -Path $menuLocation -Force | Out-Null
    Set-Item -LiteralPath $menuLocation -Value $menuLabel
    New-ItemProperty -LiteralPath $menuLocation -Name 'Icon' -Value 'shell32.dll,113' -PropertyType String -Force | Out-Null

    $commandLocation = Join-Path $menuLocation 'command'
    New-Item -Path $commandLocation -Force | Out-Null
    $folderArgument = if ($menuLocation -like '*\Background\*') { '%V' } else { '%1' }
    $command = '"{0}" -NoLogo -NoProfile -ExecutionPolicy Bypass -File {1} "{2}"' -f $powershell, $quotedScript, $folderArgument
    Set-Item -LiteralPath $commandLocation -Value $command
}

Write-Host 'Installed the 4shared upload context menu for the current Windows user.'
Write-Host 'It appears on folder icons and in a folder background right-click menu.'
Write-Host 'To remove it, run: powershell -NoProfile -ExecutionPolicy Bypass -File "Install-ContextMenu.ps1" -Uninstall'
