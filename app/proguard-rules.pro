# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ytstyle.bili.**$$serializer { *; }
-keepclassmembers class com.ytstyle.bili.** {
    *** Companion;
}
-keepclasseswithmembers class com.ytstyle.bili.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit / OkHttp
-keepattributes Signature, Exceptions
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Media3
-dontwarn androidx.media3.**

# Hilt
-keep class dagger.hilt.** { *; }

# Shizuku UserService: the class is instantiated BY NAME in a separate process
# (Shizuku's app_process), so R8 must not rename or remove it, and it must keep
# both constructors. The AIDL interface/stub are reflected over Binder.
-keep class com.ytstyle.bili.data.extcache.ExtCacheShellService { *; }
-keep class com.ytstyle.bili.data.extcache.IExtCacheService { *; }
-keep class com.ytstyle.bili.data.extcache.IExtCacheService$Stub { *; }
-keep class com.ytstyle.bili.data.extcache.IExtCacheService$Stub$Proxy { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
