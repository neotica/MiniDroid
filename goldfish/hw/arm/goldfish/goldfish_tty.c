/*
 * Android Goldfish TTY (serial) device.
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qemu/log.h"
#include "qapi/error.h"
#include "goldfish_device.h"

enum {
    TTY_PUT_CHAR         = 0x00,
    TTY_BYTES_READY      = 0x04,
    TTY_CMD              = 0x08,

    TTY_DATA_PTR         = 0x10,
    TTY_DATA_LEN         = 0x14,

    TTY_CMD_INT_DISABLE  = 0,
    TTY_CMD_INT_ENABLE   = 1,
    TTY_CMD_WRITE_BUFFER = 2,
    TTY_CMD_READ_BUFFER  = 3,
};

typedef struct GoldfishTtyState {
    GoldfishDevice dev;
    GoldfishBus *bus;
    CharFrontend chr;
    bool has_chr;
    uint32_t ptr;
    uint32_t ptr_len;
    uint32_t ready;
    uint8_t data[256];
    uint32_t data_count;
} GoldfishTtyState;

static uint64_t goldfish_tty_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishTtyState *s = opaque;

    switch (offset) {
    case TTY_BYTES_READY:
        return s->data_count;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_tty_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_tty_write(void *opaque, hwaddr offset,
                               uint64_t value, unsigned size)
{
    GoldfishTtyState *s = opaque;

    switch (offset) {
    case TTY_PUT_CHAR: {
        uint8_t ch = (uint8_t)value;
        if (s->has_chr) {
            qemu_chr_fe_write_all(&s->chr, &ch, 1);
        }
        break;
    }
    case TTY_CMD:
        switch (value) {
        case TTY_CMD_INT_DISABLE:
            if (s->ready) {
                if (s->data_count > 0) {
                    goldfish_device_set_irq(s->bus, &s->dev, 0, 0);
                }
                s->ready = 0;
            }
            break;
        case TTY_CMD_INT_ENABLE:
            if (!s->ready) {
                if (s->data_count > 0) {
                    goldfish_device_set_irq(s->bus, &s->dev, 0, 1);
                }
                s->ready = 1;
            }
            break;
        case TTY_CMD_WRITE_BUFFER:
            if (s->has_chr && s->ptr_len > 0) {
                uint8_t tmp[256];
                uint32_t vaddr = s->ptr;
                uint32_t rem = s->ptr_len;
                while (rem > 0) {
                    uint32_t chunk = rem > sizeof(tmp) ? sizeof(tmp) : rem;
                    goldfish_vmem_read(vaddr, tmp, chunk);
                    qemu_chr_fe_write_all(&s->chr, tmp, chunk);
                    vaddr += chunk;
                    rem -= chunk;
                }
            }
            break;
        case TTY_CMD_READ_BUFFER: {
            uint32_t to_read = s->ptr_len;
            if (to_read > s->data_count) {
                to_read = s->data_count;
            }
            if (to_read > 0) {
                goldfish_vmem_write(s->ptr, s->data, to_read);
                if (s->data_count > to_read) {
                    memmove(s->data, s->data + to_read,
                            s->data_count - to_read);
                }
                s->data_count -= to_read;
                if (s->has_chr) {
                    qemu_chr_fe_accept_input(&s->chr);
                }
            }
            if (s->data_count == 0 && s->ready) {
                goldfish_device_set_irq(s->bus, &s->dev, 0, 0);
            }
            break;
        }
        default:
            qemu_log_mask(LOG_GUEST_ERROR,
                          "goldfish_tty_write: bad cmd 0x%" PRIx64 "\n", value);
            break;
        }
        break;
    case TTY_DATA_PTR:
        s->ptr = (uint32_t)value;
        break;
    case TTY_DATA_LEN:
        s->ptr_len = (uint32_t)value;
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_tty_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        break;
    }
}

static int tty_can_receive(void *opaque)
{
    GoldfishTtyState *s = opaque;
    return (int)(sizeof(s->data) - s->data_count);
}

static void tty_receive(void *opaque, const uint8_t *buf, int size)
{
    GoldfishTtyState *s = opaque;
    uint32_t avail = sizeof(s->data) - s->data_count;
    uint32_t copy = (uint32_t)size > avail ? avail : (uint32_t)size;

    if (copy > 0) {
        memcpy(s->data + s->data_count, buf, copy);
        s->data_count += copy;
        if (s->ready) {
            goldfish_device_set_irq(s->bus, &s->dev, 0, 1);
        }
    }
}

static const MemoryRegionOps goldfish_tty_ops = {
    .read = goldfish_tty_read,
    .write = goldfish_tty_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

void goldfish_tty_create(GoldfishBus *bus, Chardev *chr, int id,
                         uint32_t base, uint32_t irq)
{
    GoldfishTtyState *s = g_new0(GoldfishTtyState, 1);

    s->bus = bus;
    s->dev.name = "goldfish_tty";
    s->dev.id = id;
    s->dev.base = base;
    s->dev.size = 0x1000;
    s->dev.irq = irq;
    s->dev.irq_count = 1;

    if (chr) {
        qemu_chr_fe_init(&s->chr, chr, &error_abort);
        s->has_chr = true;
        qemu_chr_fe_set_handlers(&s->chr, tty_can_receive, tty_receive,
                                 NULL, NULL, s, NULL, true);
    }

    goldfish_bus_add_device(bus, &s->dev, &goldfish_tty_ops, s);
}
