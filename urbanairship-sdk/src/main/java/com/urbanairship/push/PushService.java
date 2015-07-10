/*
Copyright 2009-2015 Urban Airship Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE URBAN AIRSHIP INC ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
EVENT SHALL URBAN AIRSHIP INC OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.urbanairship.push;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.util.SparseArray;

import com.urbanairship.AirshipConfigOptions;
import com.urbanairship.Autopilot;
import com.urbanairship.Logger;
import com.urbanairship.UAirship;
import com.urbanairship.google.PlayServicesUtils;
import com.urbanairship.http.Response;
import com.urbanairship.json.JsonException;
import com.urbanairship.json.JsonValue;
import com.urbanairship.util.UAHttpStatusUtil;
import com.urbanairship.util.UAStringUtil;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service class for handling push notifications.
 */
public class PushService extends IntentService {

    /**
     * Max time between channel registration updates.
     */
    private static final long CHANNEL_REREGISTRATION_INTERVAL_MS = 24 * 60 * 60 * 1000; //24H

    /**
     * The starting back off time for channel and push registration retries.
     */
    private static final long STARTING_BACK_OFF_TIME = 10000; // 10 seconds.

    /**
     * The max back off time for channel and push registration retries.
     */
    private static final long MAX_BACK_OFF_TIME = 5120000; // About 85 mins.

    /**
     * The timeout before a wake lock is released.
     */
    private static final long WAKE_LOCK_TIMEOUT_MS = 60 * 1000; // 1 minute

    /**
     * Action to start channel and push registration.
     */
    static final String ACTION_START_REGISTRATION = "com.urbanairship.push.ACTION_START_REGISTRATION";

    /**
     * Action notifying the service that push registration has finished.
     */
    static final String ACTION_PUSH_REGISTRATION_FINISHED = "com.urbanairship.push.ACTION_PUSH_REGISTRATION_FINISHED";

    /**
     * Action to update channel registration.
     */
    static final String ACTION_UPDATE_REGISTRATION = "com.urbanairship.push.ACTION_UPDATE_REGISTRATION";

    /**
     * Action to retry channel registration.
     */
    static final String ACTION_RETRY_CHANNEL_REGISTRATION = "com.urbanairship.push.ACTION_RETRY_CHANNEL_REGISTRATION";

    /**
     * Action to retry push registration.
     */
    static final String ACTION_RETRY_PUSH_REGISTRATION = "com.urbanairship.push.ACTION_RETRY_PUSH_REGISTRATION";

    /**
     * Action sent when a push is received.
     */
    static final String ACTION_PUSH_RECEIVED = "com.urbanairship.push.ACTION_PUSH_RECEIVED";

    /**
     * Action to update named user association or disassociation.
     */
    static final String ACTION_UPDATE_NAMED_USER = "com.urbanairship.push.ACTION_UPDATE_NAMED_USER";

    /**
     * Action to retry update named user association or disassociation.
     */
    static final String ACTION_RETRY_UPDATE_NAMED_USER = "com.urbanairship.push.ACTION_RETRY_UPDATE_NAMED_USER";

    /**
     * Action to update the channel tag groups.
     */
    static final String ACTION_UPDATE_CHANNEL_TAG_GROUPS = "com.urbanairship.push.ACTION_UPDATE_CHANNEL_TAG_GROUPS";

    /**
     * Action to retry update channel tag groups.
     */
    static final String ACTION_RETRY_UPDATE_CHANNEL_TAG_GROUPS = "com.urbanairship.push.ACTION_RETRY_UPDATE_CHANNEL_TAG_GROUPS";

    /**
     * Action to update named user tags.
     */
    static final String ACTION_UPDATE_NAMED_USER_TAGS = "com.urbanairship.push.ACTION_UPDATE_NAMED_USER_TAGS";

    /**
     * Action to retry update named user tags.
     */
    static final String ACTION_RETRY_UPDATE_NAMED_USER_TAGS = "com.urbanairship.push.ACTION_RETRY_UPDATE_NAMED_USER_TAGS";

    /**
     * Action to clear the pending named user tags.
     */
    static final String ACTION_CLEAR_PENDING_NAMED_USER_TAGS = "com.urbanairship.push.ACTION_CLEAR_PENDING_NAMED_USER_TAGS";

    /**
     * Extra containing tag groups to add to channel tag groups or named user tags.
     *
     * @hide
     */
    public static final String EXTRA_ADD_TAG_GROUPS = "com.urbanairship.push.EXTRA_ADD_TAG_GROUPS";

    /**
     * Extra containing tag groups to remove from channel tag groups or named user tags.
     *
     * @hide
     */
    public static final String EXTRA_REMOVE_TAG_GROUPS = "com.urbanairship.push.EXTRA_REMOVE_TAG_GROUPS";

    /**
     * Extra for wake lock ID. Set and removed by the service.
     */
    static final String EXTRA_WAKE_LOCK_ID = "com.urbanairship.push.EXTRA_WAKE_LOCK_ID";

    /**
     * Extra that stores the back off time on the retry intents.
     */
    static final String EXTRA_BACK_OFF = "com.urbanairship.push.EXTRA_BACK_OFF";

    private static final SparseArray<WakeLock> wakeLocks = new SparseArray<>();

    /**
     * The delay value used for associate and disassociate named user retries.
     */
    private static long namedUserBackOff = 0;

    /**
     * The delay value used for updating channel tag groups retries.
     */
    private static long tagGroupsBackOff = 0;

    /**
     * The delay value used for updating named user tags retries.
     */
    private static long namedUserTagsBackOff = 0;

    private static int nextWakeLockID = 0;
    private static boolean isPushRegistering = false;

    private static long channelRegistrationBackOff = 0;

    private static long pushRegistrationBackOff = 0;


    private ChannelAPIClient channelClient;
    private TagGroupsAPIClient tagGroupsClient;
    private NamedUserAPIClient namedUserClient;

    /**
     * PushService constructor.
     */
    public PushService() {
        super("PushService");
    }

    /**
     * PushService constructor that specifies the channel and named user client. Used
     * for testing.
     * @param client The channel api client.
     * @param namedUserClient The named user api client.
     * @param tagGroupsClient The tag groups api client.
     */
    PushService(ChannelAPIClient client, NamedUserAPIClient namedUserClient, TagGroupsAPIClient tagGroupsClient) {
        super("PushService");
        this.channelClient = client;
        this.namedUserClient = namedUserClient;
        this.tagGroupsClient = tagGroupsClient;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();
        Autopilot.automaticTakeOff(getApplicationContext());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        Logger.verbose("PushService - Received intent: " + intent.getAction());


        String action = intent.getAction();
        int wakeLockId = intent.getIntExtra(EXTRA_WAKE_LOCK_ID, -1);
        intent.removeExtra(EXTRA_WAKE_LOCK_ID);


        try {
            switch (action) {
                case ACTION_PUSH_RECEIVED:
                    onPushReceived(intent);
                    break;
                case ACTION_PUSH_REGISTRATION_FINISHED:
                    onPushRegistrationFinished();
                    break;
                case ACTION_UPDATE_REGISTRATION:
                    onUpdateRegistration();
                    break;
                case ACTION_START_REGISTRATION:
                    onStartRegistration();
                    break;
                case ACTION_RETRY_CHANNEL_REGISTRATION:
                    onRetryChannelRegistration(intent);
                    break;
                case ACTION_RETRY_PUSH_REGISTRATION:
                    onRetryPushRegistration(intent);
                    break;
                case ACTION_UPDATE_NAMED_USER:
                    onUpdateNamedUser();
                    break;
                case ACTION_RETRY_UPDATE_NAMED_USER:
                    onRetryUpdateNamedUser(intent);
                    break;
                case ACTION_UPDATE_CHANNEL_TAG_GROUPS:
                    onUpdateTagGroups(intent);
                    break;
                case ACTION_RETRY_UPDATE_CHANNEL_TAG_GROUPS:
                    onRetryUpdateTagGroups(intent);
                    break;
                case ACTION_UPDATE_NAMED_USER_TAGS:
                    onUpdateNamedUserTags(intent);
                    break;
                case ACTION_RETRY_UPDATE_NAMED_USER_TAGS:
                    onRetryUpdateNamedUserTags(intent);
                    break;
                case ACTION_CLEAR_PENDING_NAMED_USER_TAGS:
                    onClearPendingNamedUserTags();
                    break;
            }
        } finally {
            if (wakeLockId >= 0) {
                releaseWakeLock(wakeLockId);
            }
        }
    }

    /**
     * The PushMessage will be parsed from the intent and delivered to
     * the PushManager.
     *
     * @param intent The value passed to onHandleIntent.
     */
    private void onPushReceived(Intent intent) {
        PushMessage message = new PushMessage(intent.getExtras());
        Logger.info("Received push message: " + message);
        UAirship.shared().getPushManager().deliverPush(message);
    }

    /**
     * Starts push registration or it will update channel registration. When
     * push registration is started, an intent with ACTION_PUSH_REGISTRATION_FINISHED
     * will be received which will trigger a channel update. While push registration
     * is in progress, any update registrations will be ignored.
     */
    private void onStartRegistration() {
        if (isPushRegistering) {
            // This will occur anytime we have multiple processes.
            return;
        }

        if (isPushRegistrationAllowed() && needsPushRegistration()) {
            startPushRegistration();
        } else {
            performChannelRegistration();
        }
    }

    /**
     * Updates channel registration.
     */
    private void onUpdateRegistration() {
        if (isPushRegistering) {
            Logger.verbose("PushService - Push registration in progress, skipping registration update.");
            return;
        }

        performChannelRegistration();
    }

    /**
     * Called when push registration is finished. Will trigger a channel registration
     * update.
     */
    private void onPushRegistrationFinished() {
        isPushRegistering = false;
        performChannelRegistration();
    }

    /**
     * Called when a push registration previously failed and is being retried.
     *
     * @param intent The value passed to onHandleIntent.
     */
    private void onRetryPushRegistration(Intent intent) {
        // Restore the back off if the application was restarted since the last retry.
        pushRegistrationBackOff = intent.getLongExtra(EXTRA_BACK_OFF, pushRegistrationBackOff);
        if (isPushRegistrationAllowed() && needsPushRegistration()) {
            startPushRegistration();
        }
    }

    /**
     * Called when a channel registration previously failed and is being retried.
     *
     * @param intent The value passed to onHandleIntent.
     */
    private void onRetryChannelRegistration(Intent intent) {
        // Restore the back off if the application was restarted since the last retry.
        channelRegistrationBackOff = intent.getLongExtra(EXTRA_BACK_OFF, channelRegistrationBackOff);
        performChannelRegistration();
    }

    /**
     * Updates a channel.
     *
     * @param channelLocation Channel location.
     * @param payload The ChannelRegistrationPayload payload.
     */
    private void updateChannel(URL channelLocation, ChannelRegistrationPayload payload) {
        PushManager pushManager = UAirship.shared().getPushManager();
        PushPreferences pushPreferences = pushManager.getPreferences();

        ChannelResponse response = getChannelClient().updateChannelWithPayload(channelLocation, payload);

        if (response == null || UAHttpStatusUtil.inServerErrorRange(response.getStatus())) {
            // Server error occurred, so retry later.
            Logger.error("Channel registration failed, will retry.");
            channelRegistrationBackOff = calculateNextBackOff(channelRegistrationBackOff);
            scheduleRetry(ACTION_RETRY_CHANNEL_REGISTRATION, channelRegistrationBackOff);
        } else if (UAHttpStatusUtil.inSuccessRange(response.getStatus())) {
            Logger.info("Channel registration succeeded with status: " + response.getStatus());

            // Set the last registration payload and time then notify registration succeeded
            pushPreferences.setLastRegistrationPayload(payload);
            pushPreferences.setLastRegistrationTime(System.currentTimeMillis());
            pushManager.sendRegistrationFinishedBroadcast(true);

            channelRegistrationBackOff = 0;
        } else if (response.getStatus() == 409) {
            // 409 Conflict. Delete channel and register again.
            pushManager.setChannel(null, null);
            pushPreferences.setLastRegistrationPayload(null);
            performChannelRegistration();
        } else {
            // Got an unexpected status code, so notify registration failed
            Logger.error("Channel registration failed with status: " + response.getStatus());
            pushManager.sendRegistrationFinishedBroadcast(false);

            channelRegistrationBackOff = 0;
        }
    }

    /**
     * Actually creates the channel.
     *
     * @param payload The ChannelRegistrationPayload payload.
     */
    private void createChannel(ChannelRegistrationPayload payload) {
        PushManager pushManager = UAirship.shared().getPushManager();
        PushPreferences pushPreferences = pushManager.getPreferences();
        ChannelResponse response = getChannelClient().createChannelWithPayload(payload);

        if (response == null || UAHttpStatusUtil.inServerErrorRange(response.getStatus())) {
            // Server error occurred, so retry later.
            Logger.error("Channel registration failed, will retry.");
            channelRegistrationBackOff = calculateNextBackOff(channelRegistrationBackOff);
            scheduleRetry(ACTION_RETRY_CHANNEL_REGISTRATION, channelRegistrationBackOff);
        } else if (response.getStatus() == HttpURLConnection.HTTP_OK || response.getStatus() == HttpURLConnection.HTTP_CREATED) {

            if (!UAStringUtil.isEmpty(response.getChannelLocation()) && !UAStringUtil.isEmpty(response.getChannelId())) {
                Logger.info("Channel creation succeeded with status: " + response.getStatus() + " channel ID: " + response.getChannelId());

                // Set the last registration payload and time then notify registration succeeded
                pushManager.setChannel(response.getChannelId(), response.getChannelLocation());
                pushPreferences.setLastRegistrationPayload(payload);
                pushPreferences.setLastRegistrationTime(System.currentTimeMillis());
                pushManager.sendRegistrationFinishedBroadcast(true);

                if (response.getStatus() == HttpURLConnection.HTTP_OK) {
                    // 200 means channel previously existed and a named user may be associated to it.
                    if (UAirship.shared().getAirshipConfigOptions().clearNamedUser) {
                        // If clearNamedUser is true on re-install, then disassociate if necessary
                        pushManager.getNamedUser().disassociateNamedUserIfNull();
                    }
                }

                // If setId was called before channel creation, update named user
                pushManager.getNamedUser().startUpdateService();

                pushManager.updateRegistration();
                pushManager.startUpdateTagsService();
            } else {
                Logger.error("Failed to register with channel ID: " + response.getChannelId() +
                        " channel location: " + response.getChannelLocation());
                pushManager.sendRegistrationFinishedBroadcast(false);
            }

            channelRegistrationBackOff = 0;
        } else {
            // Got an unexpected status code, so notify registration failed
            Logger.error("Channel registration failed with status: " + response.getStatus());
            pushManager.sendRegistrationFinishedBroadcast(false);

            channelRegistrationBackOff = 0;
        }
    }

    /**
     * Performs channel registration. Will either result in updating or creating a channel.
     */
    private void performChannelRegistration() {
        Logger.verbose("PushService - Performing channel registration.");
        PushManager pushManager = UAirship.shared().getPushManager();
        PushPreferences pushPreferences = pushManager.getPreferences();

        ChannelRegistrationPayload payload = pushManager.getNextChannelRegistrationPayload();
        if (!shouldUpdateRegistration(payload)) {
            Logger.verbose("PushService - Channel already up to date.");
            return;
        }

        String channelId = pushPreferences.getChannelId();
        URL channelLocation = getChannelLocationURL();

        if (channelLocation != null && !UAStringUtil.isEmpty(channelId)) {
            updateChannel(channelLocation, payload);
        } else {
            createChannel(payload);
        }
    }

    /**
     * Scheduled an intent for the service.
     *
     * @param action The action to schedule.
     * @param delay The delay in milliseconds.
     */
    private void scheduleRetry(String action, long delay) {
        Logger.debug("PushService - Rescheduling " + action + " in " + delay + " milliseconds.");

        Intent intent = new Intent(getApplicationContext(), PushService.class)
                .setAction(action)
                .putExtra(EXTRA_BACK_OFF, delay);

        AlarmManager alarmManager = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getService(getApplicationContext(), 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, pendingIntent);
    }

    /**
     * Calculate the next back off value.
     *
     * @param lastBackOff The last back off value.
     * @return The next back off value.
     */
    private long calculateNextBackOff(long lastBackOff) {
        long delay = Math.min(lastBackOff * 2, MAX_BACK_OFF_TIME);
        return Math.max(delay, STARTING_BACK_OFF_TIME);
    }

    /**
     * Starts registering for push with GCM.
     */
    private void startPushRegistration() {

        isPushRegistering = true;

        switch (UAirship.shared().getPlatformType()) {
            case UAirship.ANDROID_PLATFORM:
                if (!PlayServicesUtils.isGoogleCloudMessagingDependencyAvailable()) {
                    Logger.error("GCM is unavailable. Unable to register for push notifications. If using " +
                            "the modular Google Play Services dependencies, make sure the application includes " +
                            "the com.google.android.gms:play-services-gcm dependency.");
                    performChannelRegistration();
                } else {
                    try {
                        if (!GCMRegistrar.register()) {
                            Logger.error("GCM registration failed.");
                            isPushRegistering = false;
                            pushRegistrationBackOff = 0;
                            performChannelRegistration();
                        }
                    } catch (IOException e) {
                        Logger.error("GCM registration failed, will retry. GCM error: " + e.getMessage());
                        pushRegistrationBackOff = calculateNextBackOff(pushRegistrationBackOff);
                        scheduleRetry(ACTION_RETRY_PUSH_REGISTRATION, pushRegistrationBackOff);
                    }
                }
                break;

            case UAirship.AMAZON_PLATFORM:
                if (!ADMRegistrar.register()) {
                    Logger.error("ADM registration failed.");
                    isPushRegistering = false;
                    pushRegistrationBackOff = 0;
                    performChannelRegistration();
                }
                break;

            default:
                Logger.error("Unknown platform type. Unable to register for push.");
                isPushRegistering = false;
                performChannelRegistration();
        }
    }

    /**
     * Update named user ID.
     */
    private void onUpdateNamedUser() {
        PushManager pushManager = UAirship.shared().getPushManager();
        NamedUser namedUser = pushManager.getNamedUser();
        String currentId = namedUser.getId();
        String changeToken = namedUser.getChangeToken();
        String lastUpdatedToken = namedUser.getLastUpdatedToken();

        if (changeToken == null && lastUpdatedToken == null) {
            // Skip since no one has set the named user ID. Usually from a new or re-install.
            Logger.debug("PushService - New or re-install. Skipping.");
            return;
        }

        if (changeToken != null && changeToken.equals(lastUpdatedToken)) {
            // Skip since no change has occurred (token remain the same).
            Logger.debug("PushService - named user already updated. Skipping.");
            return;
        }

        if (UAStringUtil.isEmpty(pushManager.getChannelId())) {
            Logger.info("The channel ID does not exist. Will retry when channel ID is available.");
            return;
        }

        Response response;

        if (currentId == null) {
            // When currentId is null, disassociate the current named user ID.
            response = getNamedUserClient().disassociate(pushManager.getChannelId());
        } else {
            // When currentId is non-null, associate the currentId.
            response = getNamedUserClient().associate(currentId, pushManager.getChannelId());
        }

        if (response == null || UAHttpStatusUtil.inServerErrorRange(response.getStatus())) {
            // Server error occurred, so retry later.

            Logger.info("Update named user failed, will retry.");
            namedUserBackOff = calculateNextBackOff(namedUserBackOff);
            scheduleRetry(ACTION_RETRY_UPDATE_NAMED_USER, namedUserBackOff);
        } else if (UAHttpStatusUtil.inSuccessRange(response.getStatus())) {
            Logger.info("Update named user succeeded with status: " + response.getStatus());
            // When currentId is null, the disassociate request succeeded so we set the associatedId
            // to null (removing associatedId from preferenceDataStore). When currentId is non-null,
            // the associate request succeeded so we set the associatedId.
            namedUser.setLastUpdatedToken(changeToken);
            namedUserBackOff = 0;

            namedUser.startUpdateTagsService();
        } else if (response.getStatus() == HttpURLConnection.HTTP_FORBIDDEN) {
            Logger.info("Update named user failed with status: " + response.getStatus() +
                    " This action is not allowed when the app is in server-only mode.");
            namedUserBackOff = 0;
        } else {
            Logger.info("Update named user failed with status: " + response.getStatus());
            namedUserBackOff = 0;
        }
    }

    /**
     * Called when updating named user previously failed and is being retried.
     *
     * @param intent The value passed to onHandleIntent.
     */
    private void onRetryUpdateNamedUser(Intent intent) {
        // Restore the back off if the application was restarted since the last retry.
        namedUserBackOff = intent.getLongExtra(EXTRA_BACK_OFF, namedUserBackOff);
        onUpdateNamedUser();
    }

    /**
     * Update the channel tag groups.
     *
     * @param intent The value passed to onHandleIntent.
     */
    private void onUpdateTagGroups(Intent intent) {
        PushPreferences pushPreferences = UAirship.shared().getPushManager().getPreferences();

        Map<String, Set<String>> pendingAddTags = pushPreferences.getPendingAddTagGroups();
        Map<String, Set<String>> pendingRemoveTags = pushPreferences.getPendingRemoveTagGroups();

        // Add tags from bundle to pendingAddTags and remove them from pendingRemoveTags.
        Bundle addTagsBundle = intent.getBundleExtra(EXTRA_ADD_TAG_GROUPS);
        if (addTagsBundle != null) {
            combineTags(addTagsBundle, pendingAddTags, pendingRemoveTags);
        }

        // Add tags from bundle to pendingRemoveTags and remove them from pendingAddTags.
        Bundle removeTagsBundle = intent.getBundleExtra(EXTRA_REMOVE_TAG_GROUPS);
        if (removeTagsBundle != null) {
            combineTags(removeTagsBundle, pendingRemoveTags, pendingAddTags);
        }

        String channelId = UAirship.shared().getPushManager().getChannelId();
        if (channelId == null) {
            pushPreferences.setPendingTagGroupsChanges(pendingAddTags, pendingRemoveTags);
            Logger.debug("Unable to update tag groups until a channel is created.");
            return;
        }

        // if pendingAddTags and pendingRemoveTags size are both empty, then skip call to update channel tags.
        if (pendingAddTags.isEmpty() && pendingRemoveTags.isEmpty()) {
            return;
        }

        Response response = getTagGroupsClient().updateChannelTags(channelId, pendingAddTags, pendingRemoveTags);
        if (response == null || UAHttpStatusUtil.inServerErrorRange(response.getStatus())) {
            // Save pending
            pushPreferences.setPendingTagGroupsChanges(pendingAddTags, pendingRemoveTags);
            Logger.info("Failed to update tag groups, will retry. Saved pending tag groups.");
            tagGroupsBackOff = calculateNextBackOff(tagGroupsBackOff);
            scheduleRetry(ACTION_RETRY_UPDATE_CHANNEL_TAG_GROUPS, tagGroupsBackOff);
        } else if (UAHttpStatusUtil.inSuccessRange(response.getStatus())) {
            // Clear pending
            pushPreferences.setPendingTagGroupsChanges(null, null);
            Logger.info("Update tag groups succeeded with status: " + response.getStatus());
            tagGroupsBackOff = 0;
            logTagGroupResponseIssues(response.getResponseBody());
        } else {
            int status = response.getStatus();
            tagGroupsBackOff = 0;

            Logger.info("Update tag groups failed with status: " + status);
            logTagGroupResponseIssues(response.getResponseBody());

            if (status == HttpURLConnection.HTTP_FORBIDDEN || status == HttpURLConnection.HTTP_BAD_REQUEST) {
                // Clear pending
                pushPreferences.setPendingTagGroupsChanges(null, null);
            } else {
                // Save pending
                pushPreferences.setPendingTagGroupsChanges(pendingAddTags, pendingRemoveTags);
            }
        }
    }

    /**
     * Called when updating channel tag groups previously failed and is being retried.
     *
     * @param intent The value passed to onHandleIntent.
     */
    private void onRetryUpdateTagGroups(Intent intent) {
        // Restore the back off if the application was restarted since the last retry.
        tagGroupsBackOff = intent.getLongExtra(EXTRA_BACK_OFF, tagGroupsBackOff);
        onUpdateTagGroups(intent);
    }

    /**
     * Update named user tags.
     *
     * @param intent The value passed to onHandleIntent.
     */
    private void onUpdateNamedUserTags(Intent intent) {
        PushManager pushManager = UAirship.shared().getPushManager();
        NamedUser namedUser = pushManager.getNamedUser();

        Map<String, Set<String>> pendingAddTags = namedUser.getPendingAddTagGroups();
        Map<String, Set<String>> pendingRemoveTags = namedUser.getPendingRemoveTagGroups();

        // Add tags from bundle to pendingAddTags and remove them from pendingRemoveTags.
        Bundle addTagsBundle = intent.getBundleExtra(EXTRA_ADD_TAG_GROUPS);
        if (addTagsBundle != null) {
            combineTags(addTagsBundle, pendingAddTags, pendingRemoveTags);
        }

        // Add tags from bundle to pendingRemoveTags and remove them from pendingAddTags.
        Bundle removeTagsBundle = intent.getBundleExtra(EXTRA_REMOVE_TAG_GROUPS);
        if (removeTagsBundle != null) {
            combineTags(removeTagsBundle, pendingRemoveTags, pendingAddTags);
        }

        String namedUserId = namedUser.getId();
        if (namedUserId == null) {
            namedUser.setPendingTagGroupsChanges(pendingAddTags, pendingRemoveTags);
            Logger.verbose("Failed to update named user tags due to null named user ID. Saved pending tag groups.");
            return;
        }

        // if pendingAddTags and pendingRemoveTags size are both empty, then skip call to update named user tags.
        if (pendingAddTags.isEmpty() && pendingRemoveTags.isEmpty()) {
            return;
        }

        Response response = getTagGroupsClient().updateNamedUserTags(namedUserId, pendingAddTags, pendingRemoveTags);
        if (response == null || UAHttpStatusUtil.inServerErrorRange(response.getStatus())) {
            // Save pending
            namedUser.setPendingTagGroupsChanges(pendingAddTags, pendingRemoveTags);
            Logger.info("Failed to update named user tags, will retry. Saved pending tag groups.");
            namedUserTagsBackOff = calculateNextBackOff(namedUserTagsBackOff);
            scheduleRetry(ACTION_RETRY_UPDATE_NAMED_USER_TAGS, namedUserTagsBackOff);
        } else if (UAHttpStatusUtil.inSuccessRange(response.getStatus())) {
            // Clear pending
            namedUser.setPendingTagGroupsChanges(null, null);
            Logger.info("Update named user tags succeeded with status: " + response.getStatus());
            namedUserTagsBackOff = 0;
            logTagGroupResponseIssues(response.getResponseBody());
        } else {
            int status = response.getStatus();
            namedUserTagsBackOff = 0;

            Logger.info("Update named user tags failed with status: " + status);
            logTagGroupResponseIssues(response.getResponseBody());

            if (status == HttpURLConnection.HTTP_FORBIDDEN || status == HttpURLConnection.HTTP_BAD_REQUEST) {
                // Clear pending
                namedUser.setPendingTagGroupsChanges(null, null);
            } else {
                // Save pending
                namedUser.setPendingTagGroupsChanges(pendingAddTags, pendingRemoveTags);
            }

        }
    }

    /**
     * Clear pending named user tags.
     */
    private void onClearPendingNamedUserTags() {
        PushManager pushManager = UAirship.shared().getPushManager();
        pushManager.getNamedUser().setPendingTagGroupsChanges(null, null);
    }

    /**
     * Called when update named user tags previously failed and is being retried.
     *
     * @param intent The value passed to onHandleIntent.
     */
    private void onRetryUpdateNamedUserTags(Intent intent) {
        // Restore the back off if the application was restarted since the last retry.
        namedUserTagsBackOff = intent.getLongExtra(EXTRA_BACK_OFF, namedUserTagsBackOff);
        onUpdateNamedUserTags(intent);
    }

    /**
     * Get the channel location as a URL
     *
     * @return The channel location URL
     */
    private URL getChannelLocationURL() {
        String channelLocationString = UAirship.shared().getPushManager().getPreferences().getChannelLocation();
        if (!UAStringUtil.isEmpty(channelLocationString)) {
            try {
                return new URL(channelLocationString);
            } catch (MalformedURLException e) {
                Logger.error("Channel location from preferences was invalid: " + channelLocationString, e);
            }
        }

        return null;
    }

    /**
     * Check the specified payload and last registration time to determine if registration is required
     *
     * @param payload The channel registration payload
     * @return <code>True</code> if registration is required, <code>false</code> otherwise
     */
    private boolean shouldUpdateRegistration(ChannelRegistrationPayload payload) {
        PushPreferences pushPreferences = UAirship.shared().getPushManager().getPreferences();

        // check time and payload
        ChannelRegistrationPayload lastSuccessPayload = pushPreferences.getLastRegistrationPayload();
        long timeSinceLastRegistration = (System.currentTimeMillis() - pushPreferences.getLastRegistrationTime());
        return (!payload.equals(lastSuccessPayload)) ||
                (timeSinceLastRegistration >= CHANNEL_REREGISTRATION_INTERVAL_MS);
    }

    /**
     * Checks if push registration is needed.
     *
     * @return <code>true</code> if push registration is needed, otherwise <code>false</code>.
     */
    private boolean needsPushRegistration() {
        PushPreferences pushPreferences = UAirship.shared().getPushManager().getPreferences();

        if (UAirship.getPackageInfo().versionCode != pushPreferences.getAppVersionCode()) {
            Logger.verbose("PushService - Version code changed to " + UAirship.getPackageInfo().versionCode + ". Push re-registration required.");
            return true;
        } else if (!PushManager.getSecureId(getApplicationContext()).equals(pushPreferences.getDeviceId())) {
            Logger.verbose("PushService - Device ID changed. Push re-registration required.");
            return true;
        }

        switch (UAirship.shared().getPlatformType()) {
            case UAirship.ANDROID_PLATFORM:
                if (UAStringUtil.isEmpty(pushPreferences.getGcmId())) {
                    return true;
                }
                Set<String> senderIds = UAirship.shared().getAirshipConfigOptions().getGCMSenderIds();
                Set<String> registeredGcmSenderIds = pushPreferences.getRegisteredGcmSenderIds();

                // Unregister if we have different registered sender ids
                if (registeredGcmSenderIds != null && !registeredGcmSenderIds.equals(senderIds)) {
                    Logger.verbose("PushService - GCM sender IDs changed. Push re-registration required.");
                    return true;
                }

                Logger.verbose("PushService - GCM already registered with ID: " + pushPreferences.getGcmId());
                return false;

            case UAirship.AMAZON_PLATFORM:
                if (UAStringUtil.isEmpty(pushPreferences.getAdmId())) {
                    return true;
                }

                Logger.verbose("PushService - ADM already registered with ID: " + pushPreferences.getAdmId());
                return false;
        }

        return false;
    }

    /**
     * Check if the push registration is allowed for the current platform.
     *
     * @return <code>true</code> if push registration is allowed.
     */
    private boolean isPushRegistrationAllowed() {
        AirshipConfigOptions options = UAirship.shared().getAirshipConfigOptions();

        switch (UAirship.shared().getPlatformType()) {
            case UAirship.ANDROID_PLATFORM:
                if (!options.isTransportAllowed(AirshipConfigOptions.GCM_TRANSPORT)) {
                    Logger.info("Unable to register for push. GCM transport type is not allowed.");
                    return false;
                }

                return true;

            case UAirship.AMAZON_PLATFORM:
                if (!options.isTransportAllowed(AirshipConfigOptions.ADM_TRANSPORT)) {
                    Logger.info("Unable to register for push. ADM transport type is not allowed.");
                    return false;
                }

                return true;

            default:
                return false;
        }
    }

    /**
     * Start the <code>Push Service</code>.
     *
     * @param context The context in which the receiver is running.
     * @param intent The intent to start the service.
     */
    static void startServiceWithWakeLock(final Context context, Intent intent) {
        intent.setClass(context, PushService.class);

        // Acquire a wake lock and add the id to the intent
        intent.putExtra(EXTRA_WAKE_LOCK_ID, acquireWakeLock());

        context.startService(intent);
    }

    /**
     * Releases a wake lock.
     *
     * @param wakeLockId The id of the wake lock to release.
     */
    private static synchronized void releaseWakeLock(int wakeLockId) {
        Logger.verbose("PushService - Releasing wake lock: " + wakeLockId);

        WakeLock wakeLock = wakeLocks.get(wakeLockId);

        if (wakeLock != null) {
            wakeLocks.remove(wakeLockId);

            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    /**
     * Acquires a new wake lock.
     *
     * @return id of the wake lock.
     */
    private static synchronized int acquireWakeLock() {
        Context context = UAirship.getApplicationContext();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UA_GCM_WAKE_LOCK");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);

        wakeLocks.append(++nextWakeLockID, wakeLock);

        Logger.verbose("PushService - Acquired wake lock: " + nextWakeLockID);

        return nextWakeLockID;
    }

    /**
     * Gets the channel client. Creates it if it does not exist.
     *
     * @return The channel API client.
     */
    private ChannelAPIClient getChannelClient() {
        if (channelClient == null) {
            channelClient = new ChannelAPIClient();
        }
        return channelClient;
    }

    /**
     * Gets the named user client. Creates it if it does not exist.
     *
     * @return The named user API client.
     */
    private NamedUserAPIClient getNamedUserClient() {
        if (namedUserClient == null) {
            namedUserClient = new NamedUserAPIClient();
        }
        return namedUserClient;
    }

    /**
     * Gets the tag groups client. Creates it if it does not exist.
     *
     * @return The tag groups API client.
     */
    private TagGroupsAPIClient getTagGroupsClient() {
        if (tagGroupsClient == null) {
            tagGroupsClient = new TagGroupsAPIClient(UAirship.shared().getAirshipConfigOptions());
        }
        return tagGroupsClient;
    }

    /**
     * Combine the tags from bundle with the pending tags.
     *
     * @param tagsBundle The tags bundle.
     * @param tagsToAdd The pending tags to add tags to.
     * @param tagsToRemove The pending tags to remove tags from.
     */
    private void combineTags(Bundle tagsBundle, Map<String, Set<String>> tagsToAdd, Map<String, Set<String>> tagsToRemove) {
        for (String group : tagsBundle.keySet()) {
            List<String> tags = tagsBundle.getStringArrayList(group);

            // Add tags to tagsToAdd.
            if (tagsToAdd.containsKey(group)) {
                tagsToAdd.get(group).addAll(tags);
            } else {
                tagsToAdd.put(group, new HashSet<>(tags));
            }

            // Remove tags from tagsToRemove.
            if (tagsToRemove.containsKey(group)) {
                tagsToRemove.get(group).removeAll(tags);
            }
        }
    }

    /**
     * Log the response warnings and errors if they exist in the reponse body.
     *
     * @param responseBody The response body string.
     */
    private void logTagGroupResponseIssues(String responseBody) {
        if (responseBody == null) {
            return;
        }

        JsonValue responseJson = JsonValue.NULL;
        try {
            responseJson = JsonValue.parseString(responseBody);
        } catch (JsonException e) {
            Logger.error("Unable to parse tag group response", e);
        }

        if (responseJson.isJsonMap()) {
            // Check for any warnings in the response and log them if they exist.
            if (responseJson.getMap().containsKey("warnings")) {
                for (JsonValue warning : responseJson.getMap().get("warnings").getList()) {
                    Logger.info("Tag Groups warnings: " + warning);
                }
            }

            // Check for any errors in the response and log them if they exist.
            if (responseJson.getMap().containsKey("error")) {
                Logger.info("Tag Groups error: " + responseJson.getMap().get("error"));
            }
        }
    }
}