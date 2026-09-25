# Emulador de Firebase Auth para desarrollo local.
#
# Node más firebase-tools: el emulador de Auth viene dentro de firebase-tools y no necesita
# Java ni descargar nada más al arrancar. La versión va fija para que todo el equipo use el
# mismo comportamiento; para actualizarla se cambia el valor de FIREBASE_TOOLS_VERSION y se
# reconstruye la imagen (docker compose build firebase-emulator).
FROM node:22-slim

ARG FIREBASE_TOOLS_VERSION=15.30.2

RUN npm install -g firebase-tools@${FIREBASE_TOOLS_VERSION} \
    && npm cache clean --force

WORKDIR /app
COPY --chmod=755 entrypoint.sh /app/entrypoint.sh
COPY firebase.json /app/firebase.json

# /data guarda el estado exportado (usuarios) y /app recibe el log de depuración de
# firebase-tools. El proceso corre como el usuario "node", sin privilegios de root.
RUN mkdir /data && chown -R node:node /data /app
USER node

# La Emulator UI no viene en el paquete npm: se descarga aquí, en la construcción, y no en cada
# arranque. Va después de USER node porque se guarda en la caché del usuario que la descarga
# (~/.cache/firebase); descargada como root, el emulador (que corre como node) no la encuentra.
RUN firebase setup:emulators:ui

EXPOSE 9099
ENTRYPOINT ["/app/entrypoint.sh"]
