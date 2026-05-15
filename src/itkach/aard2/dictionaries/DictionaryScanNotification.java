package itkach.aard2.dictionaries;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import itkach.aard2.MainActivity;
import itkach.aard2.R;

/**
 * Helper class for showing notifications during dictionary folder scanning.
 */
public class DictionaryScanNotification {
    private static final String CHANNEL_ID = "dictionary_scan";
    private static final int NOTIFICATION_ID = 1001;
    
    private final Context context;
    private final NotificationManager notificationManager;
    private final NotificationCompat.Builder builder;
    
    public DictionaryScanNotification(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        createNotificationChannel();
        
        // Create pending intent to open the app
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, 
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        // Build notification
        builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.dictionary)
                .setContentTitle(context.getString(R.string.notification_scanning_dictionaries))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true);
    }
    
    /**
     * Creates the notification channel for dictionary scanning (Android O+).
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_dictionary_scan),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.notification_channel_dictionary_scan_desc));
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    /**
     * Shows the notification indicating scan has started.
     */
    public void showScanStarted() {
        builder.setContentText(context.getString(R.string.notification_scanning_starting))
                .setProgress(0, 0, true);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
    
    /**
     * Updates the notification with current progress.
     * 
     * @param dictionaryName Name of the dictionary being loaded
     * @param current Current dictionary index (1-based)
     * @param total Total number of dictionaries
     */
    public void updateProgress(@NonNull String dictionaryName, int current, int total) {
        String text = context.getString(R.string.notification_loading_dictionary, 
                dictionaryName, current, total);
        builder.setContentText(text)
                .setProgress(total, current, false);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
    
    /**
     * Shows completion notification with results.
     * 
     * @param addedCount Number of dictionaries added
     * @param removedCount Number of dictionaries removed
     */
    public void showCompleted(int addedCount, int removedCount) {
        String text;
        if (addedCount > 0 && removedCount > 0) {
            text = context.getString(R.string.notification_scan_completed_both, 
                    addedCount, removedCount);
        } else if (addedCount > 0) {
            text = context.getResources().getQuantityString(
                    R.plurals.notification_scan_completed_added, addedCount, addedCount);
        } else if (removedCount > 0) {
            text = context.getResources().getQuantityString(
                    R.plurals.notification_scan_completed_removed, removedCount, removedCount);
        } else {
            text = context.getString(R.string.notification_scan_completed_no_changes);
        }
        
        builder.setContentText(text)
                .setProgress(0, 0, false)
                .setOngoing(false);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
        
        // Auto-dismiss after a few seconds
        android.os.Handler handler = new android.os.Handler(context.getMainLooper());
        handler.postDelayed(this::dismiss, 3000);
    }
    
    /**
     * Dismisses the notification.
     */
    public void dismiss() {
        notificationManager.cancel(NOTIFICATION_ID);
    }
    
    /**
     * Gets the notification for use with a foreground service.
     */
    @NonNull
    public Notification getNotification() {
        return builder.build();
    }
    
    /**
     * Gets the notification ID.
     */
    public int getNotificationId() {
        return NOTIFICATION_ID;
    }
}
