/*
 * Android Goldfish timer and RTC devices.
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qemu/log.h"
#include "qemu/timer.h"
#include "goldfish_device.h"

enum {
    TIMER_TIME_LOW        = 0x00,
    TIMER_TIME_HIGH       = 0x04,
    TIMER_ALARM_LOW       = 0x08,
    TIMER_ALARM_HIGH      = 0x0c,
    TIMER_CLEAR_INTERRUPT = 0x10,
    TIMER_CLEAR_ALARM     = 0x14,
};

typedef struct GoldfishTimerState {
    GoldfishDevice dev;
    GoldfishBus *bus;
    uint32_t alarm_low;
    int32_t alarm_high;
    int64_t now;
    QEMUTimer *timer;
} GoldfishTimerState;

static void goldfish_timer_tick(void *opaque)
{
    GoldfishTimerState *s = opaque;
    goldfish_device_set_irq(s->bus, &s->dev, 0, 1);
}

static uint64_t goldfish_timer_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishTimerState *s = opaque;

    switch (offset) {
    case TIMER_TIME_LOW:
        s->now = qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL);
        return (uint32_t)s->now;
    case TIMER_TIME_HIGH:
        return (uint32_t)((uint64_t)s->now >> 32);
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_timer_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_timer_write(void *opaque, hwaddr offset,
                                 uint64_t value, unsigned size)
{
    GoldfishTimerState *s = opaque;

    switch (offset) {
    case TIMER_ALARM_LOW: {
        int64_t alarm, now;
        s->alarm_low = (uint32_t)value;
        alarm = ((int64_t)s->alarm_high << 32) | s->alarm_low;
        now = qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL);
        if (alarm <= now) {
            goldfish_device_set_irq(s->bus, &s->dev, 0, 1);
        } else {
            timer_mod_ns(s->timer, alarm);
        }
        break;
    }
    case TIMER_ALARM_HIGH:
        s->alarm_high = (int32_t)value;
        break;
    case TIMER_CLEAR_ALARM:
        timer_del(s->timer);
        /* fall through */
    case TIMER_CLEAR_INTERRUPT:
        goldfish_device_set_irq(s->bus, &s->dev, 0, 0);
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_timer_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        break;
    }
}

static const MemoryRegionOps goldfish_timer_ops = {
    .read = goldfish_timer_read,
    .write = goldfish_timer_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

typedef struct GoldfishRtcState {
    GoldfishDevice dev;
    GoldfishBus *bus;
    uint32_t alarm_low;
    int32_t alarm_high;
    int64_t now;
} GoldfishRtcState;

static uint64_t goldfish_rtc_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishRtcState *s = opaque;

    switch (offset) {
    case 0x00:
        s->now = (int64_t)time(NULL) * 1000000000LL;
        return (uint32_t)s->now;
    case 0x04:
        return (uint32_t)((uint64_t)s->now >> 32);
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_rtc_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_rtc_write(void *opaque, hwaddr offset,
                               uint64_t value, unsigned size)
{
    GoldfishRtcState *s = opaque;

    switch (offset) {
    case 0x08:
        s->alarm_low = (uint32_t)value;
        break;
    case 0x0c:
        s->alarm_high = (int32_t)value;
        break;
    case 0x10:
    case 0x14:
        goldfish_device_set_irq(s->bus, &s->dev, 0, 0);
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_rtc_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        break;
    }
}

static const MemoryRegionOps goldfish_rtc_ops = {
    .read = goldfish_rtc_read,
    .write = goldfish_rtc_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

void goldfish_timer_and_rtc_init(GoldfishBus *bus, uint32_t timer_base,
                                 uint32_t timer_irq)
{
    GoldfishTimerState *ts = g_new0(GoldfishTimerState, 1);
    GoldfishRtcState *rs = g_new0(GoldfishRtcState, 1);

    ts->bus = bus;
    ts->dev.name = "goldfish_timer";
    ts->dev.id = -1;
    ts->dev.base = timer_base;
    ts->dev.size = 0x1000;
    ts->dev.irq = timer_irq;
    ts->dev.irq_count = 1;
    ts->timer = timer_new_ns(QEMU_CLOCK_VIRTUAL, goldfish_timer_tick, ts);
    goldfish_bus_add_device(bus, &ts->dev, &goldfish_timer_ops, ts);

    rs->bus = bus;
    rs->dev.name = "goldfish_rtc";
    rs->dev.id = -1;
    rs->dev.size = 0x1000;
    rs->dev.irq_count = 1;
    goldfish_bus_add_device(bus, &rs->dev, &goldfish_rtc_ops, rs);
}
