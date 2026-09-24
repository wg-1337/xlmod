# 多 dex 互锁守卫：类名与方法名必须固定（Obf 通过反射按名字取密钥分片）
-keep class net.xuele.xuelets.mod.guard.Guard8 { public static int a(); public static int tag(); }
-keep class net.xuele.xuelets.mod.guard.Guard9 { public static int b(); public static int tag(); }
-keepattributes *Annotation*
-dontwarn **
