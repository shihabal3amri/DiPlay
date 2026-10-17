# Entry point launched by app_process from the installed application classpath.
-keep class com.shilapi.xcertplay.hud.BydAmbientLightTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.hud.BydCallPopupTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.transport.IphoneUsbGrantTool {
    public static void main(java.lang.String[]);
}
# 原车歌曲写入由授权 shell 从 APK 调用，保留常驻及兼容入口。
-keep class com.shilapi.xcertplay.hud.BydClusterSongWorker {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.hud.BydClusterSongTool {
    public static void main(java.lang.String[]);
}
