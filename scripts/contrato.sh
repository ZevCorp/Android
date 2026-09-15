#!/usr/bin/env bash
# EL CONTRATO DEL NÚCLEO ANDROID — corre las promesas de core/src/commonTest/…/contrato y emite
# un solo veredicto. Es el juez de docs/specs/*: cada `promesaNN` de un contrato es una fila de la
# tabla de su spec, y aquí se imprime con su enunciado.
#
#   ./scripts/contrato.sh
#
# Veredicto (última línea, siempre una de las dos):
#   CONTRATO INTACTO: N promesas.
#   CONTRATO ROTO: M promesa(s) incumplida(s). El cambio no puede entrar así.
# Una promesa que falla por NotImplementedError se imprime como «⧗ PENDIENTE» (todavía no tiene
# código detrás) pero CUENTA como incumplida: un contrato con pendientes no está intacto.
#
# Código de salida: 99 si no llegó a juzgar (no compila, gradle no arrancó, no hay XML): un «no sé»
# nunca se disfraza de recuento. Si no, el número de promesas incumplidas (0 = intacto).

set -u
export LC_ALL=C.UTF-8
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

rojo()  { printf '\033[31m%s\033[0m\n' "$*"; }
verde() { printf '\033[32m%s\033[0m\n' "$*"; }
gris()  { printf '\033[90m%s\033[0m\n' "$*"; }

repo="$(cd "$(dirname "$0")/.." && pwd)"
resultados="$repo/core/build/test-results/jvmTest"
log="$repo/core/build/contrato.log"

# Sin XML viejo: un veredicto de la corrida anterior no es un veredicto.
rm -rf "$resultados"
mkdir -p "$repo/core/build"

gris "juzgando core (:core:jvmTest)…"
(cd "$repo" && ./gradlew :core:jvmTest -q --rerun > "$log" 2>&1)
codigo_gradle=$?

xmls=("$resultados"/TEST-*.xml)
if [ ! -e "${xmls[0]}" ]; then
  rojo "NO SE PUDO JUZGAR: gradle salió con $codigo_gradle y no dejó resultados en $resultados"
  grep -E "^e: |error:|FAILURE|What went wrong" -A 3 "$log" | head -20 | sed 's/^/     /'
  exit 99
fi

# Enunciado de la promesa N según la tabla de la spec: «| N | texto | fase |».
enunciado() {
  local contrato="$1" n="$2" spec
  spec="$(ls "$repo"/docs/specs/"$contrato"-*.md 2>/dev/null | head -1)"
  [ -n "$spec" ] || return 0
  grep -E "^\| *$n *\|" "$spec" | head -1 | awk -F'|' '{ gsub(/^ +| +$/, "", $3); print $3 }'
}

# Un registro por testcase: nombre<TAB>estado<TAB>mensaje. Estado: ok | rota | pendiente.
registros="$(for f in "${xmls[@]}"; do
  tr '\n' ' ' < "$f" | awk -v RS='<testcase ' 'NR > 1 {
    name = ""; if (match($0, /name="[^"]*"/)) name = substr($0, RSTART + 6, RLENGTH - 7)
    cls = "";  if (match($0, /classname="[^"]*"/)) cls = substr($0, RSTART + 11, RLENGTH - 12)
    estado = "ok"; msg = ""
    if ($0 ~ /<(failure|error)/) {
      estado = ($0 ~ /type="kotlin\.NotImplementedError"/) ? "pendiente" : "rota"
      if (match($0, /message="[^"]*"/)) msg = substr($0, RSTART + 9, RLENGTH - 10)
    }
    printf "%s\t%s\t%s\t%s\n", cls, name, estado, msg
  }'
done | sort -t$'\t' -k1,1 -k2,2)"

total=0; rotas=0
while IFS=$'\t' read -r cls name estado msg; do
  [ -n "$name" ] || continue
  total=$((total + 1))
  # Contrato001Foo + promesa07 → «001» y «7», para buscar la fila en docs/specs/001-*.md.
  # KMP le pega el target al nombre («promesa07[jvm]»): se quita antes de buscar.
  name="$(printf '%s' "$name" | sed -E 's/\[[a-zA-Z0-9]+\]$//')"
  contrato="$(printf '%s' "$cls" | sed -E 's/.*Contrato([0-9]+).*/\1/')"
  n="$(printf '%s' "$name" | sed -E 's/^promesa0*//')"
  texto="$(enunciado "$contrato" "$n")"
  [ -n "$texto" ] || texto="$name"
  # Los mensajes vienen escapados en XML; se muestran cortos y legibles.
  msg="$(printf '%s' "$msg" | sed -e 's/&quot;/"/g' -e 's/&apos;/'"'"'/g' -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g' -e 's/&#10;/ /g' | cut -c1-160)"
  case "$estado" in
    ok)        verde "  ✔ $n · $texto" ;;
    pendiente) rotas=$((rotas + 1)); printf '\033[33m%s\033[0m\n' "  ⧗ PENDIENTE $n · $texto" ;;
    rota)      rotas=$((rotas + 1)); rojo "  ✘ $n · $texto"; [ -n "$msg" ] && gris "      $msg" ;;
  esac
done <<< "$registros"

if [ "$total" -eq 0 ]; then
  rojo "NO SE PUDO JUZGAR: los XML no traen ningún testcase"
  exit 99
fi

echo
if [ "$rotas" -eq 0 ]; then
  verde "CONTRATO INTACTO: $total promesas."
  exit 0
else
  rojo "CONTRATO ROTO: $rotas promesa(s) incumplida(s). El cambio no puede entrar así."
  exit "$rotas"
fi
