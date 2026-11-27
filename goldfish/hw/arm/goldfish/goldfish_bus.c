/*
 * Android Goldfish platform device bus & interrupt controller.
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qemu/log.h"
#include "hw/core/cpu.h"
#include "system/address-spaces.h"
#include "goldfish_device.h"

/* ---------------- Guest virtual memory helpers ---------------- */

int goldfish_vmem_read(uint32_t vaddr_guest, void *buf, size_t len)
{
    CPUState *cpu = current_cpu ? current_cpu : first_cpu;
    if (!cpu || len == 0) {
        return 0;
    }
    return cpu_memory_rw_debug(cpu, (vaddr)vaddr_guest, buf, len, false);
}

int goldfish_vmem_write(uint32_t vaddr_guest, const void *buf, size_t len)
{
    CPUState *cpu = current_cpu ? current_cpu : first_cpu;
    if (!cpu || len == 0) {
        return 0;
    }
    return cpu_memory_rw_debug(cpu, (vaddr)vaddr_guest, (void *)buf, len, true);
}

/* ---------------- Goldfish Platform Device Bus ---------------- */

enum {
    PDEV_BUS_OP_DONE        = 0x00,
    PDEV_BUS_OP_REMOVE_DEV  = 0x04,
    PDEV_BUS_OP_ADD_DEV     = 0x08,

    PDEV_BUS_OP_INIT        = 0x00,

    PDEV_BUS_OP             = 0x00,
    PDEV_BUS_GET_NAME       = 0x04,
    PDEV_BUS_NAME_LEN       = 0x08,
    PDEV_BUS_ID             = 0x0c,
    PDEV_BUS_IO_BASE        = 0x10,
    PDEV_BUS_IO_SIZE        = 0x14,
    PDEV_BUS_IRQ            = 0x18,
    PDEV_BUS_IRQ_COUNT      = 0x1c,
};

struct GoldfishBus {
    GoldfishDevice bus_dev;
    GoldfishDevice *first_device;
    GoldfishDevice *last_device;
    GoldfishDevice *current;
    qemu_irq *pic;
    uint32_t free_base;
    uint32_t free_irq;
};

void goldfish_device_set_irq(GoldfishBus *bus, GoldfishDevice *dev,
                             int irq, int level)
{
    if (!bus || !bus->pic) {
        return;
    }
    if (irq < 0 || (uint32_t)irq >= dev->irq_count) {
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_device_set_irq: bad irq %d >= %u on %s\n",
                      irq, dev->irq_count, dev->name);
        return;
    }
    qemu_set_irq(bus->pic[dev->irq + irq], level ? 1 : 0);
}

int goldfish_bus_add_device_no_io(GoldfishBus *bus, GoldfishDevice *dev)
{
    if (dev->base == 0) {
        dev->base = bus->free_base;
        bus->free_base += dev->size;
    }
    if (dev->irq == 0 && dev->irq_count > 0) {
        dev->irq = bus->free_irq;
        bus->free_irq += dev->irq_count;
    }
    dev->next = NULL;
    if (bus->last_device) {
        bus->last_device->next = dev;
    } else {
        bus->first_device = dev;
    }
    bus->last_device = dev;
    return 0;
}

int goldfish_bus_add_device(GoldfishBus *bus, GoldfishDevice *dev,
                            const MemoryRegionOps *ops, void *opaque)
{
    goldfish_bus_add_device_no_io(bus, dev);
    memory_region_init_io(&dev->iomem, NULL, ops, opaque,
                          dev->name, dev->size);
    memory_region_add_subregion(get_system_memory(), dev->base, &dev->iomem);
    return 0;
}

static uint64_t goldfish_bus_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishBus *bus = opaque;

    switch (offset) {
    case PDEV_BUS_OP:
        if (bus->current) {
            bus->current->reported_state = 1;
            bus->current = bus->current->next;
        } else {
            bus->current = bus->first_device;
        }
        while (bus->current && bus->current->reported_state == 1) {
            bus->current = bus->current->next;
        }
        if (bus->current) {
            return PDEV_BUS_OP_ADD_DEV;
        } else {
            goldfish_device_set_irq(bus, &bus->bus_dev, 0, 0);
            return PDEV_BUS_OP_DONE;
        }
    case PDEV_BUS_NAME_LEN:
        return bus->current ? strlen(bus->current->name) : 0;
    case PDEV_BUS_ID:
        return bus->current ? (uint32_t)bus->current->id : 0;
    case PDEV_BUS_IO_BASE:
        return bus->current ? bus->current->base : 0;
    case PDEV_BUS_IO_SIZE:
        return bus->current ? bus->current->size : 0;
    case PDEV_BUS_IRQ:
        return bus->current ? bus->current->irq : 0;
    case PDEV_BUS_IRQ_COUNT:
        return bus->current ? bus->current->irq_count : 0;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_bus_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_bus_op_init(GoldfishBus *bus)
{
    GoldfishDevice *dev = bus->first_device;
    while (dev) {
        dev->reported_state = 0;
        dev = dev->next;
    }
    bus->current = NULL;
    goldfish_device_set_irq(bus, &bus->bus_dev, 0, bus->first_device != NULL);
}

static void goldfish_bus_write(void *opaque, hwaddr offset,
                               uint64_t value, unsigned size)
{
    GoldfishBus *bus = opaque;

    switch (offset) {
    case PDEV_BUS_OP:
        if (value == PDEV_BUS_OP_INIT) {
            goldfish_bus_op_init(bus);
        } else {
            qemu_log_mask(LOG_GUEST_ERROR,
                          "goldfish_bus_write: bad OP 0x%" PRIx64 "\n", value);
        }
        break;
    case PDEV_BUS_GET_NAME:
        if (bus->current) {
            goldfish_vmem_write((uint32_t)value, bus->current->name,
                                strlen(bus->current->name));
        }
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_bus_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        break;
    }
}

static const MemoryRegionOps goldfish_bus_ops = {
    .read = goldfish_bus_read,
    .write = goldfish_bus_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

GoldfishBus *goldfish_bus_create(uint32_t free_base, uint32_t free_size,
                                 uint32_t free_irq, uint32_t free_irq_count)
{
    GoldfishBus *bus = g_new0(GoldfishBus, 1);
    (void)free_size;
    (void)free_irq_count;
    bus->free_base = free_base;
    bus->free_irq = free_irq;
    return bus;
}

void goldfish_bus_set_pic(GoldfishBus *bus, qemu_irq *pic)
{
    bus->pic = pic;
}

void goldfish_bus_init_mmio(GoldfishBus *bus, uint32_t base, uint32_t irq)
{
    bus->bus_dev.name = "goldfish_device_bus";
    bus->bus_dev.id = -1;
    bus->bus_dev.base = base;
    bus->bus_dev.size = 0x1000;
    bus->bus_dev.irq = irq;
    bus->bus_dev.irq_count = 1;
    goldfish_bus_add_device(bus, &bus->bus_dev, &goldfish_bus_ops, bus);
}

/* ---------------- Goldfish Interrupt Controller ---------------- */

enum {
    INTERRUPT_STATUS        = 0x00,
    INTERRUPT_NUMBER        = 0x04,
    INTERRUPT_DISABLE_ALL   = 0x08,
    INTERRUPT_DISABLE       = 0x0c,
    INTERRUPT_ENABLE        = 0x10,
};

typedef struct GoldfishIntState {
    GoldfishDevice dev;
    uint32_t level;
    uint32_t irq_enabled;
    uint32_t fiq_enabled;
    qemu_irq parent_irq;
    qemu_irq parent_fiq;
} GoldfishIntState;

static void goldfish_int_update(GoldfishIntState *s)
{
    qemu_set_irq(s->parent_irq, (s->level & s->irq_enabled) != 0);
    qemu_set_irq(s->parent_fiq, (s->level & s->fiq_enabled) != 0);
}

static void goldfish_int_set_irq(void *opaque, int irq, int level)
{
    GoldfishIntState *s = opaque;
    uint32_t mask = 1U << (irq & 31);

    if (level) {
        s->level |= mask;
    } else {
        s->level &= ~mask;
    }
    goldfish_int_update(s);
}

static uint64_t goldfish_int_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishIntState *s = opaque;
    uint32_t pending = s->level & s->irq_enabled;

    switch (offset) {
    case INTERRUPT_STATUS:
        return (uint32_t)__builtin_popcount(pending);
    case INTERRUPT_NUMBER:
        if (pending == 0) {
            return 0;
        }
        return (uint32_t)__builtin_ctz(pending);
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_int_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_int_write(void *opaque, hwaddr offset,
                               uint64_t value, unsigned size)
{
    GoldfishIntState *s = opaque;
    uint32_t mask = 1U << (value & 31);

    switch (offset) {
    case INTERRUPT_DISABLE_ALL:
        s->level = 0;
        break;
    case INTERRUPT_DISABLE:
        s->irq_enabled &= ~mask;
        break;
    case INTERRUPT_ENABLE:
        s->irq_enabled |= mask;
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_int_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return;
    }
    goldfish_int_update(s);
}

static const MemoryRegionOps goldfish_int_ops = {
    .read = goldfish_int_read,
    .write = goldfish_int_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

qemu_irq *goldfish_pic_init(GoldfishBus *bus, uint32_t base,
                            qemu_irq parent_irq, qemu_irq parent_fiq)
{
    GoldfishIntState *s = g_new0(GoldfishIntState, 1);
    qemu_irq *qi = qemu_allocate_irqs(goldfish_int_set_irq, s, 32);

    s->dev.name = "goldfish_interrupt_controller";
    s->dev.id = -1;
    s->dev.base = base;
    s->dev.size = 0x1000;
    s->parent_irq = parent_irq;
    s->parent_fiq = parent_fiq;

    goldfish_bus_add_device(bus, &s->dev, &goldfish_int_ops, s);
    return qi;
}
