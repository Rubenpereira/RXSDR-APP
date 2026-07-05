#ifndef FAKE_PULSE_PULSEAUDIO_H
#define FAKE_PULSE_PULSEAUDIO_H

#include <stdbool.h>
#include "simple.h"

typedef struct pa_context pa_context;
typedef struct pa_mainloop pa_mainloop;
typedef struct pa_mainloop_api pa_mainloop_api;
typedef struct pa_operation pa_operation;

typedef enum pa_context_state {
    PA_CONTEXT_UNCONNECTED,
    PA_CONTEXT_CONNECTING,
    PA_CONTEXT_AUTHORIZING,
    PA_CONTEXT_SETTING_NAME,
    PA_CONTEXT_READY,
    PA_CONTEXT_FAILED,
    PA_CONTEXT_TERMINATED
} pa_context_state_t;

typedef enum pa_operation_state {
    PA_OPERATION_RUNNING,
    PA_OPERATION_DONE,
    PA_OPERATION_CANCELED
} pa_operation_state_t;

typedef struct pa_sink_info {
    const char *name;
    const char *description;
    uint32_t index;
} pa_sink_info;

typedef struct pa_source_info {
    const char *name;
    const char *description;
    uint32_t index;
} pa_source_info;

const char* pa_strerror(int error);

#endif
