// Ported from the user's carlink FX11 integration (c839f925).
package com.shilapi.xcertplay.vehicle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime-only adapter for the ECARX DIM APIs used by Geely's mediawidget.
 *
 * <p>The API interfaces live in the car's framework, not in a normal APK.  The
 * bridge therefore deliberately avoids a compile-time dependency and creates
 * the two data interfaces with {@link Proxy}.  This keeps the demo installable
 * on a phone while exercising the same entry point on a compatible head unit.</p>
 */
public final class GeelyEcarxDimInteractionBridge {
    private final NavigationInteractions navigationInteractions = new NavigationInteractions();
    private NavigationInteractions.Listener navigationActionListener;
    void setNavigationActionListener(NavigationInteractions.Listener listener) { navigationActionListener=listener; }

    // Share the content-based identity between playlist and playback snapshots.
    static String mediaUuid(MediaMetadata metadata) {
        return value(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
    }
    private static final String TAG = "GeelyEcarxDim";
    private static final String API_CLASS = "ecarx.fw.api.ECarXAPI";
    private static final String NAVI_CLASS = "ecarx.fw.api.diminteraction.EcarxNaviInteraction";
    private static final String MEDIA_CLASS = "ecarx.fw.api.diminteraction.EcarxMediaInteraction";
    private static final int NAV_STATUS_SUCCEED = 1;
    private static final int NAV_STATUS_START = 2;
    private static final int NAV_STATUS_END = 3;
    private static final int NAV_STATUS_REROUTING = 4;
    /** Generic online source used by the old MediaCenterAPI provider. */
    private static final int MEDIA_SOURCE_ONLINE = 6;
    /** OEM-specific source retained for newer widgets. */
    private static final int MEDIA_SOURCE_NETEASE = 15;
    private static final long INIT_RETRY_MS = 5000L;
    private static final long CALLBACK_REPUBLISH_MIN_INTERVAL_MS = 500L;
    private static final long NAVIGATION_REFRESH_MS = 500L;

    private static volatile GeelyEcarxDimInteractionBridge instance;

    private final Context context;
    private final Object lock = new Object();
    private final Handler callbackHandler = new Handler(Looper.getMainLooper());
    private final Handler navigationRetryHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Method> methodCache = new HashMap<>();
    private Context ecarxCarContext;
    private volatile Class<?> naviApiClass;
    private volatile Class<?> mediaApiClass;
    private volatile Object naviManager;
    private volatile Object mediaManager;
    private volatile Object mediaInteractionCallback;
    private volatile MediaMetadata lastMediaMetadata;
    private volatile PlaybackState lastPlaybackState;
    private volatile List<MediaMetadata> lastMediaPlaylist;
    private volatile int currentMediaSourceType = MEDIA_SOURCE_ONLINE;
    private volatile boolean mediaCallbackRegistered;
    private long lastCallbackRepublish;
    private volatile String status = "ECARX DIM 未探测";
    private volatile String navigationStatus = "ECARX DIM 导航未初始化";
    private volatile String mediaStatus = "ECARX DIM 音乐未初始化";
    private boolean navigationStarted;
    private boolean navigationRetryScheduled;
    private boolean navigationRetryRunning;
    private volatile boolean lastNavigationDimPublished;
    private int navigationRetryAttempt;
    private volatile NavigationInfo lastNavigation;
    private long lastNaviInitAttempt;
    private long lastMediaInitAttempt;

    private final Runnable navigationRetry = new Runnable() {
        @Override
        public void run() {
            navigationRetryScheduled = false;
            NavigationInfo info = lastNavigation;
            if (info == null) return;
            navigationRetryRunning = true;
            try {
                publishNavigation(info);
            } finally {
                navigationRetryRunning = false;
            }
            // A successful setter call has no delivery acknowledgement. Keep
            // the active route alive so a recreated instrument widget receives
            // a fresh TBT frame even after the initial retry window.
            if (lastNavigation == info
                    && info.maneuver != NavigationInfo.Maneuver.ARRIVED
                    && !navigationRetryScheduled) {
                navigationRetryScheduled = true;
                navigationRetryHandler.postDelayed(this, NAVIGATION_REFRESH_MS);
            }
        }
    };

    private GeelyEcarxDimInteractionBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public static GeelyEcarxDimInteractionBridge get(Context context) {
        GeelyEcarxDimInteractionBridge current = instance;
        if (current != null) return current;
        synchronized (GeelyEcarxDimInteractionBridge.class) {
            if (instance == null) {
                instance = new GeelyEcarxDimInteractionBridge(context);
            }
            return instance;
        }
    }

    public String status() {
        return status;
    }

    public String navigationStatus() {
        return navigationStatus;
    }

    public String mediaStatus() {
        return mediaStatus;
    }

    public boolean isNavigationAvailable() {
        return naviManager != null;
    }

    public boolean isMediaAvailable() {
        return mediaManager != null;
    }

    /** Loads both managers using the same ECarXAPI.creator flow as mediawidget. */
    public void start() {
        synchronized (lock) {
            long now = SystemClock.elapsedRealtime();
            if (naviManager == null
                    && (lastNaviInitAttempt == 0 || now - lastNaviInitAttempt >= INIT_RETRY_MS)) {
                lastNaviInitAttempt = now;
                try {
                    naviApiClass = loadEcarxClass(NAVI_CLASS);
                    naviManager = createManager(naviApiClass);
                    if (naviManager != null) {
                        boolean registered = navigationInteractions.register(naviManager,naviApiClass,(action,query) ->
                            callbackHandler.post(() -> {
                                if (navigationActionListener != null) navigationActionListener.onAction(action,query);
                            }));
                        Log.i(TAG,"Navigation interaction callback registered=" + registered);
                    }
                    navigationStatus = naviManager == null
                            ? "ECARX DIM 导航 Creator 返回 null"
                            : "ECARX DIM 导航 manager 已创建";
                } catch (Throwable error) {
                    naviManager = null;
                    naviApiClass = null;
                    navigationStatus = "ECARX DIM 导航初始化失败 · " + describe(error);
                    logFailure(navigationStatus, error);
                }
            }
            if (mediaManager == null
                    && (lastMediaInitAttempt == 0 || now - lastMediaInitAttempt >= INIT_RETRY_MS)) {
                lastMediaInitAttempt = now;
                try {
                    mediaApiClass = loadEcarxClass(MEDIA_CLASS);
                    mediaManager = createManager(mediaApiClass);
                    if (mediaManager == null) {
                        mediaStatus = "ECARX DIM 音乐 Creator 返回 null";
                    } else {
                        try {
                            registerMediaCallback();
                            mediaStatus = "ECARX DIM 音乐 manager 已创建 · callback 已注册";
                        } catch (Throwable callbackError) {
                            // Older firmware may expose the setter but reject callbacks.
                            // Keep the push path available and retain the exact reason.
                            mediaStatus = "ECARX DIM 音乐 manager 已创建 · callback 注册失败 · "
                                    + describe(callbackError);
                            logFailure(mediaStatus, callbackError);
                        }
                    }
                } catch (Throwable error) {
                    mediaManager = null;
                    mediaApiClass = null;
                    mediaStatus = "ECARX DIM 音乐初始化失败 · " + describe(error);
                    logFailure(mediaStatus, error);
                }
            }
            status = "ECARX DIM · 导航=" + compact(navigationStatus)
                    + " · 音乐=" + compact(mediaStatus);
        }
    }

    /** Publishes TBT primitives and the aggregate navigation object. */
    public boolean publishNavigation(NavigationInfo info) {
        if (info == null) return false;
        if (lastNavigation != info && !navigationRetryRunning) navigationRetryAttempt = 0;
        lastNavigation = info;
        if (info.maneuver == NavigationInfo.Maneuver.ARRIVED) {
            navigationRetryHandler.removeCallbacks(navigationRetry);
            navigationRetryScheduled = false;
            navigationRetryAttempt = 0;
        }
        start();
        synchronized (lock) {
            if (naviManager == null || naviApiClass == null) {
                lastNavigationDimPublished = false;
                navigationStatus = "ECARX DIM 导航不可用";
                if (info.maneuver != NavigationInfo.Maneuver.ARRIVED) {
                    scheduleNavigationRetry();
                }
                return false;
            }
            try {
                boolean firstFrame = !navigationStarted;
                int navigationState = navigationState(info);
                boolean complete = true;
                if (firstFrame) {
                    boolean started = invokeNaviRequired("notifyTurnByTurnStarted");
                    if (started) navigationStarted = true;
                    // The OEM flow announces START once, then reports a
                    // successful/active route on subsequent frames.
                    complete &= started;
                    complete &= invokeNaviRequired("notifyNavigationStatus", NAV_STATUS_START);
                }
                if (!firstFrame && navigationState != NAV_STATUS_SUCCEED) {
                    complete &= invokeNaviRequired("notifyNavigationStatus", navigationState);
                }
                if (info.details.dayNight() >= 0) invokeOptionalNavi("updateDayNightMode", info.details.dayNight());
                if (info.details.mute() >= 0) invokeOptionalNavi("updateMuteState", info.details.mute());
                invokeOptionalNavi("updateExtensionInfo", navigationExtras(info));
                complete &= invokeNaviRequired("updateDistanceToDestination",
                        toInt(info.routeRemainingMeters));
                complete &= invokeNaviRequired("updateDistanceToNextGuidancePoint",
                        toInt(info.distanceMeters));
                invokeOptionalNavi("UpdateDistanceToNextGuidancePoint",
                        String.valueOf(info.distanceMeters), 3);
                complete &= invokeNaviRequired("updateETA", info.remainingSeconds());
                if (driveDirection(info) >= 0) complete &= invokeNaviRequired("updateDriveDirection", driveDirection(info));
                publishNavigationDetails(info);
                complete &= invokeNaviRequired("updateNextGuidancePointName", info.nextRoadName);
                // Older ECARX widgets consume the Parcelable summary/status
                // pair, while newer ones consume EcarxNavigationInfo. Sending
                // both mirrors the OEM adaptapi wrapper.
                try {
                    // iAP2 turn guidance need not include destination coordinates.
                    // Do not send NaN or invent a location for the legacy summary.
                    if (Double.isFinite(info.latitude) && Double.isFinite(info.longitude)) {
                        complete &= invokeNaviRequired("updateNaviInfo", createBasicNaviInfo(info));
                    }
                    complete &= invokeNaviRequired("updateNaviStatus",
                            createBasicNaviStatus(info, navigationState));
                } catch (Throwable legacyNaviError) {
                    Log.w(TAG, "ECARX DIM 旧版导航摘要更新失败", legacyNaviError);
                    complete = false;
                }
                try {
                    Object navigationInfo = createNavigationInfo(info, navigationState);
                    complete &= invokeNaviRequired("updateNavigationInfo", navigationInfo);
                } catch (Throwable navigationInfoError) {
                    Log.w(TAG, "ECARX DIM 聚合导航对象创建失败", navigationInfoError);
                    complete = false;
                }
                // The integer id is the canonical Geely TBT value.  Several
                // firmware revisions expose the Bitmap overload as the only
                // renderer that actually draws the arrow, so send both.
                int geelyTurnId = turnId(info);
                complete &= invokeNaviRequired("updateTurnByTurnArrow", geelyTurnId);
                invokeOptionalNavi("updateTurnByTurnArrow", createTurnArrowBitmap(info));
                Log.d(TAG, "ECARX DIM TBT arrow turnId=" + geelyTurnId
                        + " bitmap=128x128 maneuver=" + info.maneuver);
                if (firstFrame && navigationState == NAV_STATUS_SUCCEED) {
                    complete &= invokeNaviRequired("notifyNavigationStatus", NAV_STATUS_SUCCEED);
                }
                if (info.maneuver == NavigationInfo.Maneuver.ARRIVED && navigationStarted) {
                    invokeNaviRequired("notifyTurnByTurnStopped");
                    navigationStarted = false;
                }
                navigationStatus = "ECARX DIM 导航已调用"
                        + (complete ? "" : "（部分字段重试中）")
                        + " · status=" + navigationState
                        + " · " + info.nextRoadName;
                status = "ECARX DIM · 导航=" + compact(navigationStatus)
                        + " · 音乐=" + compact(mediaStatus);
                lastNavigationDimPublished = complete;
                if (info.maneuver != NavigationInfo.Maneuver.ARRIVED) {
                    scheduleNavigationRetry();
                }
                return complete;
            } catch (Throwable error) {
                lastNavigationDimPublished = false;
                navigationStatus = "ECARX DIM 导航调用失败 · " + describe(error);
                status = "ECARX DIM · 导航=" + compact(navigationStatus)
                        + " · 音乐=" + compact(mediaStatus);
                logFailure(navigationStatus, error);
                if (info.maneuver != NavigationInfo.Maneuver.ARRIVED) {
                    scheduleNavigationRetry();
                }
                return false;
            }
        }
    }

    private boolean invokeNaviRequired(String name, Object... args) {
        try {
            invoke(naviManager, naviApiClass, name, args);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "ECARX DIM 导航字段失败 " + name + " · " + describe(error));
            return false;
        }
    }

    private void scheduleNavigationRetry() {
        if (navigationRetryScheduled || navigationRetryRunning) return;
        navigationRetryScheduled = true;
        navigationRetryHandler.postDelayed(navigationRetry, NAVIGATION_REFRESH_MS);
    }

    /**
     * Publishes the ECARX source, queue, playback object, and progress to the
     * cluster.  Some instrument builds render the title by resolving the
     * playback queue index, so updatePlaybackInfo alone is not sufficient.
     */
    public boolean publishMusic(MediaMetadata metadata, PlaybackState state) {
        return publishMusic(metadata, state, null);
    }

    public boolean publishMusic(MediaMetadata metadata, PlaybackState state,
                                List<MediaMetadata> playlist) {
        return publishMusicInternal(metadata, state, playlist);
    }

    /** Compatibility alias for older UI actions; the implementation is DIM-only. */
    public boolean publishMusicHttpOnly(MediaMetadata metadata,
                                        PlaybackState state,
                                        List<MediaMetadata> playlist) {
        return publishMusicInternal(metadata, state, playlist);
    }

    private boolean publishMusicInternal(MediaMetadata metadata, PlaybackState state,
                                         List<MediaMetadata> playlist) {
        return publishMusicInternal(metadata, state, playlist, currentMediaSourceType);
    }

    private boolean publishMusicInternal(MediaMetadata metadata, PlaybackState state,
                                         List<MediaMetadata> playlist, int sourceType) {
        if (metadata == null || state == null) return false;
        if (sourceType != MEDIA_SOURCE_NETEASE) sourceType = MEDIA_SOURCE_ONLINE;
        start();
        synchronized (lock) {
            if (mediaManager == null || mediaApiClass == null) return false;
            try {
                // Advertise both the generic online slot and the dedicated
                // NetEase slot. The current Geely widget consumes the generic
                // slot, while newer DIM clients identify this provider as 15.
                invoke(mediaManager, mediaApiClass, "updateMediaSourceTypeList",
                        new int[]{MEDIA_SOURCE_ONLINE, MEDIA_SOURCE_NETEASE});
                invoke(mediaManager, mediaApiClass, "updateCurrentSourceType", sourceType);
                currentMediaSourceType = sourceType;
                List<MediaMetadata> items = playlist == null || playlist.isEmpty()
                        ? java.util.Collections.singletonList(metadata) : playlist;
                lastMediaMetadata = metadata;
                lastPlaybackState = state;
                lastMediaPlaylist = new ArrayList<>(items);
                try {
                    invoke(mediaManager, mediaApiClass, "updatePlaylist",
                            sourceType, toEcarxPlaylist(items, sourceType));
                } catch (Throwable playlistError) {
                    // The first argument is the source type (not the active
                    // queue index). Keep the playback-info path alive on
                    // firmware without a writable playlist, while retaining
                    // evidence in logcat.
                    Log.w(TAG, "ECARX DIM 音乐播放列表更新失败", playlistError);
                }
                Object playbackInfo = createPlaybackInfo(metadata, state, sourceType);
                invoke(mediaManager, mediaApiClass, "updatePlaybackInfo", playbackInfo);
                invoke(mediaManager, mediaApiClass, "updateCurrentProgress",
                        toLong(Math.max(0, state.getPosition())));
                String title = value(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                String artist = value(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
                String mediaId = value(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
                String mediaPath = value(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI));
                Log.d(TAG, "音乐元数据已发送 title=" + title
                        + ", artist=" + artist
                        + ", uuid=" + mediaUuid(metadata) + ", sessionMediaId=" + mediaId
                        + ", artwork=" + artworkUri(metadata)
                        + ", sourceType=" + sourceType
                        + ", playbackStatus=" + (state.getState() == PlaybackState.STATE_PLAYING ? 1 : 0)
                        + ", duration=" + metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        + ", queueIndex=" + state.getActiveQueueItemId()
                        + ", playlistSize=" + items.size()
                        + ", mediaPath=" + mediaPath);
                mediaStatus = "ECARX DIM 音乐已调用 · " + title
                        + " · " + (state.getState() == PlaybackState.STATE_PLAYING
                        ? "playing" : "paused");
                status = "ECARX DIM · 导航=" + compact(navigationStatus)
                        + " · 音乐=" + compact(mediaStatus);
                return true;
            } catch (Throwable error) {
                mediaStatus = "ECARX DIM 音乐调用失败 · " + describe(error);
                status = "ECARX DIM · 导航=" + compact(navigationStatus)
                        + " · 音乐=" + compact(mediaStatus);
                logFailure(mediaStatus, error);
                return false;
            }
        }
    }

    /**
     * Replays a complete DIM media snapshot so a widget can resolve the new
     * artwork URI. DIM has no byte[] artwork setter; the Uri is carried by both
     * EcarxMedia and EcarxPlaybackInfo.
     */
    public boolean republishMusicArtwork(MediaMetadata metadata,
                                         PlaybackState state,
                                         List<MediaMetadata> playlist) {
        if (metadata == null || state == null) return false;
        Log.d(TAG, "ECARX DIM 重发封面 uri=" + artworkUri(metadata)
                + " title=" + value(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)));
        return publishMusicInternal(metadata, state, playlist);
    }

    public void clearNavigation() {
        synchronized (lock) {
            lastNavigation = null;
            navigationRetryHandler.removeCallbacks(navigationRetry);
            navigationRetryScheduled = false;
            navigationRetryRunning = false;
            navigationRetryAttempt = 0;
            lastNavigationDimPublished = false;
            if (naviManager == null || naviApiClass == null || !navigationStarted) {
                navigationStatus = "ECARX DIM 导航已清除";
                return;
            }
            try {
                invoke(naviManager, naviApiClass, "notifyNavigationStatus", NAV_STATUS_END);
                invoke(naviManager, naviApiClass, "notifyTurnByTurnStopped");
                navigationStatus = "ECARX DIM 导航已停止";
            } catch (Throwable error) {
                navigationStatus = "ECARX DIM 导航停止失败 · " + describe(error);
                logFailure(navigationStatus, error);
            } finally {
                navigationStarted = false;
            }
        }
    }

    /** Position-only iAP2 updates do not resend the playlist or album bitmap. */
    public void updateMusicPlayback(PlaybackState state) {
        synchronized (lock) {
            if (lastMediaMetadata == null || mediaManager == null || mediaApiClass == null) return;
            try {
                boolean changed = lastPlaybackState == null || lastPlaybackState.getState() != state.getState();
                lastPlaybackState = state;
                if (changed) invoke(mediaManager, mediaApiClass, "updatePlaybackInfo",
                        createPlaybackInfo(lastMediaMetadata, state, currentMediaSourceType));
                invoke(mediaManager, mediaApiClass, "updateCurrentProgress", Math.max(0L, state.getPosition()));
            } catch (Throwable error) {
                mediaStatus = "ECARX DIM playback update failed: " + describe(error);
            }
        }
    }

    public void stop() {
        navigationInteractions.close();
        clearNavigation();
        synchronized (lock) {
            if (mediaManager != null && mediaApiClass != null
                    && mediaInteractionCallback != null) {
                try {
                    invoke(mediaManager, mediaApiClass, "unsetMediaInteractionCallback",
                            mediaInteractionCallback);
                } catch (Throwable callbackError) {
                    Log.w(TAG, "ECARX DIM 音乐 callback 注销失败", callbackError);
                }
            }
            naviManager = null;
            mediaManager = null;
            naviApiClass = null;
            mediaApiClass = null;
            mediaInteractionCallback = null;
            mediaCallbackRegistered = false;
            lastMediaMetadata = null;
            lastPlaybackState = null;
            lastMediaPlaylist = null;
            currentMediaSourceType = MEDIA_SOURCE_ONLINE;
            lastCallbackRepublish = 0;
            callbackHandler.removeCallbacksAndMessages(null);
            navigationRetryHandler.removeCallbacks(navigationRetry);
            navigationRetryScheduled = false;
            navigationRetryRunning = false;
            lastNavigationDimPublished = false;
            navigationRetryAttempt = 0;
            lastNavigation = null;
            methodCache.clear();
            lastNaviInitAttempt = 0;
            lastMediaInitAttempt = 0;
            status = "ECARX DIM 已停止";
            navigationStatus = status;
            mediaStatus = status;
        }
    }

    private Object createManager(Class<?> apiClass) throws Exception {
        ClassLoader loader = apiClass.getClassLoader();
        Class<?> ecarxApi = Class.forName(API_CLASS, true, loader);
        Method creatorMethod = ecarxApi.getMethod("creator", Class.class);
        Object creator = creatorMethod.invoke(null, apiClass);
        if (creator == null) return null;
        Class<?> creatorInterface = Class.forName("ecarx.fw.api.ICreator", true, loader);
        Method create = creatorInterface.getMethod("create", Context.class);
        // The OEM adapter calls create() with mediawidget's own Context.  The
        // package Context used to load framework classes is not a substitute:
        // its package name can cause the framework to reject a third-party
        // provider or route it to the wrong vehicle profile.  Keep a vendor
        // Context only as a compatibility fallback for older firmware.
        InvocationTargetException firstError = null;
        try {
            Object manager = create.invoke(creator, context);
            if (manager != null) return manager;
        } catch (InvocationTargetException error) {
            firstError = error;
        }
        if (ecarxCarContext != null && ecarxCarContext != context) {
            try {
                Object manager = create.invoke(creator, ecarxCarContext);
                if (manager != null) return manager;
            } catch (InvocationTargetException fallbackError) {
                if (firstError != null) fallbackError.addSuppressed(firstError);
                throw fallbackError;
            }
        }
        if (firstError != null) throw firstError;
        return null;
    }

    /**
     * The OEM adapter registers this callback before publishing media.  The
     * cluster uses it to request a fresh snapshot after switching source or
     * recreating the media widget; a push-only client otherwise renders a
     * transient "unknown" item and then clears it.
     */
    private void registerMediaCallback() throws Exception {
        Class<?> callbackType = Class.forName(MEDIA_CLASS + "$EcarxMediaInteractionCallback",
                true, mediaApiClass.getClassLoader());
        mediaInteractionCallback = Proxy.newProxyInstance(
                callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("toString".equals(name)) return "ECARX DIM media callback";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                    if ("onUpdateMediaStatusRequest".equals(name)) {
                        int dataType = args != null && args.length > 0 && args[0] instanceof Number
                                ? ((Number) args[0]).intValue() : -1;
                        Log.d(TAG, "收到仪表媒体状态请求 dataType=" + dataType);
                        callbackHandler.post(() -> republishLastMusic(dataType,
                                currentMediaSourceType, false));
                    } else if ("onSourceSelected".equals(name)) {
                        int sourceType = args != null && args.length > 0 && args[0] instanceof Number
                                ? ((Number) args[0]).intValue() : -1;
                        Log.d(TAG, "仪表选择媒体源 sourceType=" + sourceType);
                        if (sourceType == MEDIA_SOURCE_ONLINE || sourceType == MEDIA_SOURCE_NETEASE) {
                            callbackHandler.post(() -> republishLastMusic(-1, sourceType, true));
                        }
                    } else if ("onMediaSelected".equals(name)
                            || "onMediaHighlighted".equals(name)) {
                        Log.d(TAG, "仪表媒体回调 " + name);
                    }
                    return defaultValue(method.getReturnType());
                });
        invoke(mediaManager, mediaApiClass, "setMediaInteractionCallback",
                mediaInteractionCallback);
        mediaCallbackRegistered = true;
    }

    private void republishLastMusic(int requestType, int sourceType, boolean force) {
        MediaMetadata metadata = lastMediaMetadata;
        PlaybackState state = lastPlaybackState;
        List<MediaMetadata> playlist = lastMediaPlaylist;
        if (metadata == null || state == null || mediaManager == null) {
            Log.d(TAG, "忽略仪表媒体状态请求 dataType=" + requestType + " · 尚无播放快照");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastCallbackRepublish < CALLBACK_REPUBLISH_MIN_INTERVAL_MS) {
            Log.d(TAG, "忽略重复仪表媒体状态请求 dataType=" + requestType);
            return;
        }
        lastCallbackRepublish = now;
        Log.d(TAG, "响应仪表媒体状态请求 dataType=" + requestType
                + " · title=" + value(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)));
        publishMusicInternal(metadata, state, playlist, sourceType);
    }

    private void publishNavigationDetails(NavigationInfo info) {
        String[][] mappings = {{"updateLaneInfo","lanes"},{"updateRoadCameraInfo","camera"},
                {"updateHighwayExitInfo","highwayExit"},{"updateServiceAreaInfo","serviceArea"}};
        for (String[] mapping : mappings) {
            for (Method method : naviApiClass.getMethods()) {
                if (!method.getName().equals(mapping[0]) || method.getParameterTypes().length != 1) continue;
                try {
                    Object payload = NavigationPayloads.create(method.getParameterTypes()[0],mapping[1],info.details);
                    method.invoke(naviManager,payload);
                } catch (Exception error) { Log.w(TAG,"扩展导航字段未被固件接受: " + mapping[0],error); }
                break;
            }
        }
    }

    private Object createNavigationInfo(final NavigationInfo info, final int statusCode)
            throws ClassNotFoundException {
        Class<?> type = Class.forName(NAVI_CLASS + "$EcarxNavigationInfo", true,
                naviApiClass.getClassLoader());
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new GetterHandler() {
                    @Override
                    Object value(String name, Class<?> returnType) {
                        if ("getDayNightMode".equals(name)) return info.details.dayNight();
                        if ("getDistanceToDestination".equals(name)) return (long) info.routeRemainingMeters;
                        if ("getDistanceToNextGuidancePoint".equals(name)) return (long) info.distanceMeters;
                        if ("getDrivingDirection".equals(name)) return driveDirection(info);
                        if ("getETA".equals(name)) return (long) info.remainingSeconds();
                        if ("getExtensionInfo".equals(name)) return navigationExtras(info);
                        if ("getHighwayExitInfo".equals(name)) return NavigationPayloads.create(returnType,"highwayExit",info.details);
                        if ("getLaneInfo".equals(name)) return NavigationPayloads.create(returnType,"lanes",info.details);
                        if ("getMuteState".equals(name)) return info.details.mute();
                        if ("getNavigationStatus".equals(name)) return statusCode;
                        if ("getNavigationTurnId".equals(name)) return turnId(info);
                        if ("getNavigationTurnSVG".equals(name)) return turnSvg(info);
                        if ("getNextGuidancePointName".equals(name)) return info.nextRoadName;
                        if ("getRoadCameraInfo".equals(name)) return NavigationPayloads.create(returnType,"camera",info.details);
                        if ("getServiceAreaInfo".equals(name)) return NavigationPayloads.create(returnType,"serviceArea",info.details);
                        return defaultValue(returnType);
                    }
                });
    }

    private Object createBasicNaviInfo(NavigationInfo info) throws Exception {
        Class<?> type = Class.forName(NAVI_CLASS.replace("EcarxNaviInteraction",
                "EcarxNaviInfo"), true, naviApiClass.getClassLoader());
        return type.getConstructor(String.class, double.class, double.class,
                        long.class, long.class)
                .newInstance(info.destination, info.latitude, info.longitude,
                        Math.max(0L, info.routeRemainingMeters),
                        Math.max(0L, info.remainingSeconds()));
    }

    private Object createBasicNaviStatus(NavigationInfo info, int statusCode) throws Exception {
        Class<?> type = Class.forName(NAVI_CLASS.replace("EcarxNaviInteraction",
                "EcarxNaviStatus"), true, naviApiClass.getClassLoader());
        return type.getConstructor(int.class, boolean.class)
                .newInstance(statusCode, info.rerouting);
    }

    private Object createPlaybackInfo(final MediaMetadata metadata,
                                      final PlaybackState state,
                                      final int sourceType)
            throws ClassNotFoundException {
        Class<?> type = Class.forName(MEDIA_CLASS + "$EcarxPlaybackInfo", true,
                mediaApiClass.getClassLoader());
        final long duration = Math.max(0,
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        final String title = value(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        final String artist = value(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        final String album = value(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        final String mediaId = value(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
        final String lyric = "";
        final Uri mediaPath = uri(metadata, MediaMetadata.METADATA_KEY_MEDIA_URI);
        Uri artwork = artworkUri(metadata);
        Log.d(TAG, "DIM playback artwork uri=" + artwork
                + " dimUuid=" + mediaUuid(metadata)
                + " scheme=" + (artwork == null ? "none" : artwork.getScheme())
                + " probe=" + "public JPEG; external access unverified");
        final Bitmap artworkBitmap = metadataBitmap(metadata);
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new GetterHandler() {
                    @Override
                    Object value(String name, Class<?> returnType) {
                        if ("getUUID".equals(name)) return mediaUuid(metadata);
                        if ("getTitle".equals(name)) return title;
                        if ("getArtist".equals(name)) return artist;
                        if ("getAlbum".equals(name)) return album;
                        if ("getRadioFrequency".equals(name)) return "";
                        if ("getRadioStationName".equals(name)) return "";
                        if ("getDuration".equals(name)) return duration;
                        if ("getPlayingItemPositionInQueue".equals(name)) {
                            return toInt(state.getActiveQueueItemId());
                        }
                        if ("getSourceType".equals(name)) return sourceType;
                        if ("getMediaPath".equals(name)) return mediaPath;
                        if ("getPlaybackStatus".equals(name)) {
                            return state.getState() == PlaybackState.STATE_PLAYING ? 1 : 0;
                        }
                        if ("getLyric".equals(name)) return null;
                        if ("getLyricContent".equals(name)) return lyric;
                        if ("getCurrentLyricSentence".equals(name)) return lyric;
                        if ("getPreviousArtwork".equals(name)) return artwork;
                        if ("getArtwork".equals(name)) return artwork;
                        if ("getNextArtwork".equals(name)) return artwork;
                        if (("getAlbumCover".equals(name) || "getAlbumArt".equals(name)
                                || "getArtworkBitmap".equals(name))
                                && Bitmap.class.isAssignableFrom(returnType)) {
                            return artworkBitmap;
                        }
                        if ("getLoopMode".equals(name)) return 0;
                        if ("getRadioMode".equals(name)) return 0;
                        if ("getFavoriteState".equals(name)) return 0;
                        return defaultValue(returnType);
                    }
                });
    }

    private List<Object> toEcarxPlaylist(List<MediaMetadata> playlist, int sourceType)
            throws ClassNotFoundException {
        Class<?> type = Class.forName(MEDIA_CLASS + "$EcarxMedia", true,
                mediaApiClass.getClassLoader());
        List<Object> result = new ArrayList<>(playlist.size());
        for (int index = 0; index < playlist.size(); index++) {
            result.add(createMedia(type, playlist.get(index), index, sourceType));
        }
        return result;
    }

    private Object createMedia(Class<?> type, MediaMetadata metadata, final int queueIndex,
                               final int sourceType) {
        final String uuid = value(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
        final String title = value(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        final String artist = value(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        final String album = value(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        final long duration = Math.max(0,
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        final Uri mediaPath = uri(metadata, MediaMetadata.METADATA_KEY_MEDIA_URI);
        final Uri artwork = artworkUri(metadata);
        Log.d(TAG, "DIM playlist artwork uri=" + artwork
                + " dimUuid=" + mediaUuid(metadata)
                + " scheme=" + (artwork == null ? "none" : artwork.getScheme())
                + " probe=" + "public JPEG; external access unverified");
        final Bitmap artworkBitmap = metadataBitmap(metadata);
        final String lyricContent = "";
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new GetterHandler() {
                    @Override
                    Object value(String name, Class<?> returnType) {
                        if ("getUUID".equals(name)) return mediaUuid(metadata);
                        if ("getTitle".equals(name)) return title;
                        if ("getArtist".equals(name)) return artist;
                        if ("getAlbum".equals(name)) return album;
                        if ("getRadioFrequency".equals(name)) return "";
                        if ("getRadioStationName".equals(name)) return "";
                        if ("getDuration".equals(name)) return duration;
                        if ("getPlayingItemPositionInQueue".equals(name)) return queueIndex;
                        if ("getSourceType".equals(name)) return sourceType;
                        if ("getMediaPath".equals(name)) return mediaPath;
                        if ("getLyric".equals(name)) return null;
                        if ("getLyricContent".equals(name)) return lyricContent;
                        if ("getArtwork".equals(name)) return artwork;
                        if (("getAlbumCover".equals(name) || "getAlbumArt".equals(name)
                                || "getArtworkBitmap".equals(name))
                                && Bitmap.class.isAssignableFrom(returnType)) {
                            return artworkBitmap;
                        }
                        if ("getFavoriteState".equals(name)) return 0;
                        return defaultValue(returnType);
                    }
                });
    }

    private static abstract class GetterHandler implements InvocationHandler {
        abstract Object value(String name, Class<?> returnType);

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) return "ECARX DIM data proxy";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            return value(name, method.getReturnType());
        }
    }

    private void invoke(Object receiver, Class<?> apiClass, String name, Object... args)
            throws ReflectiveOperationException {
        if (receiver == null || apiClass == null) throw new IllegalStateException("ECARX manager 未创建");
        String key = apiClass.getName() + ":" + name + "/" + args.length;
        Method method = methodCache.get(key);
        Object[] adapted = method == null ? null : adapt(method.getParameterTypes(), args);
        if (method == null || adapted == null) {
            for (Method candidate : apiClass.getMethods()) {
                if (!candidate.getName().equals(name)) continue;
                Object[] values = adapt(candidate.getParameterTypes(), args);
                if (values != null) {
                    method = candidate;
                    adapted = values;
                    methodCache.put(key, candidate);
                    if (name.equals("updateTurnByTurnArrow")) {
                        Log.d(TAG, "ECARX DIM 命中箭头方法 " + candidate.toGenericString()
                                + " arg=" + (args.length == 0 || args[0] == null
                                ? "null" : args[0].getClass().getName()));
                    }
                    break;
                }
            }
        }
        if (method == null || adapted == null) {
            throw new NoSuchMethodException(apiClass.getName() + "." + name + "/" + args.length);
        }
        try {
            method.invoke(receiver, adapted);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause == null ? error : cause);
        }
    }

    private void invokeOptionalNavi(String name, Object... args) {
        try {
            invoke(naviManager, naviApiClass, name, args);
        } catch (NoSuchMethodException missing) {
            Log.d(TAG, "ECARX DIM 当前固件未提供 " + name);
        } catch (Throwable error) {
            Log.d(TAG, "ECARX DIM 可选导航字段 " + name + " 发送失败 · "
                    + describe(error));
        }
    }

    private Object[] adapt(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return null;
        Object[] result = new Object[args.length];
        for (int i = 0; i < types.length; i++) {
            Object value = args[i];
            if (value == null) {
                if (types[i].isPrimitive()) return null;
                result[i] = null;
                continue;
            }
            Class<?> target = boxed(types[i]);
            if (target.isInstance(value)) {
                result[i] = value;
            } else if (value instanceof Number && Number.class.isAssignableFrom(target)) {
                result[i] = number((Number) value, target);
            } else if (value.getClass().isArray() && types[i].isArray()
                    && types[i].isAssignableFrom(value.getClass())) {
                result[i] = value;
            } else {
                return null;
            }
        }
        return result;
    }

    private static Bundle navigationExtras(NavigationInfo info) {
        Bundle extras = new Bundle();
        extras.putString("KEY_NAVI_INFO_DEST_NAME", info.destination);
        if (info.details.roadType() != null) extras.putString("KEY_NAVI_INFO_ROAD_TYPE", info.details.roadType());
        extras.putInt("distanceMeters", info.distanceMeters);
        extras.putBoolean("rerouting", info.rerouting);
        return extras;
    }

    private static int navigationState(NavigationInfo info) {
        if (info.maneuver == NavigationInfo.Maneuver.ARRIVED) return NAV_STATUS_END;
        return info.rerouting ? NAV_STATUS_REROUTING : NAV_STATUS_SUCCEED;
    }

    private static int turnId(NavigationInfo info) {
        if (info.sourceTurnIconId >= 0 && info.sourceTurnIconId <= 28) {
            return geelyTbtIconId(info.sourceTurnIconId, info.roundaboutExit);
        }
        switch (info.maneuver) {
            case LEFT: return 102;
            case RIGHT: return 103;
            case U_TURN: return 108;
            case ROUNDABOUT: return 111;
            case ARRIVED: return 115;
            case STRAIGHT:
            default: return 109;
        }
    }

    private static int driveDirection(NavigationInfo info) {
        // This field is cardinal heading (DIRECTION_*), not maneuver type.
        return info.details.direction();
    }

    /** Mirrors the OEM TbtIconMapping used by com.geely.naviService. */
    private static int geelyTbtIconId(int raw, int roundaboutExit) {
        if (raw == 12) return roundaboutExit > 0 && roundaboutExit <= 10
                ? roundaboutExit + 120 : 112;
        if (raw == 18) return roundaboutExit > 0 && roundaboutExit <= 10
                ? roundaboutExit + 130 : 118;
        if (raw <= 20) return raw + 100;
        switch (raw) {
            case 65: return 149;
            case 66: return 150;
            case 21: return 141;
            case 22: return 142;
            case 24: return 143;
            case 25: return 144;
            case 26: return 145;
            case 27: return 118;
            case 28: return 146;
            default: return -1;
        }
    }

    private static Bitmap createTurnArrowBitmap(NavigationInfo info) {
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        Path path = new Path();
        switch (info.maneuver) {
            case LEFT:
                path.moveTo(28, 58); path.lineTo(64, 22); path.lineTo(64, 43);
                path.cubicTo(92, 43, 101, 57, 101, 86);
                path.lineTo(80, 86); path.cubicTo(80, 70, 75, 64, 64, 64);
                path.lineTo(64, 80); path.close();
                break;
            case RIGHT:
                path.moveTo(100, 58); path.lineTo(64, 22); path.lineTo(64, 43);
                path.cubicTo(36, 43, 27, 57, 27, 86);
                path.lineTo(48, 86); path.cubicTo(48, 70, 53, 64, 64, 64);
                path.lineTo(64, 80); path.close();
                break;
            case U_TURN:
                path.moveTo(30, 70); path.cubicTo(30, 31, 98, 31, 98, 70);
                path.lineTo(77, 70); path.cubicTo(77, 54, 51, 54, 51, 70);
                path.lineTo(51, 82); path.lineTo(72, 82); path.lineTo(42, 108);
                path.lineTo(12, 82); path.lineTo(30, 82); path.close();
                break;
            case ROUNDABOUT:
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(14);
                canvas.drawCircle(64, 64, 31, paint);
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(92, 47); path.lineTo(111, 49); path.lineTo(99, 67); path.close();
                break;
            case ARRIVED:
                path.moveTo(64, 15); path.lineTo(99, 98); path.lineTo(64, 82);
                path.lineTo(29, 98); path.close();
                break;
            case STRAIGHT:
            default:
                path.moveTo(53, 20); path.lineTo(75, 20); path.lineTo(75, 73);
                path.lineTo(96, 73); path.lineTo(64, 110); path.lineTo(32, 73);
                path.lineTo(53, 73); path.close();
                break;
        }
        canvas.drawPath(path, paint);
        return bitmap;
    }

    private static String turnSvg(NavigationInfo info) {
        String path;
        switch (info.maneuver) {
            case LEFT: path = "M28 58 L64 22 L64 43 C92 43 101 57 101 86 L80 86 C80 70 75 64 64 64 L64 80 Z"; break;
            case RIGHT: path = "M100 58 L64 22 L64 43 C36 43 27 57 27 86 L48 86 C48 70 53 64 64 64 L64 80 Z"; break;
            case U_TURN: path = "M30 70 C30 31 98 31 98 70 L77 70 C77 54 51 54 51 70 L51 82 L72 82 L42 108 L12 82 L30 82 Z"; break;
            default: path = "M53 20 H75 V73 H96 L64 110 L32 73 H53 Z"; break;
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"128\" height=\"128\"><path fill=\"#ffffff\" d=\"" + path + "\"/></svg>";
    }

    private static int safeSeconds(int minutes) {
        long seconds = Math.max(0L, minutes) * 60L;
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    private static int toInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    private static long toLong(long value) {
        return Math.max(0L, value);
    }

    private static Uri uri(MediaMetadata metadata, String key) {
        String value = metadata.getString(key);
        if (value == null || value.isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static Uri artworkUri(MediaMetadata metadata) {
        String[] keys = {
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        };
        Uri nonLocal = null;
        for (String key : keys) {
            Uri value = uri(metadata, key);
            if (value == null) continue;
            String scheme = value.getScheme();
            if ("content".equalsIgnoreCase(scheme)
                    || "file".equalsIgnoreCase(scheme)
                    || "android.resource".equalsIgnoreCase(scheme)) {
                return value;
            }
            if (nonLocal == null && ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)
                    || "data".equalsIgnoreCase(scheme))) {
                nonLocal = value;
            }
        }
        return nonLocal;
    }

    private static Bitmap metadataBitmap(MediaMetadata metadata) {
        Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bitmap == null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bitmap == null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return bitmap;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Object number(Number value, Class<?> type) {
        if (type == Byte.class) return value.byteValue();
        if (type == Short.class) return value.shortValue();
        if (type == Integer.class) return value.intValue();
        if (type == Long.class) return value.longValue();
        if (type == Float.class) return value.floatValue();
        if (type == Double.class) return value.doubleValue();
        return value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private Class<?> loadEcarxClass(String name) throws ClassNotFoundException {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missingFromAppLoader) {
            try {
                if (ecarxCarContext == null) {
                    ecarxCarContext = context.createPackageContext(
                            "com.ecarx.car",
                            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                }
                return Class.forName(name, true, ecarxCarContext.getClassLoader());
            } catch (Exception ignored) {
                missingFromAppLoader.addSuppressed(ignored);
                throw missingFromAppLoader;
            }
        }
    }

    private static String compact(String value) {
        if (value == null) return "";
        return value.length() > 180 ? value.substring(0, 180) + "..." : value;
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        if (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static void logFailure(String message, Throwable error) {
        Log.w(TAG, message, error);
    }
}
