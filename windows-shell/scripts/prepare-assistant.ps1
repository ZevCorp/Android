# Compila el asistente WPF (windows-client) y lo copia a resources/assistant/ para que el instalador de
# Electron lo empaquete. Ejecuta esto ANTES de `npm run dist`.
#
#   powershell -ExecutionPolicy Bypass -File scripts\prepare-assistant.ps1
#
# Requiere .NET 8 SDK. Publica framework-dependent (más liviano; el usuario necesita el runtime de .NET
# Desktop). Para un .exe autónomo, cambia --self-contained a true y añade -p:PublishSingleFile=true.

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$client = Join-Path (Split-Path -Parent $root) "windows-client\WindowsClient.csproj"
$out = Join-Path $root "resources\assistant"

Write-Host "▸ Publicando el asistente WPF desde $client"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
dotnet publish $client -c Release -r win-x64 --self-contained false -o $out

if (Test-Path (Join-Path $out "U.exe")) {
  Write-Host "✓ Asistente listo en $out (U.exe)"
} else {
  Write-Warning "No se encontró U.exe tras publicar. Revisa el AssemblyName del csproj (debe ser 'U')."
}
