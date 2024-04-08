package com.ble.demobleapplication;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.FileHandler;

/**
 * Created by Chandan Jana on 19-02-2024.
 * Company name: Mindteck
 * Email: chandan.jana@mindteck.com
 */
public class FileLogger {
    private static final String TAG = "FileLogger";
    private static final String LOG_FILE_NAME = "csa_app_log.txt";

    public static FileHandler logger = null;
    private static String filename = "csa_app_log.txt";

    static boolean isExternalStorageAvailable = false;
    static boolean isExternalStorageWriteable = false;
    static String state = Environment.getExternalStorageState();

    public static void logToFile(Context context, String message) {
        /*try {
            File logFile = getLogFile(context);
            if (logFile != null) {
                FileOutputStream fos = new FileOutputStream(logFile, true);
                String logMessage = getFormattedLogMessage(message);
                fos.write(logMessage.getBytes());
                fos.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing to log file: " + e.getMessage());
        }*/

        if (Environment.MEDIA_MOUNTED.equals(state)) {
            Log.d("TAGG", "Media for read and write");
            // We can read and write the media
            isExternalStorageAvailable = isExternalStorageWriteable = true;
        } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            Log.d("TAGG", "Media for read not to write");
            // We can only read the media
            isExternalStorageAvailable = true;
            isExternalStorageWriteable = false;
        } else {
            // Something else is wrong. It may be one of many other states, but all we need
            //  to know is we can neither read nor write
            Log.d("TAGG", "Media neither read nor write");
            isExternalStorageAvailable = isExternalStorageWriteable = false;
        }
        File dir = new File(Environment.getExternalStorageDirectory() + "/CSA");
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            if (!dir.exists()) {
                Log.d("TAGG", "Dir created " + dir.getPath());
                dir.mkdirs();
            }

            File logFile = new File(Environment.getExternalStorageDirectory() + "/CSA/" + filename);

            if (!logFile.exists()) {
                try {
                    logFile.createNewFile();
                    Log.d("TAGG", "File created " + logFile.getPath());
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                    Log.d("TAGG", "File not created " + e.getMessage());
                }
            }
            try {
                //BufferedWriter for performance, true to set append to file flag
                BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));

                buf.write(getFormattedLogMessage(message) + "\r\n");
                //buf.append(message);
                buf.newLine();
                buf.flush();
                buf.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private static File getLogFile(Context context) {
        File logsDir = new File(context.getExternalFilesDir(null), "logs");
        if (!logsDir.exists()) {
            if (!logsDir.mkdirs()) {
                Log.e(TAG, "Failed to create directory for logs");
                return null;
            }
        }
        return new File(logsDir, LOG_FILE_NAME);
    }

    private static String getFormattedLogMessage(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        return "[" + timestamp + "] " + message + "\n";
    }
}
