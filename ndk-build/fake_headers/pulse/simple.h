#ifndef FAKE_PULSE_SIMPLE_H
#define FAKE_PULSE_SIMPLE_H

#include <stdint.h>
#include <sys/types.h>

typedef struct pa_simple pa_simple;

typedef enum pa_sample_format {
    PA_SAMPLE_U8,
    PA_SAMPLE_ALAW,
    PA_SAMPLE_ULAW,
    PA_SAMPLE_S16LE,
    PA_SAMPLE_S16BE,
    PA_SAMPLE_FLOAT32LE,
    PA_SAMPLE_FLOAT32BE,
    PA_SAMPLE_S32LE,
    PA_SAMPLE_S32BE,
    PA_SAMPLE_S24LE,
    PA_SAMPLE_S24BE,
    PA_SAMPLE_S24_32LE,
    PA_SAMPLE_S24_32BE,
    PA_SAMPLE_MAX,
    PA_SAMPLE_INVALID = -1
} pa_sample_format_t;

#define PA_SAMPLE_S16NE PA_SAMPLE_S16LE
#define PA_SAMPLE_FLOAT32NE PA_SAMPLE_FLOAT32LE

typedef struct pa_sample_spec {
    pa_sample_format_t format;
    uint32_t rate;
    uint8_t channels;
} pa_sample_spec;

typedef struct pa_buffer_attr {
    uint32_t maxlength;
    uint32_t tlength;
    uint32_t prebuf;
    uint32_t minreq;
    uint32_t fragsize;
} pa_buffer_attr;

typedef enum pa_stream_direction {
    PA_STREAM_NODIRECTION,
    PA_STREAM_PLAYBACK,
    PA_STREAM_RECORD,
    PA_STREAM_UPLOAD
} pa_stream_direction_t;

typedef struct pa_channel_map {
    uint8_t channels;
    int map[32];
} pa_channel_map;

pa_simple* pa_simple_new(
    const char *server,
    const char *name,
    pa_stream_direction_t dir,
    const char *dev,
    const char *stream_name,
    const pa_sample_spec *ss,
    const pa_channel_map *map,
    const pa_buffer_attr *attr,
    int *error);

void pa_simple_free(pa_simple *s);
int pa_simple_write(pa_simple *s, const void *data, size_t bytes, int *error);
int pa_simple_read(pa_simple *s, void *data, size_t bytes, int *error);
int64_t pa_simple_get_latency(pa_simple *s, int *error);

#endif
