package com.reactnativecommunity.rctaudiotoolkit;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.AudioAttributes;
import android.media.AudioAttributes.Builder;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Log;
import android.net.Uri;
import android.content.ContextWrapper;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.IOException;
import java.io.File;
import java.lang.Thread;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class AudioPlayerModule extends ReactContextBaseJavaModule implements MediaPlayer.OnInfoListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnSeekCompleteListener,
        MediaPlayer.OnBufferingUpdateListener, LifecycleEventListener, AudioManager.OnAudioFocusChangeListener {
    private static final String LOG_TAG = "AudioPlayerModule";

    private static final String OUTPUT_PHONE = "Phone";
    private static final String OUTPUT_PHONE_SPEAKER = "Phone Speaker";
    private static final String OUTPUT_BLUETOOTH = "Bluetooth";
    private static final String OUTPUT_HEADPHONES = "Headphones";

    Map<Integer, MediaPlayer> playerPool = new HashMap<>();
    Map<Integer, Boolean> playerAutoDestroy = new HashMap<>();
    Map<Integer, Boolean> playerContinueInBackground = new HashMap<>();
    Map<Integer, Callback> playerSeekCallback = new HashMap<>();

    boolean looping = false;
    private ReactApplicationContext context;
    private AudioManager mAudioManager;
    private Integer lastPlayerId;

    public AudioPlayerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.context = reactContext;
        reactContext.addLifecycleEventListener(this);
        this.mAudioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onHostResume() {
        // Activity `onResume`
    }

    @Override
    public void onHostPause() {
        // Need to create a copy here because it is possible for other code to modify playerPool
        // at the same time which will lead to a ConcurrentModificationException being thrown
        Map<Integer, MediaPlayer> playerPoolCopy = new HashMap<>(this.playerPool);

        for (Map.Entry<Integer, MediaPlayer> entry : playerPoolCopy.entrySet()) {
            Integer playerId = entry.getKey();

            if (!this.playerContinueInBackground.get(playerId)) {
                MediaPlayer player = entry.getValue();
                if (player == null) {
                    continue;
                }

                try {
                    player.pause();

                    WritableMap info = getInfo(player);

                    WritableMap data = new WritableNativeMap();
                    data.putString("message", "Playback paused due to onHostPause");
                    data.putMap("info", info);

                    emitEvent(playerId, "pause", data);
                } catch (Exception e) {
                    Log.e(LOG_TAG, e.toString());
                }
            }
        }
    }

    @Override
    public void onHostDestroy() {
        // Activity `onDestroy`
    }

    @Override
    public String getName() {
        return "RCTAudioPlayer";
    }

    private void emitEvent(Integer playerId, String event, WritableMap data) {
        WritableMap payload = new WritableNativeMap();
        payload.putString("event", event);
        payload.putMap("data", data);

        this.context
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("RCTAudioPlayerEvent:" + playerId, payload);
    }

    private WritableMap errObj(final String code, final String message, final boolean enableLog) {
        WritableMap err = Arguments.createMap();

        String stackTraceString = "";
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement e : stackTrace) {
                stackTraceString += (e != null ? e.toString() : "null") + "\n";
            }
        } catch (Exception e) {
            stackTraceString = "Exception occurred while parsing stack trace";
        }

        err.putString("err", code);
        err.putString("message", message);

        if (enableLog) {
            err.putString("stackTrace", stackTraceString);
            Log.e(LOG_TAG, message);
            Log.d(LOG_TAG, stackTraceString);
        }

        return err;
    }

    private WritableMap errObj(final String code, final String message) {
        return errObj(code, message, true);
    }

    private Uri uriFromPath(String path) {
        File file = null;
        String fileNameWithoutExt;
        String extPath;

        // Try finding file in app data directory
        extPath = new ContextWrapper(this.context).getFilesDir() + "/" + path;
        file = new File(extPath);
        if (file.exists()) {
            return Uri.fromFile(file);
        }

        // Try finding file on sdcard
        extPath = Environment.getExternalStorageDirectory() + "/" + path;
        file = new File(extPath);
        if (file.exists()) {
            return Uri.fromFile(file);
        }

        // Try finding file by full path
        file = new File(path);
        if (file.exists()) {
            return Uri.fromFile(file);
        }

        // Try finding file in Android "raw" resources
        if (path.lastIndexOf('.') != -1) {
            fileNameWithoutExt = path.substring(0, path.lastIndexOf('.'));
        } else {
            fileNameWithoutExt = path;
        }

        int resId = this.context.getResources().getIdentifier(fileNameWithoutExt,
            "raw", this.context.getPackageName());
        if (resId != 0) {
            return Uri.parse("android.resource://" + this.context.getPackageName() + "/" + resId);
        }

        // Otherwise pass whole path string as URI and hope for the best
        return Uri.parse(path);
    }

    @ReactMethod
    public void destroy(Integer playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);

        if (player != null) {
            player.release();
            this.playerPool.remove(playerId);
            this.playerAutoDestroy.remove(playerId);
            this.playerContinueInBackground.remove(playerId);
            this.playerSeekCallback.remove(playerId);

            WritableMap data = new WritableNativeMap();
            data.putString("message", "Destroyed player");

            emitEvent(playerId, "info", data);
        }

        if (callback != null) {
            callback.invoke();
        }
    }

    private void destroy(Integer playerId) {
        this.destroy(playerId, null);
    }

    @ReactMethod
    public void seek(Integer playerId, Integer position, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        if (position >= 0) {
            Callback oldCallback = this.playerSeekCallback.get(playerId);

            if (oldCallback != null) {
                oldCallback.invoke(errObj("seekfail", "new seek operation before old one completed", false));
                this.playerSeekCallback.remove(playerId);
            }

            this.playerSeekCallback.put(playerId, callback);
            player.seekTo(position);
        }
    }

    private WritableMap getInfo(MediaPlayer player) {
        WritableMap info = Arguments.createMap();

        info.putDouble("duration", player.getDuration());
        info.putDouble("position", player.getCurrentPosition());
        info.putDouble("audioSessionId", player.getAudioSessionId());

        return info;
    }

    @ReactMethod
    public void prepare(Integer playerId, String path, final ReadableMap options, final Callback callback) {
        if (path == null || path.isEmpty()) {
            callback.invoke(errObj("nopath", "Provided path was empty"));
            return;
        }

        // Release old player if exists
        destroy(playerId);
        this.lastPlayerId = playerId;

        Uri uri = uriFromPath(path);

        //MediaPlayer player = MediaPlayer.create(this.context, uri, null, attributes);
        MediaPlayer player = new MediaPlayer();
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);

        /*
        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_UNKNOWN)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .build();

        player.setAudioAttributes(attributes);
        */

        try {
            Log.d(LOG_TAG, uri.getPath());
            player.setDataSource(this.context, uri);
        } catch (IOException e) {
            callback.invoke(errObj("invalidpath", e.toString()));
            return;
        }

        setAudioOutput(options.getString("output"));

        player.setOnErrorListener(this);
        player.setOnInfoListener(this);
        player.setOnCompletionListener(this);
        player.setOnSeekCompleteListener(this);
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { // Async preparing, so we need to run the callback after preparing has finished

            @Override
            public void onPrepared(MediaPlayer player) {
                callback.invoke(null, getInfo(player));
            }

        });

        this.playerPool.put(playerId, player);

        // Auto destroy player by default
        boolean autoDestroy = true;

        if (options.hasKey("autoDestroy")) {
            autoDestroy = options.getBoolean("autoDestroy");
        }

        // Don't continue in background by default
        boolean continueInBackground = false;

        if (options.hasKey("continuesToPlayInBackground")) {
            continueInBackground = options.getBoolean("continuesToPlayInBackground");
        }

        this.playerAutoDestroy.put(playerId, autoDestroy);
        this.playerContinueInBackground.put(playerId, continueInBackground);

        try {
            player.prepareAsync();
        } catch (Exception e) {
            callback.invoke(errObj("prepare", e.toString()));
        }
    }

    @ReactMethod
    public void set(Integer playerId, ReadableMap options, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        if (options.hasKey("wakeLock")) {
            // TODO: can we disable the wake lock also?
            if (options.getBoolean("wakeLock")) {
                player.setWakeMode(this.context, PowerManager.PARTIAL_WAKE_LOCK);
            }
        }

        if (options.hasKey("autoDestroy")) {
            this.playerAutoDestroy.put(playerId, options.getBoolean("autoDestroy"));
        }

        if (options.hasKey("continuesToPlayInBackground")) {
            this.playerContinueInBackground.put(playerId, options.getBoolean("continuesToPlayInBackground"));
        }

        if (options.hasKey("volume") && !options.isNull("volume")) {
            double vol = options.getDouble("volume");
            player.setVolume((float) vol, (float) vol);
        }

        if (options.hasKey("looping") && !options.isNull("looping")) {
            this.looping = options.getBoolean("looping");
        }

        // `PlaybackParams` was only added in API 23
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (options.hasKey("speed") || options.hasKey("pitch"))) {
            PlaybackParams params = new PlaybackParams();

            boolean needToPauseAfterSet = false;
            if (options.hasKey("speed") && !options.isNull("speed")) {
                // If the player wasn't already playing, then setting the speed value to a non-zero value
                // will start it playing and we don't want that so we need to make sure to pause it straight
                // after setting the speed value
                boolean wasAlreadyPlaying = player.isPlaying();
                float speedValue = (float) options.getDouble("speed");
                needToPauseAfterSet = !wasAlreadyPlaying && speedValue != 0.0f;

                params.setSpeed(speedValue);
            }

            if (options.hasKey("pitch") && !options.isNull("pitch")) {
                params.setPitch((float) options.getDouble("pitch"));
            }

            player.setPlaybackParams(params);

            if (needToPauseAfterSet) {
                player.pause();
            }
        }

        callback.invoke();
    }

    @ReactMethod
    public void play(Integer playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {
            this.mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            player.start();

            callback.invoke(null, getInfo(player));
        } catch (Exception e) {
            callback.invoke(errObj("playback", e.toString()));
        }
    }

    @ReactMethod
    public void pause(Integer playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {

            player.pause();

            WritableMap info = getInfo(player);

            WritableMap data = new WritableNativeMap();
            data.putString("message", "Playback paused");
            data.putMap("info", info);

            emitEvent(playerId, "pause", data);

            callback.invoke(null, getInfo(player));

        } catch (Exception e) {
            callback.invoke(errObj("pause", e.toString()));
        }
    }

    @ReactMethod
    public void stop(Integer playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {
            if (this.playerAutoDestroy.get(playerId)) {
                player.pause();
                Log.d(LOG_TAG, "stop(): Autodestroying player...");
                destroy(playerId);
                callback.invoke();
            } else {
                // "Fake" stopping on Android by pausing and seeking to 0 so
                // that we remain in prepared state
                Callback oldCallback = this.playerSeekCallback.get(playerId);

                if (oldCallback != null) {
                    oldCallback.invoke(errObj("seekfail", "Playback stopped before seek operation could finish"));
                    this.playerSeekCallback.remove(playerId);
                }

                this.playerSeekCallback.put(playerId, callback);

                player.seekTo(0);
                player.pause();
            }
        } catch (Exception e) {
            callback.invoke(errObj("stop", e.toString()));
        }
    }

    @ReactMethod
    public void getCurrentTime(Integer playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {
            callback.invoke(null, getInfo(player));
        } catch (Exception e) {
            callback.invoke(errObj("getCurrentTime", e.toString()));
        }
    }

    // Find playerId matching player from playerPool
    private Integer getPlayerId(MediaPlayer player) {
        for (Entry<Integer, MediaPlayer> entry : playerPool.entrySet()) {
            if (equals(player, entry.getValue())) {
                return entry.getKey();
            }
        }

        return null;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer player, int percent) {
        Integer playerId = getPlayerId(player);

        WritableMap data = new WritableNativeMap();
        data.putString("message", "Status update for media stream buffering");
        data.putInt("percent", percent);
        emitEvent(playerId, "progress", data);
    }

    @Override
    public void onSeekComplete(MediaPlayer player) {
        Integer playerId = getPlayerId(player);

        // Invoke seek callback
        Callback callback = this.playerSeekCallback.get(playerId);
        if (callback != null) {
            callback.invoke(null, getInfo(player));
            this.playerSeekCallback.remove(playerId);
        }

        // Emit "seeked" event
        WritableMap data = new WritableNativeMap();
        data.putString("message", "Seek operation completed");
        emitEvent(playerId, "seeked", data);
    }

    @Override
    public void onCompletion(MediaPlayer player) {
        Integer playerId = getPlayerId(player);

        WritableMap data = new WritableNativeMap();

        player.seekTo(0);
        if (this.looping) {
            player.start();
            data.putString("message", "Media playback looped");
            emitEvent(playerId, "looped", data);
        } else {
            data.putString("message", "Playback completed");
            emitEvent(playerId, "ended", data);
        }

        if (!this.looping && this.playerAutoDestroy.get(playerId)) {
            Log.d(LOG_TAG, "onCompletion(): Autodestroying player...");
            destroy(playerId);
        }
    }

    @Override
    public boolean onError(MediaPlayer player, int what, int extra) {
        Integer playerId = getPlayerId(player);

        // TODO: translate these codes into english
        WritableMap err = new WritableNativeMap();
        err.putInt("what", what);
        err.putInt("extra", extra);

        WritableMap data = new WritableNativeMap();
        data.putMap("err", err);
        data.putString("message", "Android MediaPlayer error");

        emitEvent(playerId, "error", data);

        destroy(playerId);
        return true; // don't call onCompletion listener afterwards
    }

    @Override
    public boolean onInfo(MediaPlayer player, int what, int extra) {
        Integer playerId = getPlayerId(player);

        // TODO: translate these codes into english
        WritableMap info = new WritableNativeMap();
        info.putInt("what", what);
        info.putInt("extra", extra);

        WritableMap data = new WritableNativeMap();
        data.putMap("info", info);
        data.putString("message", "Android MediaPlayer info");

        emitEvent(playerId, "info", data);

        return false;
    }

    // Audio Focus
    public void onAudioFocusChange(int focusChange)
    {
        switch (focusChange)
        {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                //MediaPlayer player = this.playerPool.get(this.lastPlayerId);
                WritableMap data = new WritableNativeMap();
                data.putString("message", "Lost audio focus, playback paused");

                this.emitEvent(this.lastPlayerId, "forcePause", data);
                break;
        }
    }


    // Utils
    public static boolean equals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    @ReactMethod
    private void setAudioOutput(String output)
    {
        if (output == null) {
            return;
        }

        AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);

        switch (output){
        case AudioPlayerModule.OUTPUT_BLUETOOTH:
          audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
          audioManager.startBluetoothSco();
          audioManager.setBluetoothScoOn(true);
          break;
        case AudioPlayerModule.OUTPUT_PHONE_SPEAKER:
          if (audioManager.isBluetoothScoOn() || audioManager.isBluetoothA2dpOn()) {
            audioManager.setMode(AudioManager.MODE_IN_CALL);
          } else {
            audioManager.setMode(AudioManager.MODE_NORMAL);
          }
          audioManager.stopBluetoothSco();
          audioManager.setBluetoothScoOn(false);
          audioManager.setSpeakerphoneOn(true);
          break;
        case AudioPlayerModule.OUTPUT_PHONE:
          audioManager.setMode(AudioManager.MODE_IN_CALL);
          //break;
        case "None":
          audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
          audioManager.stopBluetoothSco();
          audioManager.setSpeakerphoneOn(false);
          audioManager.setBluetoothScoOn(false);
          break;
        default:
          //audioManager.setSpeakerphoneOn(true);
          break;
        }
    }
}
