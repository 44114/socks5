# JSch
-keep class com.jcraft.jsch.** { *; }

# Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# dnsjava
-keep class org.xbill.DNS.** { *; }

# Room
-keep class com.socks5.data.model.** { *; }

# --- Suppress warnings for optional/transitive dependencies never used on Android ---
# JNA (Windows-specific, not on Android)
-dontwarn com.sun.jna.**
# JSR-305 annotations (not on Android)
-dontwarn javax.annotation.**
# JNDI (not on Android)
-dontwarn javax.naming.**
# Lombok (build-time only)
-dontwarn lombok.**
# Log4j (JSch optional logger, not on Android)
-dontwarn org.apache.logging.log4j.**
# GSS-API / Kerberos (JSch optional)
-dontwarn org.ietf.jgss.**
# Unix domain sockets (JSch optional, not on Android)
-dontwarn org.newsclub.net.unix.**
# SLF4J static binding (not on Android)
-dontwarn org.slf4j.impl.**
# dnsjava SPI/JVM extensions
-dontwarn org.xbill.DNS.spi.**
-dontwarn sun.net.spi.nameservice.**
