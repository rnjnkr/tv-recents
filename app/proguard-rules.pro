# Drop debug/verbose logging from release builds (it names the apps you open).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
