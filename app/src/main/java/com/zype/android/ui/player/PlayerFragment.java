package com.zype.android.ui.player;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Point;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;

import com.google.ads.interactivemedia.v3.api.AdDisplayContainer;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.google.ads.interactivemedia.v3.api.AdsLoader;
import com.google.ads.interactivemedia.v3.api.AdsManager;
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent;
import com.google.ads.interactivemedia.v3.api.AdsRequest;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.android.exoplayer.AspectRatioFrameLayout;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.audio.AudioCapabilitiesReceiver;
import com.google.android.exoplayer.audio.AudioTrack;
import com.google.android.exoplayer.drm.UnsupportedDrmException;
import com.google.android.exoplayer.extractor.mp4.Mp4Extractor;
import com.google.android.exoplayer.util.Util;
import com.zype.android.BuildConfig;
import com.zype.android.R;
import com.zype.android.ZypeApp;
import com.zype.android.ZypeSettings;
import com.zype.android.core.provider.DataHelper;
import com.zype.android.core.provider.helpers.VideoHelper;
import com.zype.android.core.settings.SettingsProvider;
import com.zype.android.receiver.PhoneCallReceiver;
import com.zype.android.receiver.RemoteControlReceiver;
import com.zype.android.ui.base.BaseFragment;
import com.zype.android.ui.base.BaseVideoActivity;
import com.zype.android.ui.chromecast.LivePlayerActivity;
import com.zype.android.ui.dialog.ErrorDialogFragment;
import com.zype.android.ui.video_details.VideoDetailActivity;
import com.zype.android.ui.video_details.fragments.video.MediaControlInterface;
import com.zype.android.ui.video_details.fragments.video.OnVideoAudioListener;
import com.zype.android.utils.BundleConstants;
import com.zype.android.utils.FileUtils;
import com.zype.android.utils.Logger;
import com.zype.android.utils.UiUtils;
import com.zype.android.webapi.WebApiManager;
import com.zype.android.webapi.model.video.Thumbnail;
import com.zype.android.webapi.model.video.VideoData;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Observable;
import java.util.Observer;


public class PlayerFragment extends BaseFragment implements
        CustomPlayer.Listener, AudioCapabilitiesReceiver.Listener, MediaControlInterface, Observer,
        AdEvent.AdEventListener, AdErrorEvent.AdErrorListener {

    public static final int TYPE_AUDIO_LOCAL = 1;
    public static final int TYPE_AUDIO_WEB = 2;
    public static final int TYPE_VIDEO_LOCAL = 3;
    public static final int TYPE_VIDEO_WEB = 4;
    public static final int TYPE_AUDIO_LIVE = 5;
    public static final int TYPE_VIDEO_LIVE = 6;

    public static final String CONTENT_TYPE_TYPE = "content_type";
    public static final String CONTENT_URL = "content_url";
    public static final String CONTENT_ID_EXTRA = "content_id";
    public static final String PARAMETERS_ADD_TAG = "AddTag";
    public static final String PARAMETERS_ON_AIR = "OnAir";

    private static final CookieManager defaultCookieManager;
    public static final int MEDIA_STOP_CODE = 115756;
    public static String MEDIA_STOP = "MEDIA_STOP";

    static {
        defaultCookieManager = new CookieManager();
        defaultCookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private MediaController mediaController;
    private AspectRatioFrameLayout videoFrame;
    private CustomPlayer player;
    private SurfaceView surfaceView;

    private boolean playerNeedsPrepare;

    private String contentUri;
    private int contentType;
    private List<Thumbnail> mThumbnailList;
    private String fileId;
    private String adTag;
    private boolean onAir;

    private OnVideoAudioListener mListener;
    private AudioCapabilitiesReceiver audioCapabilitiesReceiver;

    private ImageView thumbnailView;
    private View mainView;
    private boolean isNeedToSeekToLatestListenPosition = true;
    private BroadcastReceiver callReceiver;
    private AudioManager mAudioManager;
    private ComponentName mRemoteControlResponder;
    private boolean isReceiversRegistered = false;
    private boolean deleteFileBeforeExit = false;

    //
    // IMA SDK
    //
    // Factory class for creating SDK objects.
    private ImaSdkFactory sdkFactory;
    // The AdsLoader instance exposes the requestAds method.
    private AdsLoader adsLoader;
    // AdsManager exposes methods to control ad playback and listen to ad events.
    private AdsManager adsManager;
    // Whether an ad is displayed.
    private boolean isAdDisplayed;

    // Limiting live stream
    private Handler handlerTimer;
    private Runnable runnableTimer;
    private Calendar liveStreamTimeStart;

    public static PlayerFragment newInstance(int mediaType, String filePath, String fileId) {
        PlayerFragment fragment = new PlayerFragment();
        Bundle args = new Bundle();
        args.putInt(CONTENT_TYPE_TYPE, mediaType);
        args.putString(CONTENT_URL, filePath);
        args.putString(CONTENT_ID_EXTRA, fileId);
        fragment.setArguments(args);
        return fragment;
    }

    public static PlayerFragment newInstance(int mediaType, String filePath, String adTag, boolean onAir, String fileId) {
        PlayerFragment fragment = new PlayerFragment();
        Bundle args = new Bundle();
        args.putInt(CONTENT_TYPE_TYPE, mediaType);
        args.putString(CONTENT_URL, filePath);
        args.putString(PARAMETERS_ADD_TAG, adTag);
        args.putBoolean(PARAMETERS_ON_AIR, onAir);
        args.putString(CONTENT_ID_EXTRA, fileId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            contentUri = getArguments().getString(CONTENT_URL);
            Logger.i("url: " + contentUri);
            contentType = getArguments().getInt(CONTENT_TYPE_TYPE, BaseVideoActivity.TYPE_UNKNOWN);
            fileId = getArguments().getString(CONTENT_ID_EXTRA);
            if (!TextUtils.isEmpty(fileId)) {
//                fileId = getArguments().getString(CONTENT_ID_EXTRA);
                mThumbnailList = DataHelper.getThumbnailList(getActivity().getContentResolver(), fileId);
            }
            adTag = getArguments().getString(PARAMETERS_ADD_TAG);
            onAir = getArguments().getBoolean(PARAMETERS_ON_AIR);
        }
//        context = getContext();
        isNeedToSeekToLatestListenPosition = true;
        callReceiver = new CallReceiver();
        handlerTimer = new Handler();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        mainView = inflater.inflate(R.layout.fragment_custom_player, container, false);
        mainView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(final View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    toggleControlsVisibility();
                } else if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;
            }
        });
        mainView.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                    return false;
                } else {
                    return mediaController.dispatchKeyEvent(event);
                }
            }
        });

        videoFrame = (AspectRatioFrameLayout) mainView.findViewById(R.id.video_frame);
        surfaceView = (SurfaceView) mainView.findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(surfaceCallback);
        thumbnailView = (ImageView) mainView.findViewById(R.id.thumbnailView);
        if (contentType == TYPE_AUDIO_WEB || contentType == TYPE_AUDIO_LOCAL) {
            thumbnailView.setVisibility(View.VISIBLE);
        } else if (contentType == TYPE_AUDIO_LIVE) {
            thumbnailView.setVisibility(View.VISIBLE);
            UiUtils.loadImage(getActivity().getApplicationContext(), SettingsProvider.getInstance().getOnAirPictureUrl(), thumbnailView);
        } else {
            thumbnailView.setVisibility(View.GONE);
        }

        CookieHandler currentHandler = CookieHandler.getDefault();
        if (currentHandler != defaultCookieManager) {
            CookieHandler.setDefault(defaultCookieManager);
        }
        audioCapabilitiesReceiverRegister();
        return mainView;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        //
        // IMA SDK
        //
        // Create an AdsLoader.
        sdkFactory = ImaSdkFactory.getInstance();
        adsLoader = sdkFactory.createAdsLoader(this.getActivity());
        // Add listeners for when ads are loaded and for errors.
        adsLoader.addAdErrorListener(this);
        adsLoader.addAdsLoadedListener(new AdsLoader.AdsLoadedListener() {
            @Override
            public void onAdsManagerLoaded(AdsManagerLoadedEvent adsManagerLoadedEvent) {
                // Ads were successfully loaded, so get the AdsManager instance. AdsManager has
                // events for ad playback and errors.
                adsManager = adsManagerLoadedEvent.getAdsManager();
                // Attach event and error event listeners.
                adsManager.addAdErrorListener(PlayerFragment.this);
                adsManager.addAdEventListener(PlayerFragment.this);
                adsManager.init();
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        onScreenOrientationChanged();
    }

    private void onScreenOrientationChanged() {
        mListener.onFullscreenChanged();
        hideControls();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        Logger.d("onAttach()");
        try {
            mListener = (OnVideoAudioListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString()
                    + " must implement OnVideoAudioListener");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (BuildConfig.DEBUG) {
            Logger.d("onStart()");
        }
        hideNotification();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (BuildConfig.DEBUG) {
            Logger.d("onResume()");
        }
        mListener.onFullscreenChanged();
        registerReceivers();
        if (player == null) {
            preparePlayer(true);
        }
        else {
            if (ZypeSettings.BACKGROUND_PLAYBACK_ENABLED) {
                player.setBackgrounded(false);
            }
            player.getPlayerControl().start();
            player.setSurface(surfaceView.getHolder().getSurface());
            if (onAir && (!SettingsProvider.getInstance().isLoggedIn() || SettingsProvider.getInstance().getSubscriptionCount() <= 0)) {
                startTimer();
            }
        }
        if (mThumbnailList != null) {
            if (mThumbnailList.size() > 0) {
                Display display = getActivity().getWindowManager().getDefaultDisplay();
                Point size = new Point();
                display.getSize(size);
                int width = size.x;
                int position = getNearestPosition(width, mThumbnailList);
                UiUtils.loadImage(getActivity().getApplicationContext(), mThumbnailList.get(position).getUrl(), thumbnailView);
            } else {
                UiUtils.loadImage(getActivity().getApplicationContext(), SettingsProvider.getInstance().getOnAirPictureUrl(), thumbnailView);
            }
        }
        // IMA SDK
        if (adsManager != null && isAdDisplayed) {
            adsManager.resume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (BuildConfig.DEBUG) {
            Logger.d("onPause()");
        }
        if (player != null) {
            if (ZypeSettings.BACKGROUND_PLAYBACK_ENABLED) {
                player.setBackgrounded(true);
            }
            stopTimer();
        }
        // IMA SDK
        if (adsManager != null && isAdDisplayed) {
            adsManager.pause();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (BuildConfig.DEBUG) {
            Logger.d("onStop()");
        }
        if (ZypeSettings.BACKGROUND_PLAYBACK_ENABLED) {
            if (contentType == TYPE_AUDIO_LIVE || contentType == TYPE_VIDEO_LIVE) {
                showNotification(true, contentType);
            } else {
                showNotification(false, contentType);
            }
        }
        else {
            isNeedToSeekToLatestListenPosition= true;
            if (player != null) {
                player.removeListener(this);
            }
            stop();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (BuildConfig.DEBUG) {
            Logger.d("onDestroy()");
        }
        hideNotification();
        audioCapabilitiesReceiverUnregister();
        releasePlayer();
        if (callReceiver != null) {
            try {
                getActivity().unregisterReceiver(callReceiver);
            } catch (IllegalArgumentException e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            }
        }
        if (mAudioManager != null)
            mAudioManager.unregisterMediaButtonEventReceiver(
                    mRemoteControlResponder);
        RemoteControlReceiver.getObservable().deleteObserver(this);
        isReceiversRegistered = false;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (BuildConfig.DEBUG) {
            Logger.d("onDetach()");
        }
        audioCapabilitiesReceiverUnregister();
        releasePlayer();
        mListener = null;
    }

    private void registerReceivers() {
        if (!isReceiversRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            filter.addAction("android.intent.action.NEW_OUTGOING_CALL");
            getActivity().registerReceiver(callReceiver, filter);

            if (audioCapabilitiesReceiver == null) {
                audioCapabilitiesReceiverRegister();
            }
            if (mAudioManager == null || mRemoteControlResponder == null) {
                mAudioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
                mRemoteControlResponder = new ComponentName(getActivity().getPackageName(),
                        RemoteControlReceiver.class.getName());
            }
            mAudioManager.registerMediaButtonEventReceiver(
                    mRemoteControlResponder);
            RemoteControlReceiver.getObservable().addObserver(this);
            isReceiversRegistered = true;
        }
    }

    private void audioCapabilitiesReceiverRegister() {
        audioCapabilitiesReceiver = new AudioCapabilitiesReceiver(getContext(), this);
        audioCapabilitiesReceiver.register();
    }

    private void audioCapabilitiesReceiverUnregister() {
        if (audioCapabilitiesReceiver != null) {
            audioCapabilitiesReceiver.unregister();
            audioCapabilitiesReceiver = null;
        }
    }

    private int getNearestPosition(int width, List<Thumbnail> thumbnailList) {
        int position = -1;
        if (thumbnailList != null) {
            for (int i = 0; i < thumbnailList.size(); i++)
                if (Math.abs(width - position) > Math.abs(thumbnailList.get(i).getWidth() - width)) {
                    position = i;
                }
        }
        return position;
    }

    @Override
    protected String getFragmentName() {
        return "Player";
    }

    @Override
    public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
        if (player == null) {
            return;
        }
        boolean backgrounded = player.getBackgrounded();
        boolean playWhenReady = player.getPlayWhenReady();
        releasePlayer();
        preparePlayer(playWhenReady);
        player.setBackgrounded(backgrounded);
    }

    private CustomPlayer.RendererBuilder getRendererBuilder() {
        String userAgent = Util.getUserAgent(getContext(), WebApiManager.CUSTOM_HEADER_VALUE);
        switch (contentType) {
            case TYPE_VIDEO_WEB:
            case TYPE_VIDEO_LIVE:
            case TYPE_AUDIO_WEB:
            case TYPE_AUDIO_LIVE:
                return new HlsRendererBuilder(getContext(), userAgent, contentUri);
            case TYPE_VIDEO_LOCAL:
            case TYPE_AUDIO_LOCAL:
                return new ExtractorRendererBuilder(getContext(), userAgent, Uri.parse(contentUri), new Mp4Extractor());
            default:
                throw new IllegalStateException("Unsupported type: " + contentType);
        }
    }

    private void preparePlayer(boolean playWhenReady) {
        if (player == null) {
            player = new CustomPlayer(getActivity().getApplicationContext(), getRendererBuilder());
            player.addListener(this);
            playerNeedsPrepare = true;
            mediaController = new MediaController(getContext());
            mediaController.setAnchorView(mainView);
            mediaController.setMediaPlayer(player.getPlayerControl());
            mediaController.setEnabled(true);

            player.setInternalErrorListener(new CustomPlayer.InternalErrorListener() {
                @Override
                public void onRendererInitializationError(Exception e) {
                    Logger.e("onRendererInitializationError", e);
                    if (!WebApiManager.isHaveActiveNetworkConnection(getActivity())) {
                        UiUtils.showErrorSnackbar(getView(), "Video is not available right now. " + getActivity().getString(R.string.connection_error));
                    } else {
                        UiUtils.showErrorSnackbar(getView(), "onRendererInitializationError");
                    }
                    if (player.getPlaybackState() != ExoPlayer.STATE_ENDED) {
                        mListener.saveCurrentTimeStamp(player.getCurrentPosition());
                    }
                    player.getPlayerControl().pause();
                }

                @Override
                public void onAudioTrackInitializationError(AudioTrack.InitializationException e) {
                    Logger.e("onAudioTrackInitializationError", e);
                    Toast.makeText(getContext(), "onAudioTrackInitializationError", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onAudioTrackWriteError(AudioTrack.WriteException e) {
                    Logger.e("onAudioTrackWriteError", e);
                    Toast.makeText(getContext(), "onAudioTrackWriteError", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onDecoderInitializationError(MediaCodecTrackRenderer.DecoderInitializationException e) {
                    Logger.e("onDecoderInitializationError", e);
                    Toast.makeText(getContext(), "onDecoderInitializationError", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onCryptoError(MediaCodec.CryptoException e) {
                    Logger.e("onCryptoError", e);
                    Toast.makeText(getContext(), "onCryptoError", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onLoadError(int sourceId, IOException e) {
                    Logger.e("onLoadError", e);
                    if (!WebApiManager.isHaveActiveNetworkConnection(getActivity())) {
                        UiUtils.showErrorSnackbar(getView(), "Video is not available right now. " + getActivity().getString(R.string.connection_error));
                    } else {
                        UiUtils.showErrorSnackbar(getView(), "Video is not available right now");
                    }
                    releasePlayer();
                }

                @Override
                public void onDrmSessionManagerError(Exception e) {
                    Logger.e("onDrmSessionManagerError", e);
                    Toast.makeText(getContext(), "onDrmSessionManagerError", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (playerNeedsPrepare) {
            player.prepare();
            playerNeedsPrepare = false;
        }
        if (surfaceView == null) {
            surfaceView = (SurfaceView) mainView.findViewById(R.id.surface_view);
        }
        player.setSurface(surfaceView.getHolder().getSurface());
        player.setPlayWhenReady(playWhenReady);

        showAd();
    }

    private void releasePlayer() {
        if (player != null) {
            mediaController.hide();
            if (player.getPlaybackState() != ExoPlayer.STATE_ENDED) {
                mListener.saveCurrentTimeStamp(player.getCurrentPosition());
            }
//            player.getPlayerControl().pause();
            player.release();
            player = null;
            videoFrame = null;
            surfaceView.getHolder().removeCallback(surfaceCallback);
            surfaceView = null;
            System.gc();
        }
    }

    @Override
    public void onStateChanged(boolean playWhenReady, int playbackState) {
        switch (playbackState) {
            case ExoPlayer.STATE_ENDED:
                showControls();
                if (contentType == TYPE_VIDEO_LOCAL || contentType == TYPE_VIDEO_WEB) {
                    mListener.videoFinished();
                } else if (contentType == TYPE_AUDIO_LOCAL || contentType == TYPE_AUDIO_WEB) {
                    mListener.audioFinished();
                } else if (contentType == TYPE_AUDIO_LIVE || contentType == TYPE_VIDEO_LIVE) {
                    //IGNORE
                    Logger.d("ExoPlayer.STATE_READY start live");
                } else {
                    Logger.e("ExoPlayer.STATE_ENDED unknown type " + contentType);
                }
                if (SettingsProvider.getInstance().isUserPreferenceAutoRemoveWatchedContentSet()) {
                    deleteFileBeforeExit = true;
                }
                break;
            case ExoPlayer.STATE_READY:
                if (isNeedToSeekToLatestListenPosition) {
                    long playerPosition = 0;
                    if (contentType != TYPE_AUDIO_LIVE && contentType != TYPE_VIDEO_LIVE) {
                        playerPosition = DataHelper.getPlayTime(getActivity().getContentResolver(), fileId);
                    }
                    player.seekTo(playerPosition);
                    isNeedToSeekToLatestListenPosition = false;
                }
                if (contentType == TYPE_VIDEO_LOCAL || contentType == TYPE_VIDEO_WEB) {
                    Logger.d(String.format("ExoPlayer.STATE_READY: playWhenReady=%1$s", playWhenReady));
                    // Count play time of the live stream if user is not logged in and is not subscribed
                    if (onAir && (!SettingsProvider.getInstance().isLoggedIn() || SettingsProvider.getInstance().getSubscriptionCount() <= 0)) {
                        if (playWhenReady) {
                            startTimer();
                        } else {
                            stopTimer();
                        }
                    }
                    mListener.videoStarted();
                } else if (contentType == TYPE_AUDIO_LOCAL || contentType == TYPE_AUDIO_WEB) {
                    mListener.audioStarted();
                } else if (contentType == TYPE_AUDIO_LIVE || contentType == TYPE_VIDEO_LIVE) {
                    //IGNORE
                    Logger.d("ExoPlayer.STATE_READY ready live");
                } else {
                    Logger.e("ExoPlayer.STATE_READY unknown type " + contentType);
                }
                showControls();
                break;
            default:
                Logger.e("ExoPlayer.STATE_READY status:" + playbackState);
                break;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (deleteFileBeforeExit) {
            Logger.d("ExoPlayer.STATE_ENDED remove download content");
            if (contentType == TYPE_VIDEO_LOCAL) {
                FileUtils.deleteVideoFile(fileId, getActivity());
                DataHelper.setAudioDeleted(getActivity().getContentResolver(), fileId);
            } else if (contentType == TYPE_AUDIO_LOCAL) {
                FileUtils.deleteAudioFile(fileId, getActivity());
                DataHelper.setAudioDeleted(getActivity().getContentResolver(), fileId);
            }
        }
    }

    @Override
    public void onError(Exception e) {
        Logger.e("onError", e);
        if (e instanceof UnsupportedDrmException) {
            // Special case DRM failures.
            UnsupportedDrmException unsupportedDrmException = (UnsupportedDrmException) e;
            int stringId = Util.SDK_INT < 18 ? R.string.drm_error_not_supported
                    : unsupportedDrmException.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME
                    ? R.string.drm_error_unsupported_scheme : R.string.drm_error_unknown;
            Toast.makeText(getContext(), stringId, Toast.LENGTH_LONG).show();
        }
        playerNeedsPrepare = true;
//        showControls();
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthAspectRatio) {
        if (videoFrame != null) {
//            Toast.makeText(getContext(), "Set video size: " + width + "x" + format.height +" Codec:"+format.codecs, Toast.LENGTH_SHORT).show();
            videoFrame.setAspectRatio(
                    height == 0 ? 1 : (width * pixelWidthAspectRatio) / height);
        }
    }

    // User controls

    private void toggleControlsVisibility() {
        if (mediaController != null) {
            if (mediaController.isShowing()) {
                hideControls();
            } else {
                showControls();
            }
        }
    }

    private void hideControls() {
        if (contentType == TYPE_VIDEO_LOCAL || contentType == TYPE_VIDEO_WEB || contentType == TYPE_VIDEO_LIVE) {
            mediaController.hide();
        } else {
            if (mediaController != null) {
                mediaController.post(new Runnable() {
                    @Override
                    public void run() {
                        mediaController.show(0);
                    }
                });
            }
        }
    }

    private void showControls() {
        if (BuildConfig.DEBUG) {
            Logger.d("showControls(): mListener=" + mListener.toString());
        }
        if (contentType == TYPE_VIDEO_LOCAL || contentType == TYPE_VIDEO_WEB || contentType == TYPE_VIDEO_LIVE) {
            if (mediaController != null) {
                mediaController.show(5000);
            }
        } else {
            if (mediaController != null) {
                mediaController.show(0);
            }
        }
    }

    SurfaceHolder.Callback surfaceCallback = new Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            if (player != null) {
                player.setSurface(holder.getSurface());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (player != null) {
                player.blockingClearSurface();
            }
        }
    };

    @Override
    public void seekToMillis(int ms) {
        if (player != null) {
            player.seekTo(ms);
        }
    }

    @Override
    public int getCurrentTimeStamp() {
        if (player != null) {
            return (int) player.getCurrentPosition();
        } else {
            return -1;
        }
    }

    @Override
    public void play() {
        if (player != null) {
            player.getPlayerControl().start();
        }
    }

    @Override
    public void stop() {
        Logger.d("fragment stop");
        if (player != null) {
            player.getPlayerControl().pause();
            releasePlayer();
        }
    }

    @Override
    public void update(Observable observable, Object data) {
        Logger.d("fragment remote action received code=" + data);
        int keycode = (int) data;
        switch (keycode) {
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null) {
                    if (player.getPlayerControl().isPlaying()) {
                        player.getPlayerControl().pause();
                    } else {
                        player.getPlayerControl().start();
                    }
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (player != null) {
                    if (player.getPlayerControl().isPlaying()) {
                        player.getPlayerControl().pause();
                    } else {
                        player.getPlayerControl().start();
                    }
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player != null) {
                    if (player.getPlayerControl().isPlaying()) {
                        player.getPlayerControl().pause();
                    } else {
                        player.getPlayerControl().start();
                    }
                }
                break;
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (player != null) {
                    if (player.getPlayerControl().isPlaying()) {
                        player.getPlayerControl().pause();
                    } else {
                        player.getPlayerControl().start();
                    }
                }
                break;
            case MEDIA_STOP_CODE: {
                if (player != null) {
                    if (player.getPlayerControl().isPlaying()) {
                        player.getPlayerControl().pause();
                    }
                    Logger.d("MEDIA_STOP_CODE");
                    hideNotification();
                }
            }
            break;
            default:
                break;
        }
    }

    class CallReceiver extends PhoneCallReceiver {

        @Override
        protected void onIncomingCallStarted(Context ctx, String number, Date start) {
            Logger.d("PlayerFragment call onIncomingCallStarted");
            if (player != null)
                player.getPlayerControl().pause();
        }

        @Override
        protected void onOutgoingCallStarted(Context ctx, String number, Date start) {
            Logger.d("PlayerFragment call onOutgoingCallStarted");
            if (player != null)
                player.getPlayerControl().pause();
        }

        @Override
        protected void onIncomingCallEnded(Context ctx, String number, Date start, Date end) {
            Logger.d("PlayerFragment call onIncomingCallEnded");
            if (player != null)
                player.getPlayerControl().start();
        }

        @Override
        protected void onOutgoingCallEnded(Context ctx, String number, Date start, Date end) {
            Logger.d("PlayerFragment call onOutgoingCallEnded");
            if (player != null)
                player.getPlayerControl().start();
        }

        @Override
        protected void onMissedCall(Context ctx, String number, Date start) {
            Logger.d("PlayerFragment call onMissedCall");
            if (player != null)
                player.getPlayerControl().start();
        }
    }

    public void showNotification(boolean isLive, int type) {

        VideoData video = VideoHelper.getVideo(getActivity().getContentResolver(), fileId);
        String title = "";
        if (video != null) {
            title = video.getTitle();
        } else {
            title = "Live";
        }
        Intent notificationIntent;
        Bundle bundle = new Bundle();
        if (isLive) {
            notificationIntent = new Intent(getActivity(), LivePlayerActivity.class);
            bundle.putInt(BundleConstants.VIDEO_TYPE, type);
            title = "Live";
        } else {
            notificationIntent = new Intent(getActivity(), VideoDetailActivity.class);
        }
        notificationIntent.putExtras(bundle);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
        PendingIntent intent = PendingIntent.getActivity(getActivity(), 0, notificationIntent, 0);

        Notification.Builder builder = new Notification.Builder(getActivity());

        builder.setContentIntent(intent)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getActivity().getString(R.string.app_name))
                .setContentIntent(intent)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentText(title)
                .setAutoCancel(true)
                .setOngoing(true)
                .setWhen(0)
                .setOngoing(true)
                .setContentTitle(title);

        Intent stopIntent = new Intent();
        stopIntent.setAction(MEDIA_STOP);
        PendingIntent pendingIntentStop = PendingIntent.getBroadcast(getActivity(), 12345, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification not = builder
                .setPriority(Notification.PRIORITY_MAX)
                .addAction(R.drawable.ic_stop_black_24px, "Stop", pendingIntentStop)
                .setWhen(0)
                .build();
        NotificationManager notificationManager =
                (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(ZypeApp.NOTIFICATION_ID, not);

    }

    public void hideNotification() {
        Logger.d("hideNotification");
        NotificationManager mNotificationManager = (NotificationManager) getActivity().getSystemService(getActivity().NOTIFICATION_SERVICE);
        mNotificationManager.cancel(ZypeApp.NOTIFICATION_ID);
    }

    //
    // IMA SDK
    //
    private void requestAds(String adTagUrl) {
        AdDisplayContainer adDisplayContainer = sdkFactory.createAdDisplayContainer();
        adDisplayContainer.setAdContainer(videoFrame);

        // Create the ads request.
        AdsRequest request = sdkFactory.createAdsRequest();
        request.setAdTagUrl(adTagUrl);
        request.setAdDisplayContainer(adDisplayContainer);
        request.setContentProgressProvider(new ContentProgressProvider() {
            @Override
            public VideoProgressUpdate getContentProgress() {
                if (isAdDisplayed || player == null || player.getDuration() <= 0) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                return new VideoProgressUpdate(player.getCurrentPosition(), player.getDuration());
            }
        });

        // Request the ad. After the ad is loaded, onAdsManagerLoaded() will be called.
        adsLoader.requestAds(request);
    }

    @Override
    public void onAdEvent(AdEvent adEvent) {
        Logger.i("Ad event: " + adEvent.getType());

        // These are the suggested event types to handle. For full list of all ad event
        // types, see the documentation for AdEvent.AdEventType.
        switch (adEvent.getType()) {
            case LOADED:
                // AdEventType.LOADED will be fired when ads are ready to be played.
                // AdsManager.start() begins ad playback. This method is ignored for VMAP or
                // ad rules playlists, as the SDK will automatically start executing the
                // playlist.
                adsManager.start();
                break;
            case CONTENT_PAUSE_REQUESTED:
                // AdEventType.CONTENT_PAUSE_REQUESTED is fired immediately before a video
                // ad is played.
                isAdDisplayed = true;
                player.getPlayerControl().pause();
                break;
            case CONTENT_RESUME_REQUESTED:
                // AdEventType.CONTENT_RESUME_REQUESTED is fired when the ad is completed
                // and you should start playing your content.
                isAdDisplayed = false;
                player.getPlayerControl().start();
                break;
            case ALL_ADS_COMPLETED:
                if (adsManager != null) {
                    adsManager.destroy();
                    adsManager = null;
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
        Logger.e("Ad error: " + adErrorEvent.getError().getMessage());
        player.getPlayerControl().start();
    }

    private void showAd() {
        if (SettingsProvider.getInstance().getSubscriptionCount() <= 0 || BuildConfig.DEBUG) {
            // IMA SDK test tag
//        requestAds("https://pubads.g.doubleclick.net/gampad/ads?sz=640x480&iu=/124319096/external/single_ad_samples&ciu_szs=300x250&impl=s&gdfp_req=1&env=vp&output=vast&unviewed_position_start=1&cust_params=deployment%3Ddevsite%26sample_ct%3Dlinear&correlator=");
            // Test VAST 3
//            requestAds("https://s3.amazonaws.com/demo.jwplayer.com/advertising/assets/vast3_jw_ads.xml");
            // Test VAST 2
//        requestAds("http://loopme.me/api/vast/ads?appId=e18c19fa43&vast=2&campid=6029");
            Logger.d("adTag=" + adTag);
            requestAds(adTag);
        }
    }

    //
    // Limiting live stream
    //
    private void startTimer() {
        if (handlerTimer != null) {
            runnableTimer = new Runnable() {
                @Override
                public void run() {
                    stopTimer();
                    if (isLiveStreamLimitHit()) {
                        Logger.d("startTimer(): Live stream limit has been hit");
                        player.getPlayerControl().pause();
                        ErrorDialogFragment dialog = ErrorDialogFragment.newInstance(SettingsProvider.getInstance().getLiveStreamMessage(), null, null);
                        dialog.show(getActivity().getSupportFragmentManager(), ErrorDialogFragment.TAG);
                    }
                    else {
                        handlerTimer.postDelayed(runnableTimer, 1000);
                    }
                }
            };
            liveStreamTimeStart = Calendar.getInstance();
            handlerTimer.postDelayed(runnableTimer, 1000);
        }
    }

    private void stopTimer() {
        if (handlerTimer != null && runnableTimer != null) {
            handlerTimer.removeCallbacks(runnableTimer);
        }
        if (liveStreamTimeStart != null) {
            addLiveStreamPlayTime();
        }
    }

    private void addLiveStreamPlayTime() {
        Calendar currentTime = Calendar.getInstance();
        int liveStreamTime = SettingsProvider.getInstance().getLiveStreamTime();
        liveStreamTime += (int) (currentTime.getTimeInMillis() - liveStreamTimeStart.getTimeInMillis()) / 1000;
        SettingsProvider.getInstance().saveLiveStreamTime(liveStreamTime);
        liveStreamTimeStart.setTime(currentTime.getTime());
        Logger.d(String.format("addLiveStreamPlayTime(): liveStreamTime=%1$s", liveStreamTime));
    }

    private boolean isLiveStreamLimitHit() {
        return SettingsProvider.getInstance().getLiveStreamTime() >= SettingsProvider.getInstance().getLiveStreamLimit();
    }
}
