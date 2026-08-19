# OkHttp / Okio 常用混淆规则（当前 release 未开启 minify，保留以备后用）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okhttp3.** { *; }
-keep interface okio.** { *; }
