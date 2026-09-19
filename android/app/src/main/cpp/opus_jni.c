// Thin JNI over libopus: one encoder and one decoder per handle, 16-bit PCM in/out.
// Kotlin side: com.kivan.motoparty.audio.Opus.
#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define FN(name) Java_com_kivan_motoparty_audio_Opus_##name

JNIEXPORT jlong JNICALL FN(encoderCreate)(JNIEnv *env, jclass cls, jint rate, jint channels,
                                          jint bitrate, jint lossPercent) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(rate, channels, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == NULL) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(lossPercent));
    opus_encoder_ctl(enc, OPUS_SET_DTX(1));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(8));
    return (jlong)(intptr_t)enc;
}

JNIEXPORT jint JNICALL FN(encode)(JNIEnv *env, jclass cls, jlong handle, jshortArray pcm,
                                  jint frameSize, jbyteArray out) {
    OpusEncoder *enc = (OpusEncoder *)(intptr_t)handle;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jint outLen = (*env)->GetArrayLength(env, out);
    int n = opus_encode(enc, in, frameSize, (unsigned char *)o, outLen);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}

// 1 while the encoder is in DTX (sending nothing or comfort-noise updates).
JNIEXPORT jint JNICALL FN(encoderInDtx)(JNIEnv *env, jclass cls, jlong handle) {
    opus_int32 v = 0;
    opus_encoder_ctl((OpusEncoder *)(intptr_t)handle, OPUS_GET_IN_DTX(&v));
    return v;
}

JNIEXPORT void JNICALL FN(encoderDestroy)(JNIEnv *env, jclass cls, jlong handle) {
    if (handle) opus_encoder_destroy((OpusEncoder *)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL FN(decoderCreate)(JNIEnv *env, jclass cls, jint rate, jint channels) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(rate, channels, &err);
    if (err != OPUS_OK || dec == NULL) return 0;
    return (jlong)(intptr_t)dec;
}

// data == null -> packet-loss concealment. fec != 0 -> decode the in-band FEC copy of the
// previous frame that `data` (the successor) carries.
JNIEXPORT jint JNICALL FN(decode)(JNIEnv *env, jclass cls, jlong handle, jbyteArray data,
                                  jint len, jshortArray pcm, jint frameSize, jint fec) {
    OpusDecoder *dec = (OpusDecoder *)(intptr_t)handle;
    jshort *out = (*env)->GetShortArrayElements(env, pcm, NULL);
    int n;
    if (data == NULL) {
        n = opus_decode(dec, NULL, 0, out, frameSize, 0);
    } else {
        jbyte *d = (*env)->GetByteArrayElements(env, data, NULL);
        n = opus_decode(dec, (const unsigned char *)d, len, out, frameSize, fec);
        (*env)->ReleaseByteArrayElements(env, data, d, JNI_ABORT);
    }
    (*env)->ReleaseShortArrayElements(env, pcm, out, 0);
    return n;
}

JNIEXPORT void JNICALL FN(decoderDestroy)(JNIEnv *env, jclass cls, jlong handle) {
    if (handle) opus_decoder_destroy((OpusDecoder *)(intptr_t)handle);
}

JNIEXPORT jstring JNICALL FN(version)(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, opus_get_version_string());
}
