/*
 * Android Goldfish RGB565 framebuffer device.
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qemu/log.h"
#include "qemu/timer.h"
#include "qemu/bswap.h"
#include "system/address-spaces.h"
#include "ui/console.h"
#include "goldfish_device.h"

enum {
    FB_GET_WIDTH       = 0x00,
    FB_GET_HEIGHT      = 0x04,
    FB_INT_STATUS      = 0x08,
    FB_INT_ENABLE      = 0x0c,
    FB_SET_BASE        = 0x10,
    FB_SET_ROTATION    = 0x14,
    FB_SET_BLANK       = 0x18,
    FB_GET_PHYS_WIDTH  = 0x1c,
    FB_GET_PHYS_HEIGHT = 0x20,

    FB_INT_VSYNC            = 1U << 0,
    FB_INT_BASE_UPDATE_DONE = 1U << 1,
};

typedef struct GoldfishFbState {
    GoldfishDevice dev;
    GoldfishBus *bus;
    QemuConsole *con;
    QEMUTimer *update_timer;

    int width;
    int height;
    int phys_width_mm;
    int phys_height_mm;

    uint32_t fb_base;
    bool base_valid;
    bool need_update;
    bool need_int;
    bool blank;
    uint32_t int_status;
    uint32_t int_enable;
    uint32_t set_rotation;
    uint32_t rotation;

    uint8_t *raw_buf;
} GoldfishFbState;

static void goldfish_fb_update_irq(GoldfishFbState *s)
{
    goldfish_device_set_irq(s->bus, &s->dev, 0,
                            (s->int_status & s->int_enable) != 0);
}

static void goldfish_fb_complete_frame(GoldfishFbState *s)
{
    if (!s->base_valid || s->fb_base == 0) {
        return;
    }

    if (s->need_int) {
        s->int_status |= FB_INT_BASE_UPDATE_DONE;
        s->need_int = false;
    }
    if (s->int_enable & FB_INT_VSYNC) {
        s->int_status |= FB_INT_VSYNC;
    }
    goldfish_fb_update_irq(s);
}

static void goldfish_fb_timer_cb(void *opaque)
{
    GoldfishFbState *s = opaque;
    goldfish_fb_complete_frame(s);
    if (s->con) {
        qemu_console_hw_update(s->con);
    }
}

static bool goldfish_fb_gfx_update(void *opaque)
{
    GoldfishFbState *s = opaque;
    DisplaySurface *surface = qemu_console_surface(s->con);
    size_t fb_bytes = (size_t)s->width * (size_t)s->height * 2;

    if (surface_width(surface) != s->width ||
        surface_height(surface) != s->height) {
        qemu_console_resize(s->con, s->width, s->height);
        surface = qemu_console_surface(s->con);
        s->need_update = true;
    }

    if (!s->base_valid || s->fb_base == 0) {
        return true;
    }

    goldfish_fb_complete_frame(s);

    uint8_t *dst_base = surface_data(surface);
    int dst_stride = surface_stride(surface);

    if (s->blank) {
        memset(dst_base, 0, (size_t)dst_stride * (size_t)s->height);
        qemu_console_update_full(s->con);
        s->need_update = false;
        return true;
    }

    address_space_read(&address_space_memory, s->fb_base,
                       MEMTXATTRS_UNSPECIFIED, s->raw_buf, fb_bytes);

    int y_first = -1;
    int y_last = -1;

    for (int y = 0; y < s->height; y++) {
        const uint8_t *src = s->raw_buf + (size_t)y * (size_t)s->width * 2;
        uint32_t *dst = (uint32_t *)(dst_base + (size_t)y * (size_t)dst_stride);
        bool row_changed = s->need_update;

        for (int x = 0; x < s->width; x++) {
            uint16_t p = lduw_le_p(src + x * 2);
            uint32_t r = ((p >> 11) & 0x1f) * 255 / 31;
            uint32_t g = ((p >> 5) & 0x3f) * 255 / 63;
            uint32_t b = (p & 0x1f) * 255 / 31;
            uint32_t pix = (r << 16) | (g << 8) | b;
            if (dst[x] != pix) {
                dst[x] = pix;
                row_changed = true;
            }
        }
        if (row_changed) {
            if (y_first < 0) {
                y_first = y;
            }
            y_last = y;
        }
    }

    s->need_update = false;

    if (y_first >= 0) {
        qemu_console_update(s->con, 0, y_first, s->width, y_last - y_first + 1);
    }

    return true;
}

static void goldfish_fb_invalidate(void *opaque)
{
    GoldfishFbState *s = opaque;
    s->need_update = true;
}

static const GraphicHwOps goldfish_fb_gfx_ops = {
    .invalidate = goldfish_fb_invalidate,
    .gfx_update = goldfish_fb_gfx_update,
};

static uint64_t goldfish_fb_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishFbState *s = opaque;

    switch (offset) {
    case FB_GET_WIDTH:
        return (uint32_t)s->width;
    case FB_GET_HEIGHT:
        return (uint32_t)s->height;
    case FB_INT_STATUS: {
        uint32_t ret = s->int_status & s->int_enable;
        if (ret) {
            s->int_status &= ~ret;
            goldfish_fb_update_irq(s);
        }
        return ret;
    }
    case FB_GET_PHYS_WIDTH:
        return (uint32_t)s->phys_width_mm;
    case FB_GET_PHYS_HEIGHT:
        return (uint32_t)s->phys_height_mm;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_fb_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_fb_write(void *opaque, hwaddr offset,
                              uint64_t val, unsigned size)
{
    GoldfishFbState *s = opaque;

    switch (offset) {
    case FB_INT_ENABLE:
        s->int_enable = (uint32_t)val;
        goldfish_fb_update_irq(s);
        break;
    case FB_SET_BASE:
        s->fb_base = (uint32_t)val;
        s->int_status &= ~FB_INT_BASE_UPDATE_DONE;
        s->need_update = true;
        s->need_int = true;
        s->base_valid = true;
        s->rotation = s->set_rotation;
        goldfish_fb_update_irq(s);
        /* Ensure BASE_UPDATE_DONE completes within 16 ms even if no VNC/GUI
         * client is connected yet (prevents SurfaceFlinger boot stalls). */
        if (!timer_pending(s->update_timer)) {
            timer_mod_ns(s->update_timer,
                         qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL) + 16000000LL);
        }
        break;
    case FB_SET_ROTATION:
        s->set_rotation = (uint32_t)(val & 3);
        break;
    case FB_SET_BLANK:
        s->blank = (val != 0);
        s->need_update = true;
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_fb_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        break;
    }
}

static const MemoryRegionOps goldfish_fb_ops = {
    .read = goldfish_fb_read,
    .write = goldfish_fb_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

void goldfish_fb_create(GoldfishBus *bus, int id, int width, int height)
{
    GoldfishFbState *s = g_new0(GoldfishFbState, 1);

    s->bus = bus;
    s->width = width > 0 ? width : 320;
    s->height = height > 0 ? height : 480;
    /* 160 dpi physical size in millimeters */
    s->phys_width_mm = (s->width * 254 + 800) / 1600;
    s->phys_height_mm = (s->height * 254 + 800) / 1600;
    s->raw_buf = g_malloc0((size_t)s->width * (size_t)s->height * 2);

    s->dev.name = "goldfish_fb";
    s->dev.id = id;
    s->dev.size = 0x1000;
    s->dev.irq_count = 1;

    s->update_timer = timer_new_ns(QEMU_CLOCK_VIRTUAL, goldfish_fb_timer_cb, s);
    s->con = qemu_graphic_console_create(NULL, 0, &goldfish_fb_gfx_ops, s);
    qemu_console_resize(s->con, s->width, s->height);

    goldfish_bus_add_device(bus, &s->dev, &goldfish_fb_ops, s);
}
