/* Non-variadic wrappers around opus_encoder_ctl / opus_decoder_ctl.
 * Swift cannot call C variadic functions or the OPUS_SET_* function-like
 * macros, so the handful of settings Motoparty needs are exposed here.
 * (Not named opus_*.h so scripts/fetch-opus.sh never overwrites it.) */
#ifndef MOTOPARTY_OPUS_SHIM_H
#define MOTOPARTY_OPUS_SHIM_H

#include "opus.h"

#ifdef __cplusplus
extern "C" {
#endif

int mp_opus_encoder_set_bitrate(OpusEncoder *enc, opus_int32 bitsPerSecond);
int mp_opus_encoder_set_inband_fec(OpusEncoder *enc, int on);
int mp_opus_encoder_set_packet_loss_perc(OpusEncoder *enc, int percent);
int mp_opus_encoder_set_dtx(OpusEncoder *enc, int on);
int mp_opus_encoder_set_complexity(OpusEncoder *enc, int complexity);
int mp_opus_encoder_set_signal_voice(OpusEncoder *enc);
int mp_opus_encoder_get_bitrate(OpusEncoder *enc, opus_int32 *out);
int mp_opus_encoder_get_inband_fec(OpusEncoder *enc, opus_int32 *out);
int mp_opus_encoder_get_dtx(OpusEncoder *enc, opus_int32 *out);
/* 1 while the encoder is in DTX (the last frame was a DTX frame). */
int mp_opus_encoder_get_in_dtx(OpusEncoder *enc, opus_int32 *out);
int mp_opus_decoder_reset(OpusDecoder *dec);
int mp_opus_decoder_get_last_packet_duration(OpusDecoder *dec, opus_int32 *out);

#ifdef __cplusplus
}
#endif

#endif
