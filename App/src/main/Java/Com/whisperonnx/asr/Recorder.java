package com.whisperonnx.asr;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;
import com.whisperonnx.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Recorder {

    public interface RecorderListener {
        void onUpdateReceived(String message);
    }

    private static final String TAG = "Recorder";
    public static final String ACTION_STOP = "Stop";
    public static final String ACTION_RECORD = "Record";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private RecorderListener mListener;
    private final Lock lock = new ReentrantLock();
    private final Condition hasTask = lock.newCondition();
    private final Object fileSavedLock = new Object(); // Lock object for wait/notify

    private volatile boolean shouldStartRecording = false;
    private boolean useVAD = false;
    private VadWebRTC vad = null;
    private static final int VAD_FRAME_SIZE = 480;
    private SharedPreferences sp;

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;
        sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        // Initialize and start the worker thread
        workerThread = new Thread(this::recordLoop);
        workerThread.start();
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }


    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
    }

    public void initVad(){
        int silenceDurationMs = sp.getInt("silenceDurationMs", 800);
        vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(silenceDurationMs)
                .setSpeechDurationMs(200)
                .build();
        useVAD = true;
        Log.d(TAG, "VAD initialized");
    }


    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);

        // Wait for the recording thread to finish
        synchronized (fileSavedLock) {
            try {
                fileSavedLock.wait(); // Wait until notified by the recording thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
        }
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        if (mListener != null)
            mListener.onUpdateReceived(message);
    }


    private void recordLoop() {
        while (true) {
            lock.lock();
            try {
                while (!shouldStartRecording) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            // Start recording process
            try {
                recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(e.getMessage());
            } finally {
                mInProgress.set(false);
            }
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(mContext.getString(R.string.need_record_audio_permission));
            return;
        }

        int channels = 1;
        int bytesPerSample = 2;
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateInHz)
                        .build())
                .setBufferSizeInBytes(bufferSize);

        AudioRecord audioRecord = builder.build();
        audioRecord.startRecording();

        // Calculate maximum byte counts for 30 seconds (for saving)
        int bytesForThirtySeconds = sampleRateInHz * bytesPerSample * channels * 30;

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(); // Buffer for saving data RecordBuffer

        FileOutputStream wavOutputStream = null;
        File wavFile = null;

        try {
            File recordingsDir = new File(mContext.getExternalFilesDir(null), "Recordings");
            if (!recordingsDir.exists()) recordingsDir.mkdirs();

            String filename = "Recording_" + System.currentTimeMillis() + ".wav";
            wavFile = new File(recordingsDir, filename);

            wavOutputStream = new FileOutputStream(wavFile);

            // write placeholder header
            byte[] header = new byte[44];
            wavOutputStream.write(header);

        } catch (IOException e) {
            Log.e(TAG, "Failed to create WAV file", e);
        }


        byte[] audioData = new byte[bufferSize];
        int totalBytesRead = 0;

        boolean isSpeech;
        boolean isRecording = false;
        byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  //VAD needs 16 bit

        while (mInProgress.get() && totalBytesRead < bytesForThirtySeconds) {
            int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
            if (bytesRead > 0) {
                outputBuffer.write(audioData, 0, bytesRead);  // Save all bytes read up to 30 seconds
                try {
                    if (wavOutputStream != null) {
                        wavOutputStream.write(audioData, 0, bytesRead);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error writing WAV data", e);
                }

                totalBytesRead += bytesRead;
            } else {
                Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                break;
            }

            if (useVAD){
                byte[] outputBufferByteArray = outputBuffer.toByteArray();
                if (outputBufferByteArray.length >= VAD_FRAME_SIZE * 2) {
                    // Always use the last VAD_FRAME_SIZE * 2 bytes (16 bit) from outputBuffer for VAD
                    System.arraycopy(outputBufferByteArray, outputBufferByteArray.length - VAD_FRAME_SIZE * 2, vadAudioBuffer, 0, VAD_FRAME_SIZE * 2);

                    isSpeech = vad.isSpeech(vadAudioBuffer);
                    if (isSpeech) {
                        if (!isRecording) {
                            Log.d(TAG, "VAD Speech detected: recording starts");
                            sendUpdate(MSG_RECORDING);
                        }
                        isRecording = true;
                    } else {
                        if (isRecording) {
                            isRecording = false;
                            mInProgress.set(false);
                        }
                    }
                }
            } else {
                if (!isRecording) sendUpdate(MSG_RECORDING);
                isRecording = true;
            }
        }
        Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

        if (useVAD){
            useVAD = false;
            vad.close();
            vad = null;
            Log.d(TAG, "Closing VAD");
        }
        audioRecord.stop();
        audioRecord.release();

        try {
            if (wavOutputStream != null) {
                long totalAudioLen = wavOutputStream.getChannel().size() - 44;
                long totalDataLen = totalAudioLen + 36;
                long sampleRate = sampleRateInHz;
                int channelsCount = channels;
                long byteRate = sampleRate * channelsCount * bytesPerSample;

                wavOutputStream.close();

                byte[] header = new byte[44];

                header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
                header[4] = (byte)(totalDataLen & 0xff);
                header[5] = (byte)((totalDataLen >> 8) & 0xff);
                header[6] = (byte)((totalDataLen >> 16) & 0xff);
                header[7] = (byte)((totalDataLen >> 24) & 0xff);
                header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';

                header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
                header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
                header[20] = 1; header[21] = 0;
                header[22] = (byte) channelsCount;
                header[23] = 0;
                header[24] = (byte)(sampleRate & 0xff);
                header[25] = (byte)((sampleRate >> 8) & 0xff);
                header[26] = (byte)((sampleRate >> 16) & 0xff);
                header[27] = (byte)((sampleRate >> 24) & 0xff);
                header[28] = (byte)(byteRate & 0xff);
                header[29] = (byte)((byteRate >> 8) & 0xff);
                header[30] = (byte)((byteRate >> 16) & 0xff);
                header[31] = (byte)((byteRate >> 24) & 0xff);
                header[32] = (byte)(channelsCount * bytesPerSample);
                header[33] = 0;
                header[34] = 16;
                header[35] = 0;
                header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
                header[40] = (byte)(totalAudioLen & 0xff);
                header[41] = (byte)((totalAudioLen >> 8) & 0xff);
                header[42] = (byte)((totalAudioLen >> 16) & 0xff);
                header[43] = (byte)((totalAudioLen >> 24) & 0xff);

                FileOutputStream overwrite = new FileOutputStream(wavFile);
                overwrite.write(header);
                overwrite.close();

                Log.d(TAG, "WAV saved: " + wavFile.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed finalizing WAV", e);
        }

        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);

        // Save recorded audio data to BufferStore (up to 30 seconds)
        RecordBuffer.setOutputBuffer(outputBuffer.toByteArray());
        if (totalBytesRead > 6400){  //min 0.2s
            sendUpdate(MSG_RECORDING_DONE);
        } else {
            sendUpdate(MSG_RECORDING_ERROR);
        }

        // Notify the waiting thread that recording is complete
        synchronized (fileSavedLock) {
            fileSavedLock.notify(); // Notify that recording is finished
        }

    }

}
