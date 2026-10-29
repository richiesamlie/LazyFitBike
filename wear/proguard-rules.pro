# Preserve Wear OS services and activities
-keep class com.fitness.zwiftbike.wear.MainActivity { *; }
-keep class com.fitness.zwiftbike.wear.SensorWorkoutService { *; }
-keep class com.fitness.zwiftbike.wear.WearOtaListenerService { *; }
-dontwarn org.slf4j.**
