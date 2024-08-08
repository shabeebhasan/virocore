//
//  Copyright (c) 2017-present, ViroMedia, Inc.
//  All rights reserved.
//
//  Permission is hereby granted, free of charge, to any person obtaining
//  a copy of this software and associated documentation files (the
//  "Software"), to deal in the Software without restriction, including
//  without limitation the rights to use, copy, modify, merge, publish,
//  distribute, sublicense, and/or sell copies of the Software, and to
//  permit persons to whom the Software is furnished to do so, subject to
//  the following conditions:
//
//  The above copyright notice and this permission notice shall be included
//  in all copies or substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
//  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
//  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
//  IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
//  CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
//  TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
//  SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package com.viro.core.internal;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSourceFactory;
import androidx.media3.datasource.RawResourceDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;

import com.google.common.base.Ascii;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Wraps the Android ExoPlayer and can be controlled via JNI.
 */
public class AVPlayer {

    private static final String TAG = "Viro";

    /**
     * These states mimic the underlying stats in the Android
     * MediaPlayer. We need to ensure we don't violate any state
     * in the Android MediaPlayer, else it becomes invalid.
     */
    private enum State {
        IDLE,
        PREPARED,
        PAUSED,
        STARTED,
    }

    private final ExoPlayer mExoPlayer;

    private Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private float mVolume;
    private final long mNativeReference;
    private boolean mLoop;
    private State mState;
    private boolean mMute;
    private int mPrevExoPlayerState = -1;
    private boolean mWasBuffering = false;

    public AVPlayer(long nativeReference, Context context) {
        mVolume = 1.0f;
        mNativeReference = nativeReference;
        mLoop = false;
        mState = State.IDLE;
        mMute = false;

        // AdaptiveTrackSelection.Factory trackSelectionFactory = new AdaptiveTrackSelection.Factory();
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context); 
        // trackSelectionFactory);
        mExoPlayer = new ExoPlayer.Builder(context).setTrackSelector(trackSelector).setLoadControl(new DefaultLoadControl()).build();

        mExoPlayer.addListener(new Player.Listener() {

            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                Log.i(TAG, "AVPlayer onPlayerStateChanged " + mPrevExoPlayerState + " => " + playbackState);
                // this function sometimes gets called back w/ the same playbackState.
                if (mPrevExoPlayerState == playbackState) {
                    return;
                }
                mPrevExoPlayerState = playbackState;
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        if (!mWasBuffering) {
                            nativeWillBuffer(mNativeReference);
                            mWasBuffering = true;
                        }
                        break;
                    case Player.STATE_READY:
                        if (mWasBuffering) {
                            nativeDidBuffer(mNativeReference);
                            mWasBuffering = false;
                        }
                        break;
                    case Player.STATE_ENDED:
                        if (mLoop) {
                            mExoPlayer.seekToDefaultPosition();
                        }
                        nativeOnFinished(mNativeReference);
                        break;
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.w(TAG, "AVPlayer encountered error [" + error + "]", error);
                nativeOnError(mNativeReference, error.getLocalizedMessage());
            }
        });
    }

    @FunctionalInterface
    public interface PlayerAction<T> {
        T performAction(ExoPlayer player);
    }

    private <T> T runSynchronouslyOnMainThread(PlayerAction<T> action) throws ExecutionException, InterruptedException {
        return runSynchronouslyOnMainThread(action, true);
    }

    private <T> T runSynchronouslyOnMainThread(PlayerAction<T> action, boolean waitForResult) throws ExecutionException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action.performAction(mExoPlayer);
        }

        Callable<T> callable = () -> action.performAction(mExoPlayer);
        FutureTask<T> future = new FutureTask<>(callable);

        mainThreadHandler.post(future);

        if (!waitForResult) return null;

        try {
            return future.get();
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer ExoPlayer failed to run action on the main thread", e);
            throw e;
        }
    }

    public boolean setDataSourceURL(String resourceOrURL, final Context context) {
        try {
            reset();

            Uri uri = Uri.parse(resourceOrURL);
            DataSource.Factory dataSourceFactory;
            ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
            if (resourceOrURL.startsWith("res")) {
                // the uri we get is in the form res:/#######, so we want the path
                // which is `/#######`, and the id is the path minus the first char
                int id = Integer.parseInt(uri.getPath().substring(1));
                uri = RawResourceDataSource.buildRawResourceUri(id);
                dataSourceFactory = new DataSource.Factory() {
                    @Override
                    public DataSource createDataSource() {
                        return new RawResourceDataSource(context);
                    }
                };
            } else {
                dataSourceFactory = new DefaultDataSourceFactory(context,
                        Util.getUserAgent(context, "ViroAVPlayer")); //, new DefaultBandwidthMeter());
            }
            Log.i(TAG, "AVPlayer setting URL to [" + uri + "]");

            MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, extractorsFactory);

            return runSynchronouslyOnMainThread(player -> {
                player.setMediaSource(mediaSource);
                player.prepare();
                player.seekToDefaultPosition();
                mState = State.PREPARED;
                Log.i(TAG, "AVPlayer prepared for playback");
                nativeOnPrepared(mNativeReference);
                return true;
            });
        } catch (Exception e) {
            Log.w(TAG, "AVPlayer failed to load video at URL [" + resourceOrURL + "]", e);
            reset();

            return false;
        }
    }

    private MediaSource buildMediaSource(Uri uri, DataSource.Factory mediaDataSourceFactory, ExtractorsFactory extractorsFactory) {
        int type = inferContentType(uri);
        switch (type) {
            case C.TYPE_SS:
                return new SsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            default:
                // Return an ExtraMediaSource as default.
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory, extractorsFactory).createMediaSource(MediaItem.fromUri(uri));
        }
    }

    private int inferContentType(Uri uri) {
        String path = uri.getPath();
        return path == null ? C.TYPE_OTHER : inferContentType(path);
    }

    private int inferContentType(String fileName) {
        fileName = Ascii.toLowerCase(fileName);
        if (fileName.endsWith(".mpd")) {
            return C.TYPE_DASH;
        } else if (fileName.endsWith(".m3u8")) {
            return C.TYPE_HLS;
        } else if (fileName.endsWith(".ism") || fileName.endsWith(".isml")
                || fileName.endsWith(".ism/manifest") || fileName.endsWith(".isml/manifest")) {
            return C.TYPE_SS;
        } else {
            return C.TYPE_OTHER;
        }
    }

    public void setVideoSink(Surface videoSink) {
        try {
            runSynchronouslyOnMainThread(player -> {
                player.setVideoSurface(videoSink);
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer failed to set video", e);
        }
    }

    public void reset() {
        try {
            runSynchronouslyOnMainThread(player -> {
                player.stop();
                player.seekToDefaultPosition();
                mState = State.IDLE;
                return null;
            });
            Log.i(TAG, "AVPlayer reset");
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer failed reset", e);
        }
    }

    public void destroy() {
        try {
            runSynchronouslyOnMainThread(player -> {
                    player.stop();
                    player.seekToDefaultPosition();
                    player.release();
                    mState = State.IDLE;
                    return null;
                },
                false // don't wait for the result to prevent ANR issue
            );
            Log.i(TAG, "AVPlayer destroyed");
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer destroy failed", e);
        }
    }

    public void play() {
        if (mState == State.PREPARED || mState == State.PAUSED) {
            try {
                runSynchronouslyOnMainThread(player -> {
                    player.setPlayWhenReady(true);
                    mState = State.STARTED;
                    return null;
                });
            } catch (Exception e) {
                Log.e(TAG, "AVPlayer failed to play video", e);
            }
        } else {
            Log.w(TAG, "AVPlayer could not play video in " + mState.toString() + " state");
        }
    }

    public void pause() {
        if (mState == State.STARTED) {
            try {
                runSynchronouslyOnMainThread(player -> {
                    player.setPlayWhenReady(false);
                    mState = State.PAUSED;
                    return null;
                });
            } catch (Exception e) {
                Log.e(TAG, "AVPlayer failed to pause video", e);
            }
        } else {
            Log.w(TAG, "AVPlayer could not pause video in " + mState.toString() + " state");
        }
    }

    public boolean isPaused() {
        return mState != State.STARTED;
    }

    public void setLoop(boolean loop) {
        mLoop = loop;
        try {
            runSynchronouslyOnMainThread(player -> {
                if (player.getPlaybackState() == ExoPlayer.STATE_ENDED) {
                    player.seekToDefaultPosition();
                }
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer failed to set loop", e);
        }
    }

    public void setVolume(float volume) {
        mVolume = volume;
        if (mMute) {
            return;
        }
        try {
            runSynchronouslyOnMainThread(player -> {
                player.setVolume(mVolume);
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer failed to set volume", e);
        }
    }

    public void setMuted(boolean muted) {
        mMute = muted;
        try {
            runSynchronouslyOnMainThread(player -> {
                if (muted) {
                    player.setVolume(0);
                } else {
                    player.setVolume(mVolume);
                }
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer failed to set muted " + muted, e);
        }
    }

    public void seekToTime(float seconds) {
        if (mState == State.IDLE) {
            Log.w(TAG, "AVPlayer could not seek while in IDLE state");
            return;
        }
        try {
            runSynchronouslyOnMainThread(player -> {
                player.seekTo((long) (seconds * 1000));
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer failed to seek", e);
        }
    }

    public float getCurrentTimeInSeconds() {
        if (mState == State.IDLE) {
            Log.w(TAG, "AVPlayer could not get current time in IDLE state");
            return 0;
        }

        long currentPosition = 0;
        try {
            currentPosition = runSynchronouslyOnMainThread(player -> player.getCurrentPosition());
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer could not get video current position", e);
        }

        return currentPosition / 1000.0f;
    }

    public float getVideoDurationInSeconds() {
        if (mState == State.IDLE) {
            Log.w(TAG, "AVPlayer could not get video duration in IDLE state");
            return 0;
        }

        long duration = 0;
        try {
            duration = runSynchronouslyOnMainThread(player -> player.getDuration());
        } catch (Exception e) {
            Log.e(TAG, "AVPlayer could not get video duration", e);
        }
        if (duration == C.TIME_UNSET) {
            return 0;
        }

        return duration / 1000.0f;
    }

    /**
     * Native Callbacks
     */
    private native void nativeOnPrepared(long ref);

    private native void nativeOnFinished(long ref);

    private native void nativeWillBuffer(long ref);

    private native void nativeDidBuffer(long ref);

    private native void nativeOnError(long ref, String error);
}

