#!/usr/bin/env bash
# Compila el cliente y lo empaqueta como U.app (bundle de agente, sin icono en el Dock).
# Uso:  ./make-app.sh   →  genera ./U.app  y lo deja listo para abrir.
set -euo pipefail
cd "$(dirname "$0")"

echo "▸ swift build -c release"
swift build -c release

BIN=".build/release/U"
APP="U.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/U"
cp Resources/Info.plist "$APP/Contents/Info.plist"

# Firma ad-hoc: necesaria para que macOS recuerde los permisos de Accesibilidad/Grabación por identidad.
codesign --force --deep --sign - "$APP" 2>/dev/null || echo "  (codesign ad-hoc omitido)"

echo "✓ Listo: $APP"
echo "  Ábrelo con:  open $APP"
echo "  La 1ª vez concede Accesibilidad y Grabación de pantalla en Ajustes → Privacidad y seguridad."
