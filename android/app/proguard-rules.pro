# Reglas R8/ProGuard — Cabina 360 AM Medinas
#
# Objetivo: ofuscar y reducir el APK para dificultar la ingeniería inversa,
# SIN romper el puente entre la página web y la app.

# --- CRÍTICO: el puente JavaScript ---
# La página llama por nombre a AMMApp.guardar(), AMMApp.compartir(), etc.
# R8 no ve a esos "llamadores" (están en JS), así que hay que conservar
# todos los métodos marcados con @JavascriptInterface o la app dejaría de
# responder a los botones de la cabina.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Conserva la clase puente completa (se instancia por reflexión desde el WebView).
-keep class mx.ammedinas.cabina.MainActivity$Puente { *; }

# La Activity la referencia el AndroidManifest por nombre; se conserva su nombre.
-keep class mx.ammedinas.cabina.MainActivity { *; }

# No conservar atributos de depuración (números de línea / archivo fuente):
# así el stack decompilado revela menos. Quita estas dos líneas si algún día
# necesitas depurar crashes con líneas exactas.
-dontwarn android.webkit.**
