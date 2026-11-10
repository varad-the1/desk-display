package com.deskdisplay.app;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose. Having this service enabled (Notification access) is what lets the app
 * read and control the active media session (Spotify, YouTube Music, ...).
 */
public class MediaListener extends NotificationListenerService {
}
