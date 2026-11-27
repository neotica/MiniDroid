/*
 * Android Goldfish input events device (touchscreen + hardware keypad).
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qemu/log.h"
#include "ui/input.h"
#include "standard-headers/linux/input.h"
#include "goldfish_device.h"

#define MAX_EVENTS (256 * 4)

/* Android 1.6 emulator extra keycodes from qwerty.kl / hw-events.h */
#ifndef KEY_SOFT1
#define KEY_SOFT1 229
#endif
#ifndef KEY_SOFT2
#define KEY_SOFT2 230
#endif
#ifndef KEY_SEND
#define KEY_SEND 231
#endif
#ifndef KEY_CENTER
#define KEY_CENTER 232
#endif

enum {
    REG_READ     = 0x00,
    REG_SET_PAGE = 0x00,
    REG_LEN      = 0x04,
    REG_DATA     = 0x08,

    PAGE_NAME    = 0x00000,
    PAGE_EVBITS  = 0x10000,
    PAGE_ABSDATA = 0x20000 | EV_ABS,
};

typedef struct GoldfishEventsState {
    GoldfishDevice dev;
    GoldfishBus *bus;
    QemuInputHandlerState *ihs;

    int width;
    int height;
    int page;

    uint32_t events[MAX_EVENTS];
    uint32_t first;
    uint32_t last;

    const char *name;

    struct {
        size_t len;
        uint8_t *bits;
    } ev_bits[EV_MAX + 1];

    /* Pointer tracking for touchscreen */
    int abs_x;
    int abs_y;
    bool btn_down;
    bool pointer_dirty;
} GoldfishEventsState;

static void enqueue_event(GoldfishEventsState *s, uint32_t type,
                          uint32_t code, uint32_t value)
{
    uint32_t enqueued = (s->last - s->first) & (MAX_EVENTS - 1);
    if (enqueued + 3 >= MAX_EVENTS - 1) {
        return;
    }

    bool was_empty = (s->first == s->last);

    s->events[s->last] = type;
    s->last = (s->last + 1) & (MAX_EVENTS - 1);
    s->events[s->last] = code;
    s->last = (s->last + 1) & (MAX_EVENTS - 1);
    s->events[s->last] = value;
    s->last = (s->last + 1) & (MAX_EVENTS - 1);

    if (was_empty) {
        goldfish_device_set_irq(s->bus, &s->dev, 0, 1);
    }
}

static uint32_t dequeue_event(GoldfishEventsState *s)
{
    if (s->first == s->last) {
        return 0;
    }
    uint32_t v = s->events[s->first];
    s->first = (s->first + 1) & (MAX_EVENTS - 1);
    if (s->first == s->last) {
        goldfish_device_set_irq(s->bus, &s->dev, 0, 0);
    }
    return v;
}

static size_t get_page_len(GoldfishEventsState *s)
{
    int page = s->page;
    if (page == PAGE_NAME) {
        return strlen(s->name);
    }
    if (page >= PAGE_EVBITS && page <= PAGE_EVBITS + EV_MAX) {
        return s->ev_bits[page - PAGE_EVBITS].len;
    }
    return 0;
}

static uint32_t get_page_data(GoldfishEventsState *s, size_t offset)
{
    size_t len = get_page_len(s);
    int page = s->page;
    if (offset >= len) {
        return 0;
    }
    if (page == PAGE_NAME) {
        return (uint8_t)s->name[offset];
    }
    if (page >= PAGE_EVBITS && page <= PAGE_EVBITS + EV_MAX) {
        return s->ev_bits[page - PAGE_EVBITS].bits[offset];
    }
    return 0;
}

static uint64_t goldfish_events_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishEventsState *s = opaque;

    if (offset == REG_READ) {
        return dequeue_event(s);
    } else if (offset == REG_LEN) {
        return (uint32_t)get_page_len(s);
    } else if (offset >= REG_DATA) {
        return get_page_data(s, (size_t)(offset - REG_DATA));
    }
    return 0;
}

static void goldfish_events_write(void *opaque, hwaddr offset,
                                  uint64_t val, unsigned size)
{
    GoldfishEventsState *s = opaque;

    if (offset == REG_SET_PAGE) {
        s->page = (int)val;
    }
}

static const MemoryRegionOps goldfish_events_ops = {
    .read = goldfish_events_read,
    .write = goldfish_events_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

static void events_set_bits(GoldfishEventsState *s, int type,
                            int bitl, int bith)
{
    int il = bitl / 8;
    int ih = bith / 8;

    if (type < 0 || type > EV_MAX) {
        return;
    }
    if ((size_t)ih >= s->ev_bits[type].len) {
        uint8_t *bits = g_malloc0((size_t)ih + 1);
        if (s->ev_bits[type].bits) {
            memcpy(bits, s->ev_bits[type].bits, s->ev_bits[type].len);
            g_free(s->ev_bits[type].bits);
        }
        s->ev_bits[type].bits = bits;
        s->ev_bits[type].len = (size_t)ih + 1;
    }

    uint8_t *bits = s->ev_bits[type].bits;
    uint8_t maskl = (uint8_t)(0xffU << (bitl & 7));
    uint8_t maskh = (uint8_t)(0xffU >> (7 - (bith & 7)));

    if (il >= ih) {
        maskh &= maskl;
    } else {
        bits[il] |= maskl;
        while (++il < ih) {
            bits[il] = 0xff;
        }
    }
    bits[ih] |= maskh;
}

static void events_set_bit(GoldfishEventsState *s, int type, int bit)
{
    events_set_bits(s, type, bit, bit);
}

static unsigned int map_android_key(unsigned int lnx)
{
    /* Map convenient PC/VNC keys to Android 1.6 qwerty.kl keycodes */
    switch (lnx) {
    case KEY_ESC:
        return KEY_BACK;      /* 158 = BACK */
    case KEY_MENU:
        return KEY_F1;        /* 59 = MENU in qwerty.kl */
    case KEY_F5:
        return KEY_SEARCH;    /* 217 = SEARCH */
    case KEY_F7:
        return KEY_POWER;     /* 116 = POWER */
    case KEY_F8:
        return KEY_VOLUMEDOWN;
    case KEY_F9:
        return KEY_VOLUMEUP;
    case KEY_KPENTER:
        return KEY_CENTER;    /* 232 = DPAD_CENTER */
    default:
        return lnx;
    }
}

static void goldfish_input_event(DeviceState *dev, QemuConsole *src,
                                 QemuInputEvent *evt)
{
    GoldfishEventsState *s = (GoldfishEventsState *)dev;

    switch (evt->type) {
    case INPUT_EVENT_KIND_KEY: {
        unsigned int code = map_android_key(evt->key.key);
        enqueue_event(s, EV_KEY, code & 0x1ff, evt->key.down ? 1 : 0);
        enqueue_event(s, EV_SYN, SYN_REPORT, 0);
        break;
    }
    case INPUT_EVENT_KIND_ABS:
        if (evt->abs.axis == INPUT_AXIS_X) {
            s->abs_x = qemu_input_scale_axis(evt->abs.value,
                                             INPUT_EVENT_ABS_MIN,
                                             INPUT_EVENT_ABS_MAX,
                                             0, s->width - 1);
            if (s->btn_down) {
                s->pointer_dirty = true;
            }
        } else if (evt->abs.axis == INPUT_AXIS_Y) {
            s->abs_y = qemu_input_scale_axis(evt->abs.value,
                                             INPUT_EVENT_ABS_MIN,
                                             INPUT_EVENT_ABS_MAX,
                                             0, s->height - 1);
            if (s->btn_down) {
                s->pointer_dirty = true;
            }
        }
        break;
    case INPUT_EVENT_KIND_BTN:
        if (evt->btn.button == INPUT_BUTTON_LEFT ||
            evt->btn.button == INPUT_BUTTON_TOUCH) {
            if (s->btn_down != evt->btn.down) {
                s->btn_down = evt->btn.down;
                s->pointer_dirty = true;
            }
        } else if (evt->btn.button == INPUT_BUTTON_RIGHT) {
            /* Right click convenience -> Android BACK */
            enqueue_event(s, EV_KEY, KEY_BACK, evt->btn.down ? 1 : 0);
            enqueue_event(s, EV_SYN, SYN_REPORT, 0);
        }
        break;
    default:
        break;
    }
}

static void goldfish_input_sync(DeviceState *dev)
{
    GoldfishEventsState *s = (GoldfishEventsState *)dev;

    if (s->pointer_dirty) {
        enqueue_event(s, EV_ABS, ABS_X, (uint32_t)s->abs_x);
        enqueue_event(s, EV_ABS, ABS_Y, (uint32_t)s->abs_y);
        enqueue_event(s, EV_ABS, ABS_Z, 0);
        enqueue_event(s, EV_KEY, BTN_TOUCH, s->btn_down ? 1 : 0);
        enqueue_event(s, EV_SYN, SYN_REPORT, 0);
        s->pointer_dirty = false;
    }
}

static const QemuInputHandler goldfish_input_handler = {
    .name  = "Goldfish Touchscreen & Keypad",
    .mask  = INPUT_EVENT_MASK_KEY | INPUT_EVENT_MASK_BTN | INPUT_EVENT_MASK_ABS,
    .event = goldfish_input_event,
    .sync  = goldfish_input_sync,
};

void goldfish_events_create(GoldfishBus *bus, int width, int height)
{
    GoldfishEventsState *s = g_new0(GoldfishEventsState, 1);

    s->bus = bus;
    s->width = width > 0 ? width : 320;
    s->height = height > 0 ? height : 480;
    s->name = "qwerty";

    /* Event capability bitmasks matching Android 1.6 emulator */
    events_set_bit(s, EV_SYN, EV_KEY);
    events_set_bit(s, EV_KEY, KEY_HOME);
    events_set_bit(s, EV_KEY, KEY_BACK);
    events_set_bit(s, EV_KEY, KEY_SEND);
    events_set_bit(s, EV_KEY, KEY_END);
    events_set_bit(s, EV_KEY, KEY_SOFT1);
    events_set_bit(s, EV_KEY, KEY_SOFT2);
    events_set_bit(s, EV_KEY, KEY_VOLUMEUP);
    events_set_bit(s, EV_KEY, KEY_VOLUMEDOWN);
    events_set_bit(s, EV_KEY, KEY_POWER);
    events_set_bit(s, EV_KEY, KEY_SEARCH);
    events_set_bit(s, EV_KEY, KEY_DOWN);
    events_set_bit(s, EV_KEY, KEY_UP);
    events_set_bit(s, EV_KEY, KEY_LEFT);
    events_set_bit(s, EV_KEY, KEY_RIGHT);
    events_set_bit(s, EV_KEY, KEY_CENTER);
    events_set_bit(s, EV_KEY, BTN_TOUCH);
    events_set_bits(s, EV_KEY, 1, 0xff);
    events_set_bits(s, EV_KEY, 0x160, 0x1ff);

    events_set_bit(s, EV_SYN, EV_ABS);
    events_set_bits(s, EV_ABS, ABS_X, ABS_Z);

    s->dev.name = "goldfish_events";
    s->dev.id = 0;
    s->dev.size = 0x1000;
    s->dev.irq_count = 1;

    goldfish_bus_add_device(bus, &s->dev, &goldfish_events_ops, s);

    s->ihs = qemu_input_handler_register((DeviceState *)s,
                                         &goldfish_input_handler);
    qemu_input_handler_activate(s->ihs);
}
