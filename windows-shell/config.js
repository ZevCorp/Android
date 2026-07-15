// Configuración del caparazón. Lo único importante: la URL de la webapp desplegada.
//
// La app NO empaqueta el código de la webapp: carga la URL de producción (Vercel). Por eso, cuando se
// actualiza el repo de la webapp y Vercel redespliega, la app instalada muestra los cambios al reabrir
// o refrescar (Ctrl+R) — sin reinstalar nada. Fricción casi nula, y 100% de reutilización del webapp.
//
// Se puede sobreescribir en tiempo de ejecución con la variable de entorno WEBAPP_URL (útil para
// apuntar a un preview/staging sin recompilar).

module.exports = {
  webappUrl: process.env.WEBAPP_URL || 'https://miracle-web-umber.vercel.app',

  // Ruta(s) donde buscar el asistente WPF (U.exe) que dispara la carita flotante. La primera que exista
  // gana. En la app empaquetada vive en resources/assistant/U.exe (ver electron-builder → extraResources).
  assistantExeName: 'U.exe',
};
