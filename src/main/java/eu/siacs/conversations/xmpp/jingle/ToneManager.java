package eu.siacs.conversations.xmpp.jingle;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;

import static java.util.Arrays.asList;

class ToneManager {

    private final ToneGenerator toneGenerator;

    private ToneState state = null;
    private ScheduledFuture<?> currentTone;

    ToneManager() {
        ToneGenerator toneGenerator;
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 35);
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to instantiate ToneGenerator", e);
            toneGenerator = null;
        }
        this.toneGenerator = toneGenerator;
    }

    void transition(final RtpEndUserState state) {
        transition(of(true, state, Collections.emptySet()));
    }

    void transition(final boolean isInitiator, final RtpEndUserState state, final Set<Media> media) {
        transition(of(isInitiator, state, media));
    }

    private static ToneState of(final boolean isInitiator, final RtpEndUserState state, final Set<Media> media) {
        if (isInitiator) {
            if (asList(RtpEndUserState.RINGING, RtpEndUserState.CONNECTING).contains(state)) {
                return ToneState.RINGING;
            }
            if (state == RtpEndUserState.DECLINED_OR_BUSY) {
                return ToneState.BUSY;
            }
        }
        if (state == RtpEndUserState.ENDING_CALL) {
            if (media.contains(Media.VIDEO)) {
                return ToneState.NULL;
            } else {
                return ToneState.ENDING_CALL;
            }
        }
        if (state == RtpEndUserState.CONNECTED) {
            if (media.contains(Media.VIDEO)) {
                return ToneState.NULL;
            } else {
                return ToneState.CONNECTED;
            }
        }
        return ToneState.NULL;
    }

    private synchronized void transition(ToneState state) {
        if (this.state == state) {
            return;
        }
        if (state == ToneState.NULL && this.state == ToneState.ENDING_CALL) {
            return;
        }
        cancelCurrentTone();
        Log.d(Config.LOGTAG, getClass().getName() + ".transition(" + state + ")");
        switch (state) {
            case RINGING:
                scheduleWaitingTone();
                break;
            case CONNECTED:
                scheduleConnected();
                break;
            case BUSY:
                scheduleBusy();
                break;
            case ENDING_CALL:
                scheduleEnding();
                break;
        }
        this.state = state;
    }

    private void scheduleConnected() {
        this.currentTone = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(() -> {
            startTone(ToneGenerator.TONE_PROP_PROMPT, 200);
        }, 0, TimeUnit.SECONDS);
    }

    private void scheduleEnding() {
        this.currentTone = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(() -> {
            startTone(ToneGenerator.TONE_CDMA_CALLDROP_LITE, 375);
        }, 0, TimeUnit.SECONDS);
    }

    private void scheduleBusy() {
        this.currentTone = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(() -> {
            startTone(ToneGenerator.TONE_CDMA_NETWORK_BUSY, 2500);
        }, 0, TimeUnit.SECONDS);
    }

    private void scheduleWaitingTone() {
        this.currentTone = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> {
            startTone(ToneGenerator.TONE_CDMA_DIAL_TONE_LITE, 750);
        }, 0, 3, TimeUnit.SECONDS);
    }

    private void cancelCurrentTone() {
        if (currentTone != null) {
            currentTone.cancel(true);
        }
        if (toneGenerator != null) {
            toneGenerator.stopTone();
        }
    }

    private void startTone(final int toneType, final int durationMs) {
        if (toneGenerator != null) {
            this.toneGenerator.startTone(toneType, durationMs);
        } else {
            Log.e(Config.LOGTAG, "failed to start tone. ToneGenerator doesn't exist");
        }
    }

    private enum ToneState {
        NULL, RINGING, CONNECTED, BUSY, ENDING_CALL
    }
}
