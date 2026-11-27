/*
 * Android Goldfish battery device and memlog stub.
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qemu/log.h"
#include "goldfish_device.h"

enum {
    POWER_SUPPLY_STATUS_CHARGING = 1,
    POWER_SUPPLY_HEALTH_GOOD     = 1,
};

enum {
    BATTERY_INT_STATUS = 0x00,
    BATTERY_INT_ENABLE = 0x04,
    BATTERY_AC_ONLINE  = 0x08,
    BATTERY_STATUS     = 0x0c,
    BATTERY_HEALTH     = 0x10,
    BATTERY_PRESENT    = 0x14,
    BATTERY_CAPACITY   = 0x18,
};

typedef struct GoldfishBatteryState {
    GoldfishDevice dev;
    GoldfishBus *bus;
    uint32_t int_status;
    uint32_t int_enable;
    uint32_t ac_online;
    uint32_t status;
    uint32_t health;
    uint32_t present;
    uint32_t capacity;
} GoldfishBatteryState;

static uint64_t goldfish_battery_read(void *opaque, hwaddr offset,
                                      unsigned size)
{
    GoldfishBatteryState *s = opaque;

    switch (offset) {
    case BATTERY_INT_STATUS: {
        uint32_t ret = s->int_status & s->int_enable;
        if (ret) {
            goldfish_device_set_irq(s->bus, &s->dev, 0, 0);
            s->int_status = 0;
        }
        return ret;
    }
    case BATTERY_INT_ENABLE:
        return s->int_enable;
    case BATTERY_AC_ONLINE:
        return s->ac_online;
    case BATTERY_STATUS:
        return s->status;
    case BATTERY_HEALTH:
        return s->health;
    case BATTERY_PRESENT:
        return s->present;
    case BATTERY_CAPACITY:
        return s->capacity;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_battery_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_battery_write(void *opaque, hwaddr offset,
                                   uint64_t val, unsigned size)
{
    GoldfishBatteryState *s = opaque;

    if (offset == BATTERY_INT_ENABLE) {
        s->int_enable = (uint32_t)val;
    }
}

static const MemoryRegionOps goldfish_battery_ops = {
    .read = goldfish_battery_read,
    .write = goldfish_battery_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

void goldfish_battery_create(GoldfishBus *bus)
{
    GoldfishBatteryState *s = g_new0(GoldfishBatteryState, 1);

    s->bus = bus;
    s->dev.name = "goldfish-battery";
    s->dev.id = 0;
    s->dev.size = 0x1000;
    s->dev.irq_count = 1;

    s->ac_online = 1;
    s->status = POWER_SUPPLY_STATUS_CHARGING;
    s->health = POWER_SUPPLY_HEALTH_GOOD;
    s->present = 1;
    s->capacity = 85;

    goldfish_bus_add_device(bus, &s->dev, &goldfish_battery_ops, s);
}

/* Memlog stub */
static uint64_t goldfish_memlog_read(void *opaque, hwaddr offset, unsigned size)
{
    return 0;
}

static void goldfish_memlog_write(void *opaque, hwaddr offset,
                                  uint64_t val, unsigned size)
{
    (void)opaque;
    (void)offset;
    (void)val;
    (void)size;
}

static const MemoryRegionOps goldfish_memlog_ops = {
    .read = goldfish_memlog_read,
    .write = goldfish_memlog_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

void goldfish_memlog_create(GoldfishBus *bus, uint32_t base)
{
    GoldfishDevice *dev = g_new0(GoldfishDevice, 1);
    dev->name = "goldfish_memlog";
    dev->id = 0;
    dev->base = base;
    dev->size = 0x1000;
    dev->irq_count = 0;
    goldfish_bus_add_device(bus, dev, &goldfish_memlog_ops, dev);
}
