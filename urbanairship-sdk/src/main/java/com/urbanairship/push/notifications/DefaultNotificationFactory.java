/* Copyright 2016 Urban Airship and Contributors */

package com.urbanairship.push.notifications;

import android.app.Notification;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;

import com.urbanairship.push.PushMessage;
import com.urbanairship.util.UAStringUtil;

/**
 * The default notification factory.
 * <p/>
 * Notifications generated by this factory use the standard Android notification
 * layout and defaults to the BigTextStyle.
 * <p/>
 * To customize the factory, override {@link #extendBuilder(NotificationCompat.Builder, PushMessage, int)}.
 */
public class DefaultNotificationFactory extends NotificationFactory {

    /**
     * Default constructor.
     * @param context The application context
     */
    public DefaultNotificationFactory(@NonNull Context context) {
        super(context);
    }

    @Nullable
    @Override
    public final Notification createNotification(@NonNull PushMessage message, int notificationId) {
        if (UAStringUtil.isEmpty(message.getAlert())) {
            return null;
        }

        NotificationCompat.Builder builder = createNotificationBuilder(message, notificationId, new NotificationCompat.BigTextStyle().bigText(message.getAlert()));
        return extendBuilder(builder, message, notificationId).build();
    }

    /**
     * Called to apply any final overrides to the builder before the notification is built.
     *
     * @param builder The notification builder.
     * @param message The push message.
     * @param notificationId The notification ID.
     * @return The notification builder.
     */
    public NotificationCompat.Builder extendBuilder(@NonNull NotificationCompat.Builder builder, @NonNull  PushMessage message, int notificationId) {
        return builder;
    }
}
