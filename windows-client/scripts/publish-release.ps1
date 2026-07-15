<#
.SYNOPSIS
  Compila la carita y arma el paquete de actualización. Un comando, todo lo demás es subir 3 archivos.

.DESCRIPTION
  Deja en out\releases lo que hay que subir al bucket `windows` de Supabase para que TODOS los clientes
  instalados se actualicen solos. Ver RELEASING-WINDOWS.md para el flujo completo.

.EXAMPLE
  .\scripts\publish-release.ps1 -Version 1.0.1
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    # false = el usuario necesita el runtime .NET 8 instalado, pero el paquete pesa ~10x menos.
    [bool]$SelfContained = $true
)

$ErrorActionPreference = "Stop"

$clientDir = Split-Path -Parent $PSScriptRoot
$repoDir   = Split-Path -Parent $clientDir
$publishDir = Join-Path $repoDir "out\assistant"
$releaseDir = Join-Path $repoDir "out\releases"

if (-not (Get-Command vpk -ErrorAction SilentlyContinue)) {
    throw "Falta la herramienta vpk. Instálala una vez con: dotnet tool install -g vpk"
}

Write-Host "==> Publicando U.exe (self-contained=$SelfContained)..." -ForegroundColor Cyan
# Sin PublishSingleFile a propósito: ScreenRecorderLib es un ensamblado mixto C++/CLI y no lo soporta
# (ver PRODUCTION.md). Velopack empaqueta la carpeta entera, así que no hace falta.
dotnet publish (Join-Path $clientDir "WindowsClient.csproj") `
    -c Release -r win-x64 --self-contained $SelfContained -o $publishDir
if ($LASTEXITCODE -ne 0) { throw "dotnet publish falló" }

Write-Host "==> Empaquetando version $Version..." -ForegroundColor Cyan
# Sin --channel: el default en Windows es "win", que es el que el cliente pide (releases.win.json).
vpk pack -u U -v $Version -p $publishDir -e U.exe -o $releaseDir
if ($LASTEXITCODE -ne 0) { throw "vpk pack falló" }

Write-Host ""
Write-Host "Listo. Subí estos archivos al bucket 'windows' de Supabase:" -ForegroundColor Green
Get-ChildItem $releaseDir -Include "releases.win.json", "*.nupkg" -Recurse |
    ForEach-Object { "  - $($_.Name)  ($([math]::Round($_.Length / 1MB, 1)) MB)" }
Write-Host ""
Write-Host "Para un cliente NUEVO (primera instalación), mandale:" -ForegroundColor Green
Write-Host "  $releaseDir\U-Setup.exe"
