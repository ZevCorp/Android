#!/usr/bin/env bash
# EL CONTRATO DEL NÚCLEO ANDROID — corre TODAS las promesas de core/src/commonTest/…/contrato y emite
# un solo veredicto. Es el juez de docs/specs/*: cada fila de la tabla de promesas de una spec (la que
# abre con la cabecera «| # | Promesa |») tiene que tener su `promesaN` juzgada, y cada `promesaN` tiene
# que ser una fila de alguna spec. Las promesas se numeran en bloques que no chocan (la 001 usa 1-99; la
# spec NNN, NNN×100+1 en adelante), así que el número basta para encontrar su enunciado en cualquier spec.
#
#   ./scripts/contrato.sh
#
# Veredicto (última línea, siempre una de estas):
#   CONTRATO INTACTO: N promesas.
#   CONTRATO ROTO: M promesa(s) incumplida(s). El cambio no puede entrar así.
#   NO SE PUDO JUZGAR: …
# Cuentan como INCUMPLIDAS, además de la que falla:
#   «⧗ PENDIENTE»                falla por NotImplementedError: todavía no tiene código detrás;
#   «✘ … (silenciada: @Ignore)»  el test existe y no se corrió: una promesa que no se juzga no se cumple;
#   «⧗ SIN JUEZ»                 la fila está en la spec y ningún test la juzga: borrar el test no la retira.
# Una fila retirada se tacha (el enunciado empieza con «~~») y deja de pedir juez.
#
# Código de salida: 99 si no llegó a juzgar (no compila, gradle no arrancó, no hay XML, un test que no es
# fila de ninguna spec, un número en dos specs o en dos tests): un «no sé» nunca se disfraza de recuento.
# Si no, el número de promesas incumplidas (0 = intacto).

set -u
export LC_ALL=C.UTF-8
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

rojo()  { printf '\033[31m%s\033[0m\n' "$*"; }
verde() { printf '\033[32m%s\033[0m\n' "$*"; }
ambar() { printf '\033[33m%s\033[0m\n' "$*"; }
gris()  { printf '\033[90m%s\033[0m\n' "$*"; }

repo="$(cd "$(dirname "$0")/.." && pwd)"
resultados="$repo/core/build/test-results/jvmTest"
log="$repo/core/build/contrato.log"
filas="$repo/core/build/contrato.filas.tsv"
juzgadas="$repo/core/build/contrato.juzgadas.tsv"

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

# Las filas de la tabla de promesas de cada spec, en su orden: spec<TAB>N<TAB>enunciado.
# La tabla abre con «| # | Promesa |» y acaba en la primera línea que no es fila.
for spec in "$repo"/docs/specs/*.md; do
  [ -e "$spec" ] || continue
  awk -v spec="$(basename "$spec")" '
    /^\| *# *\| *Promesa *\|/ { dentro = 1; next }
    dentro && /^\|[-:| ]+$/   { next }
    dentro && !/^\|/          { dentro = 0; next }
    dentro {
      split($0, c, "|"); n = c[2]; texto = c[3]
      gsub(/^ +| +$/, "", n); gsub(/^ +| +$/, "", texto)
      if (texto == "") texto = "-"
      if (n ~ /^[0-9]+$/) printf "%s\t%d\t%s\n", spec, n, texto
    }' "$spec"
done > "$filas"

# Un registro por testcase: clase<TAB>nombre<TAB>estado<TAB>mensaje. Estado: ok | rota | pendiente | silenciada.
for f in "${xmls[@]}"; do
  tr '\n' ' ' < "$f" | awk -v RS='<testcase ' 'NR > 1 {
    name = ""; if (match($0, /name="[^"]*"/)) name = substr($0, RSTART + 6, RLENGTH - 7)
    cls = "";  if (match($0, /classname="[^"]*"/)) cls = substr($0, RSTART + 11, RLENGTH - 12)
    estado = "ok"; msg = ""
    if ($0 ~ /<(failure|error)/) {
      estado = ($0 ~ /type="kotlin\.NotImplementedError"/) ? "pendiente" : "rota"
      if (match($0, /message="[^"]*"/)) msg = substr($0, RSTART + 9, RLENGTH - 10)
    } else if ($0 ~ /<skipped/) {
      estado = "silenciada"
    }
    printf "%s\t%s\t%s\t%s\n", cls, name, estado, msg
  }'
done > "$juzgadas"

if [ ! -s "$juzgadas" ]; then
  rojo "NO SE PUDO JUZGAR: los XML no traen ningún testcase"
  exit 99
fi

# El cruce spec ↔ contrato. Una línea por veredicto, sin campos vacíos en medio (read los fundiría):
#   ok|rota|pendiente|silenciada|sinjuez <TAB> N <TAB> enunciado <TAB> mensaje
#   huerfana|doble|repetida <TAB> N o «-» <TAB> detalle
veredictos="$(awk -F'\t' '
  FILENAME == ARGV[1] {
    if (NF < 3) next
    if ($2 in spec) repetida[$2] = spec[$2] " y " $1
    else orden[++filas] = $2
    spec[$2] = $1; texto[$2] = $3
    next
  }
  NF >= 3 {
    clase = $1; sub(/.*\./, "", clase)
    nombre = $2; sub(/\[[A-Za-z0-9]+\]$/, "", nombre)   # KMP le pega el target: «promesa07[jvm]»
    if (nombre !~ /^promesa[0-9]+$/) { printf "huerfana\t-\t%s.%s\n", clase, nombre; next }
    n = sprintf("%d", substr(nombre, 8))
    if (!(n in spec)) { printf "huerfana\t%s\t%s.%s\n", n, clase, nombre; next }
    if (n in estado)  { printf "doble\t%s\t%s y %s\n", n, juez[n], clase; next }
    estado[n] = $3; mensaje[n] = $4; juez[n] = clase
  }
  END {
    for (n in repetida) printf "repetida\t%s\t%s\n", n, repetida[n]
    for (i = 1; i <= filas; i++) {
      n = orden[i]
      if (n in estado)           printf "%s\t%s\t%s\t%s\n", estado[n], n, texto[n], mensaje[n]
      else if (texto[n] ~ /^~~/) continue
      else                       printf "sinjuez\t%s\t%s\t\n", n, texto[n]
    }
  }' "$filas" "$juzgadas")"

total=0; rotas=0; errores=()
while IFS=$'\t' read -r estado n texto msg; do
  [ -n "$estado" ] || continue
  case "$estado" in
    huerfana) errores+=("✘ $texto no es fila de ninguna tabla de promesas en docs/specs/*.md"); continue ;;
    doble)    errores+=("✘ la promesa $n la juzgan dos tests: $texto"); continue ;;
    repetida) errores+=("✘ la promesa $n está en dos specs: $texto"); continue ;;
  esac
  total=$((total + 1))
  # Los mensajes vienen escapados en XML; se muestran cortos y legibles.
  msg="$(printf '%s' "$msg" | sed -e 's/&quot;/"/g' -e 's/&apos;/'"'"'/g' -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g' -e 's/&#10;/ /g' | cut -c1-160)"
  case "$estado" in
    ok)         verde "  ✔ $n · $texto" ;;
    pendiente)  rotas=$((rotas + 1)); ambar "  ⧗ PENDIENTE $n · $texto" ;;
    sinjuez)    rotas=$((rotas + 1)); ambar "  ⧗ SIN JUEZ $n · $texto" ;;
    silenciada) rotas=$((rotas + 1)); rojo "  ✘ $n · $texto (silenciada: @Ignore)" ;;
    *)          rotas=$((rotas + 1)); rojo "  ✘ $n · $texto"; [ -n "$msg" ] && gris "      $msg" ;;
  esac
done <<< "$veredictos"

if [ "${#errores[@]}" -gt 0 ]; then
  echo
  for e in "${errores[@]}"; do rojo "  $e"; done
  echo
  rojo "NO SE PUDO JUZGAR: ${#errores[@]} test(s) o fila(s) sin pareja entre el contrato y docs/specs/*.md"
  exit 99
fi

if [ "$total" -eq 0 ]; then
  rojo "NO SE PUDO JUZGAR: ninguna spec tiene tabla de promesas «| # | Promesa |»"
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
