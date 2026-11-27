/*
 * Android Goldfish audio output device (/dev/eac, 44.1 kHz 16-bit LE stereo PCM).
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 *
 * Streams raw PCM over an optional QEMU chardev named "audio0"
 * (e.g. -chardev socket,id=audio0,host=127.0.0.1,port=15558) while pacing
 * double-buffered write completion IRQs at exact real-time 44.1 kHz stereo.
 */

#include "qemu/osdep.h"
#include "qemu/log.h"
#include "qemu/timer.h"
#include "qapi/error.h"
#include "chardev/char.h"
#include "chardev/char-fe.h"
#include "system/address-spaces.h"
#include "goldfish_device.h"

#define AUDIO_BYTES_PER_SEC (44100LL * 2 * 2) /* 44.1 kHz, stereo, 16-bit LE */
#define AUDIO_MAX_BUF_BYTES (64 * 1024)

enum {
    AUDIO_INT_STATUS            = 0x00,
    AUDIO_INT_ENABLE            = 0x04,
    AUDIO_SET_WRITE_BUFFER_1    = 0x08,
    AUDIO_SET_WRITE_BUFFER_2    = 0x0C,
    AUDIO_WRITE_BUFFER_1        = 0x10,
    AUDIO_WRITE_BUFFER_2        = 0x14,
    AUDIO_READ_SUPPORTED        = 0x18,
    AUDIO_SET_READ_BUFFER       = 0x1C,
    AUDIO_START_READ            = 0x20,
    AUDIO_READ_BUFFER_AVAILABLE = 0x24,

    AUDIO_INT_WRITE_BUFFER_1_EMPTY = 1U << 0,
    AUDIO_INT_WRITE_BUFFER_2_EMPTY = 1U << 1,
    AUDIO_INT_READ_BUFFER_FULL     = 1U << 2,
};

typedef struct GoldfishAudioState {
    GoldfishDevice dev;
    GoldfishBus *bus;
    CharFrontend chr;
    bool has_chr;
    QEMUTimer *timer;

    uint32_t buffer_1;
    uint32_t buffer_2;
    uint32_t read_buffer;

    uint32_t int_status;
    uint32_t int_enable;

    int current_buffer; /* 0 = idle, 1 = playing buf1, 2 = playing buf2 */
    uint8_t buf1[AUDIO_MAX_BUF_BYTES];
    uint32_t buf1_len;
    uint8_t buf2[AUDIO_MAX_BUF_BYTES];
    uint32_t buf2_len;
} GoldfishAudioState;

static void audio_update_irq(GoldfishAudioState *s)
{
    goldfish_device_set_irq(s->bus, &s->dev, 0,
                            (s->int_status & s->int_enable) != 0);
}

static void audio_start_buffer(GoldfishAudioState *s, int which)
{
    const uint8_t *data = (which == 1) ? s->buf1 : s->buf2;
    uint32_t len = (which == 1) ? s->buf1_len : s->buf2_len;

    if (len == 0) {
        s->current_buffer = 0;
        return;
    }

    s->current_buffer = which;
    if (s->has_chr) {
        qemu_chr_fe_write_all(&s->chr, data, (int)len);
    }

    int64_t duration_ns = ((int64_t)len * 1000000000LL) / AUDIO_BYTES_PER_SEC;
    if (duration_ns < 1000000LL) {
        duration_ns = 1000000LL;
    }
    timer_mod_ns(s->timer, qemu_clock_get_ns(QEMU_CLOCK_REALTIME) + duration_ns);
}

static void goldfish_audio_timer_cb(void *opaque)
{
    GoldfishAudioState *s = opaque;
    uint32_t new_status = 0;

    if (s->current_buffer == 1) {
        s->buf1_len = 0;
        new_status |= AUDIO_INT_WRITE_BUFFER_1_EMPTY;
        if (s->buf2_len > 0) {
            audio_start_buffer(s, 2);
        } else {
            s->current_buffer = 0;
        }
    } else if (s->current_buffer == 2) {
        s->buf2_len = 0;
        new_status |= AUDIO_INT_WRITE_BUFFER_2_EMPTY;
        if (s->buf1_len > 0) {
            audio_start_buffer(s, 1);
        } else {
            s->current_buffer = 0;
        }
    }

    if (new_status) {
        s->int_status |= new_status;
        audio_update_irq(s);
    }
}

static uint64_t goldfish_audio_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishAudioState *s = opaque;

    switch (offset) {
    case AUDIO_INT_STATUS: {
        uint32_t ret = s->int_status & s->int_enable;
        if (ret) {
            goldfish_device_set_irq(s->bus, &s->dev, 0, 0);
        }
        return ret;
    }
    case AUDIO_READ_SUPPORTED:
        return 0;
    case AUDIO_READ_BUFFER_AVAILABLE:
        return 0;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_audio_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_audio_write(void *opaque, hwaddr offset,
                                 uint64_t val, unsigned size)
{
    GoldfishAudioState *s = opaque;
    uint32_t v = (uint32_t)val;

    switch (offset) {
    case AUDIO_INT_ENABLE:
        timer_del(s->timer);
        s->buf1_len = 0;
        s->buf2_len = 0;
        s->current_buffer = 0;
        s->int_enable = v;
        s->int_status = AUDIO_INT_WRITE_BUFFER_1_EMPTY |
                        AUDIO_INT_WRITE_BUFFER_2_EMPTY;
        audio_update_irq(s);
        break;
    case AUDIO_SET_WRITE_BUFFER_1:
        s->buffer_1 = v;
        break;
    case AUDIO_SET_WRITE_BUFFER_2:
        s->buffer_2 = v;
        break;
    case AUDIO_WRITE_BUFFER_1: {
        uint32_t len = v > AUDIO_MAX_BUF_BYTES ? AUDIO_MAX_BUF_BYTES : v;
        if (len > 0) {
            address_space_read(&address_space_memory, s->buffer_1,
                               MEMTXATTRS_UNSPECIFIED, s->buf1, len);
        }
        s->buf1_len = len;
        s->int_status &= ~AUDIO_INT_WRITE_BUFFER_1_EMPTY;
        if (s->current_buffer == 0 && len > 0) {
            audio_start_buffer(s, 1);
        } else if (len == 0) {
            s->int_status |= AUDIO_INT_WRITE_BUFFER_1_EMPTY;
            audio_update_irq(s);
        }
        break;
    }
    case AUDIO_WRITE_BUFFER_2: {
        uint32_t len = v > AUDIO_MAX_BUF_BYTES ? AUDIO_MAX_BUF_BYTES : v;
        if (len > 0) {
            address_space_read(&address_space_memory, s->buffer_2,
                               MEMTXATTRS_UNSPECIFIED, s->buf2, len);
        }
        s->buf2_len = len;
        s->int_status &= ~AUDIO_INT_WRITE_BUFFER_2_EMPTY;
        if (s->current_buffer == 0 && len > 0) {
            audio_start_buffer(s, 2);
        } else if (len == 0) {
            s->int_status |= AUDIO_INT_WRITE_BUFFER_2_EMPTY;
            audio_update_irq(s);
        }
        break;
    }
    case AUDIO_SET_READ_BUFFER:
        s->read_buffer = v;
        break;
    case AUDIO_START_READ:
        s->int_status &= ~AUDIO_INT_READ_BUFFER_FULL;
        audio_update_irq(s);
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_audio_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        break;
    }
}

static const MemoryRegionOps goldfish_audio_ops = {
    .read = goldfish_audio_read,
    .write = goldfish_audio_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

void goldfish_audio_create(GoldfishBus *bus, uint32_t base, int id)
{
    GoldfishAudioState *s = g_new0(GoldfishAudioState, 1);
    Chardev *chr = qemu_chr_find("audio0");

    s->bus = bus;
    s->dev.name = "goldfish_audio";
    s->dev.id = id;
    s->dev.base = base;
    s->dev.size = 0x1000;
    s->dev.irq = 0;
    s->dev.irq_count = 1;

    if (chr) {
        qemu_chr_fe_init(&s->chr, chr, &error_abort);
        s->has_chr = true;
    }

    s->timer = timer_new_ns(QEMU_CLOCK_REALTIME, goldfish_audio_timer_cb, s);
    goldfish_bus_add_device(bus, &s->dev, &goldfish_audio_ops, s);
}
