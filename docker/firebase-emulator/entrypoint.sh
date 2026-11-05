#!/bin/sh
# Arranca el emulador de Firebase Auth.
#
# - El ID de proyecto debe empezar por "demo-". Ese prefijo hace que las herramientas de
#   Firebase traten el proyecto como local y bloqueen cualquier acceso a recursos reales de
#   Google Cloud. Si alguien pone aquí el ID de un proyecto real, se rechaza el arranque.
# - Si ya existe una exportación en /data/export, la importa. Al apagarse de forma ordenada
#   (docker compose stop o down) vuelve a exportar el estado en el mismo lugar. Un apagado
#   brusco (docker kill, cierre forzado de Docker, corte de luz) no exporta nada.
# - Se exporta a un subdirectorio y no a /data porque firebase-tools borra y recrea el
#   destino de la exportación, y no puede hacerlo con el punto de montaje del volumen: falla
#   con "permission denied" y, aun así, el contenedor termina con código 0.
set -eu

PROJECT="${FIREBASE_EMULATOR_PROJECT_ID:-demo-cameia}"
case "$PROJECT" in
  demo-*) ;;
  *)
    echo "ERROR: FIREBASE_EMULATOR_PROJECT_ID debe empezar por 'demo-' (valor recibido: '$PROJECT')." >&2
    exit 1
    ;;
esac

if [ -f /data/export/firebase-export-metadata.json ]; then
  exec firebase emulators:start --only auth --project "$PROJECT" \
    --import /data/export --export-on-exit /data/export
fi

exec firebase emulators:start --only auth --project "$PROJECT" \
  --export-on-exit /data/export
