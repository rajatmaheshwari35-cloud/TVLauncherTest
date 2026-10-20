TV Launcher Test APK — source project

Purpose
-------
A minimal, no-permissions Android TV launcher test for Android 11 (API 30).
It declares HOME, DEFAULT, and LEANBACK_LAUNCHER. Installing it does not
automatically make it the default launcher. The app only displays a black
test screen and returns to the prior app when BACK is pressed.

Safety scope
------------
- Does not disable or enable any existing package.
- Does not change the default launcher.
- Requests no permissions.
- Does not access the network.
- Package ID: com.example.tvlaunchertest

Build on Windows
----------------
1. Install Android Studio if not already installed.
2. In Android Studio, choose Open and select this extracted TVLauncherTest folder.
3. Allow Gradle sync. Install Android SDK Platform 35 if prompted.
4. Choose Build > Build Bundle(s) / APK(s) > Build APK(s).
5. The APK should be at:
   app\build\outputs\apk\debug\app-debug.apk

Install test APK using your existing platform-tools PowerShell window
---------------------------------------------------------------------
First make sure the TV is still connected:
  .\adb.exe -s 192.168.1.19:5555 get-state

Copy the built APK to C:\platform-tools\tv-launcher-test.apk, then run:
  .\adb.exe -s 192.168.1.19:5555 install -r .\tv-launcher-test.apk

If installation fails, stop and share the exact error. Do not change security
settings or enable/disable packages to work around it.

If installation succeeds, launch the test app explicitly:
  .\adb.exe -s 192.168.1.19:5555 shell am start -n com.example.tvlaunchertest/.MainActivity

Then take a TV screenshot and send it for verification. The existing Google TV
launcher remains the default. Do not choose the test app as HOME/default yet.

Note
----
This environment did not have the Android SDK/build tools available, so this
archive contains the complete Android Studio source project rather than a
precompiled APK. Build it locally with Android Studio before installing.
