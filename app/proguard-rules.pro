# Сервис клавиатуры и экран настроек вызываются системой по имени
-keep class com.expressive.keyboard.ComposeKeyboardService { *; }
-keep class com.expressive.keyboard.MainActivity { *; }

# OkHttp (через Coil) ссылается на необязательные TLS-провайдеры
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# Rhino (JavaScript модов): классы ищутся по имени через рефлексию
-keep class !org.mozilla.javascript.tools.**, org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.lang.model.**
-dontwarn jdk.dynalink.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
