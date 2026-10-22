package com.minimal.blacklauncher;

import android.app.admin.DeviceAdminReceiver;

/** Only grants the "lock screen" policy. Cannot wipe, read or control anything else. */
public class LockAdmin extends DeviceAdminReceiver {}
