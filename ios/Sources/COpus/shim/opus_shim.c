#include "motoparty_opus.h"

int mp_opus_encoder_set_bitrate(OpusEncoder *enc, opus_int32 bitsPerSecond)
{
    return opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitsPerSecond));
}

int mp_opus_encoder_set_inband_fec(OpusEncoder *enc, int on)
{
    return opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(on ? 1 : 0));
}

int mp_opus_encoder_set_packet_loss_perc(OpusEncoder *enc, int percent)
{
    return opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(percent));
}

int mp_opus_encoder_set_dtx(OpusEncoder *enc, int on)
{
    return opus_encoder_ctl(enc, OPUS_SET_DTX(on ? 1 : 0));
}

int mp_opus_encoder_set_complexity(OpusEncoder *enc, int complexity)
{
    return opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));
}

int mp_opus_encoder_set_signal_voice(OpusEncoder *enc)
{
    return opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
}

int mp_opus_encoder_get_bitrate(OpusEncoder *enc, opus_int32 *out)
{
    return opus_encoder_ctl(enc, OPUS_GET_BITRATE(out));
}

int mp_opus_encoder_get_inband_fec(OpusEncoder *enc, opus_int32 *out)
{
    return opus_encoder_ctl(enc, OPUS_GET_INBAND_FEC(out));
}

int mp_opus_encoder_get_dtx(OpusEncoder *enc, opus_int32 *out)
{
    return opus_encoder_ctl(enc, OPUS_GET_DTX(out));
}

int mp_opus_encoder_get_in_dtx(OpusEncoder *enc, opus_int32 *out)
{
    return opus_encoder_ctl(enc, OPUS_GET_IN_DTX(out));
}

int mp_opus_decoder_reset(OpusDecoder *dec)
{
    return opus_decoder_ctl(dec, OPUS_RESET_STATE);
}

int mp_opus_decoder_get_last_packet_duration(OpusDecoder *dec, opus_int32 *out)
{
    return opus_decoder_ctl(dec, OPUS_GET_LAST_PACKET_DURATION(out));
}
