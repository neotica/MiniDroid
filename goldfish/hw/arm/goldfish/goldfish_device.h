/*
 * Android Goldfish virtual platform (ARM) for modern QEMU.
 * Based on AOSP external/qemu (donut-release2), GPLv2.
 */

#ifndef HW_ARM_GOLDFISH_DEVICE_H
#define HW_ARM_GOLDFISH_DEVICE_H

#include "hw/core/irq.h"
#include "system/memory.h"
#include "chardev/char-fe.h"
#include "system/block-backend.h"

typedef struct GoldfishDevice {
    struct GoldfishDevice *next;
    uint32_t reported_state;
    const char *name;
    int32_t id;
    uint32_t base;      /* auto-allocated if 0 */
    uint32_t size;
    uint32_t irq;       /* auto-allocated if 0 and irq_count > 0 */
    uint32_t irq_count;
    MemoryRegion iomem;
} GoldfishDevice;

typedef struct GoldfishBus GoldfishBus;

/* Platform device bus & PIC */
qemu_irq *goldfish_pic_init(GoldfishBus *bus, uint32_t base,
                            qemu_irq parent_irq, qemu_irq parent_fiq);
GoldfishBus *goldfish_bus_create(uint32_t free_base, uint32_t free_size,
                                 uint32_t free_irq, uint32_t free_irq_count);
void goldfish_bus_set_pic(GoldfishBus *bus, qemu_irq *pic);
void goldfish_bus_init_mmio(GoldfishBus *bus, uint32_t base, uint32_t irq);

int goldfish_bus_add_device_no_io(GoldfishBus *bus, GoldfishDevice *dev);
int goldfish_bus_add_device(GoldfishBus *bus, GoldfishDevice *dev,
                            const MemoryRegionOps *ops, void *opaque);
void goldfish_device_set_irq(GoldfishBus *bus, GoldfishDevice *dev,
                             int irq, int level);

/* Guest virtual memory access helpers (equivalent to Donut vmemcpy/pmemcpy) */
int goldfish_vmem_read(uint32_t vaddr, void *buf, size_t len);
int goldfish_vmem_write(uint32_t vaddr, const void *buf, size_t len);

/* Individual Goldfish devices */
void goldfish_timer_and_rtc_init(GoldfishBus *bus, uint32_t timer_base,
                                 uint32_t timer_irq);
void goldfish_tty_create(GoldfishBus *bus, Chardev *chr, int id,
                         uint32_t base, uint32_t irq);
void goldfish_nand_init(GoldfishBus *bus, const char *system_path,
                        const char *userdata_path, const char *cache_path);
void goldfish_fb_create(GoldfishBus *bus, int id, int width, int height);
void goldfish_audio_create(GoldfishBus *bus, uint32_t base, int id);
void goldfish_events_create(GoldfishBus *bus, int width, int height);
void goldfish_mmc_create(GoldfishBus *bus, uint32_t base, int id,
                         const char *sdcard_path);
void goldfish_battery_create(GoldfishBus *bus);
void goldfish_memlog_create(GoldfishBus *bus, uint32_t base);

#endif /* HW_ARM_GOLDFISH_DEVICE_H */
