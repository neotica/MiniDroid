/*
 * Android 1.6 (Donut) Goldfish ARM Machine (-M goldfish).
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qapi/error.h"
#include "qapi/visitor.h"
#include "qemu/error-report.h"
#include "qemu/units.h"
#include "hw/arm/boot.h"
#include "hw/arm/machines-qom.h"
#include "hw/core/boards.h"
#include "hw/core/irq.h"
#include "hw/core/sysbus.h"
#include "hw/net/smc91c111.h"
#include "net/net.h"
#include "qom/object.h"
#include "system/address-spaces.h"
#include "system/block-backend.h"
#include "system/blockdev.h"
#include "system/memory.h"
#include "system/system.h"
#include "target/arm/cpu-qom.h"
#include "goldfish_device.h"

#define TYPE_GOLDFISH_MACHINE MACHINE_TYPE_NAME("goldfish")
OBJECT_DECLARE_SIMPLE_TYPE(GoldfishMachineState, GOLDFISH_MACHINE)

struct GoldfishMachineState {
    MachineState parent_obj;

    char *system_img;
    char *userdata_img;
    char *cache_img;
    char *sdcard_img;
    uint32_t fb_width;
    uint32_t fb_height;
};

static struct arm_boot_info goldfish_binfo;

static void goldfish_machine_init_fn(MachineState *machine)
{
    GoldfishMachineState *gms = GOLDFISH_MACHINE(machine);
    Object *cpuobj;
    ARMCPU *cpu;
    MemoryRegion *sysmem = get_system_memory();
    GoldfishBus *bus;
    qemu_irq *pic;

    if (machine->ram_size > 256 * MiB) {
        error_report("goldfish: maximum supported RAM is 256 MiB");
        exit(1);
    }

    cpuobj = object_new(machine->cpu_type);
    qdev_realize(DEVICE(cpuobj), NULL, &error_fatal);
    cpu = ARM_CPU(cpuobj);

    memory_region_add_subregion(sysmem, 0, machine->ram);

    bus = goldfish_bus_create(0xff010000, 0x7f0000, 10, 22);
    pic = goldfish_pic_init(bus, 0xff000000,
                            qdev_get_gpio_in(DEVICE(cpu), ARM_CPU_IRQ),
                            qdev_get_gpio_in(DEVICE(cpu), ARM_CPU_FIQ));
    goldfish_bus_set_pic(bus, pic);

    goldfish_bus_init_mmio(bus, 0xff001000, 1);
    goldfish_timer_and_rtc_init(bus, 0xff003000, 3);

    goldfish_tty_create(bus, serial_hd(0), 0, 0xff002000, 4);
    for (int i = 1; i < 4; i++) {
        if (serial_hd(i)) {
            goldfish_tty_create(bus, serial_hd(i), i, 0, 0);
        }
    }

    /* Ethernet NIC (smc91c111 -> eth0 10.0.2.15 in Android 1.6) */
    if (qemu_find_nic_info("smc91c111", true, NULL)) {
        GoldfishDevice *smc_dev = g_new0(GoldfishDevice, 1);
        smc_dev->name = "smc91x";
        smc_dev->id = 0;
        smc_dev->size = 0x1000;
        smc_dev->irq_count = 1;
        goldfish_bus_add_device_no_io(bus, smc_dev);
        smc91c111_init(smc_dev->base, pic[smc_dev->irq]);
    }

    goldfish_fb_create(bus, 0, (int)gms->fb_width, (int)gms->fb_height);
    goldfish_audio_create(bus, 0xff004000, 0);

    /* SD card (-M goldfish,...,sdcard=sdcard.img) */
    if (gms->sdcard_img && gms->sdcard_img[0]) {
        goldfish_mmc_create(bus, 0xff005000, 0, gms->sdcard_img);
    }

    goldfish_memlog_create(bus, 0xff006000);
    goldfish_battery_create(bus);
    goldfish_events_create(bus, (int)gms->fb_width, (int)gms->fb_height);
    goldfish_nand_init(bus, gms->system_img, gms->userdata_img, gms->cache_img);

    memset(&goldfish_binfo, 0, sizeof(goldfish_binfo));
    goldfish_binfo.ram_size = machine->ram_size;
    goldfish_binfo.board_id = 1441; /* MACH_TYPE_GOLDFISH */
    goldfish_binfo.loader_start = 0;
    arm_load_kernel(cpu, machine, &goldfish_binfo);
}

static char *goldfish_get_system(Object *obj, Error **errp)
{
    return g_strdup(GOLDFISH_MACHINE(obj)->system_img);
}

static void goldfish_set_system(Object *obj, const char *val, Error **errp)
{
    GoldfishMachineState *gms = GOLDFISH_MACHINE(obj);
    g_free(gms->system_img);
    gms->system_img = g_strdup(val);
}

static char *goldfish_get_userdata(Object *obj, Error **errp)
{
    return g_strdup(GOLDFISH_MACHINE(obj)->userdata_img);
}

static void goldfish_set_userdata(Object *obj, const char *val, Error **errp)
{
    GoldfishMachineState *gms = GOLDFISH_MACHINE(obj);
    g_free(gms->userdata_img);
    gms->userdata_img = g_strdup(val);
}

static char *goldfish_get_cache(Object *obj, Error **errp)
{
    return g_strdup(GOLDFISH_MACHINE(obj)->cache_img);
}

static void goldfish_set_cache(Object *obj, const char *val, Error **errp)
{
    GoldfishMachineState *gms = GOLDFISH_MACHINE(obj);
    g_free(gms->cache_img);
    gms->cache_img = g_strdup(val);
}

static char *goldfish_get_sdcard(Object *obj, Error **errp)
{
    return g_strdup(GOLDFISH_MACHINE(obj)->sdcard_img);
}

static void goldfish_set_sdcard(Object *obj, const char *val, Error **errp)
{
    GoldfishMachineState *gms = GOLDFISH_MACHINE(obj);
    g_free(gms->sdcard_img);
    gms->sdcard_img = g_strdup(val);
}

static void goldfish_get_width(Object *obj, Visitor *v, const char *name,
                               void *opaque, Error **errp)
{
    uint32_t val = GOLDFISH_MACHINE(obj)->fb_width;
    visit_type_uint32(v, name, &val, errp);
}

static void goldfish_set_width(Object *obj, Visitor *v, const char *name,
                               void *opaque, Error **errp)
{
    uint32_t val;
    if (visit_type_uint32(v, name, &val, errp)) {
        GOLDFISH_MACHINE(obj)->fb_width = val;
    }
}

static void goldfish_get_height(Object *obj, Visitor *v, const char *name,
                                void *opaque, Error **errp)
{
    uint32_t val = GOLDFISH_MACHINE(obj)->fb_height;
    visit_type_uint32(v, name, &val, errp);
}

static void goldfish_set_height(Object *obj, Visitor *v, const char *name,
                                void *opaque, Error **errp)
{
    uint32_t val;
    if (visit_type_uint32(v, name, &val, errp)) {
        GOLDFISH_MACHINE(obj)->fb_height = val;
    }
}

static void goldfish_instance_init(Object *obj)
{
    GoldfishMachineState *gms = GOLDFISH_MACHINE(obj);
    gms->fb_width = 320;
    gms->fb_height = 480;
}

static void goldfish_class_init(ObjectClass *oc, const void *data)
{
    MachineClass *mc = MACHINE_CLASS(oc);

    mc->desc = "Android 1.6 Goldfish ARM Emulator (ARM926EJ-S)";
    mc->init = goldfish_machine_init_fn;
    mc->default_cpu_type = ARM_CPU_TYPE_NAME("arm926");
    mc->default_ram_size = 128 * MiB;
    mc->default_ram_id = "goldfish.ram";
    mc->ignore_memory_transaction_failures = true;

    object_class_property_add_str(oc, "system",
                                  goldfish_get_system, goldfish_set_system);
    object_class_property_add_str(oc, "userdata",
                                  goldfish_get_userdata, goldfish_set_userdata);
    object_class_property_add_str(oc, "cache",
                                  goldfish_get_cache, goldfish_set_cache);
    object_class_property_add_str(oc, "sdcard",
                                  goldfish_get_sdcard, goldfish_set_sdcard);
    object_class_property_add(oc, "width", "uint32",
                              goldfish_get_width, goldfish_set_width,
                              NULL, NULL);
    object_class_property_add(oc, "height", "uint32",
                              goldfish_get_height, goldfish_set_height,
                              NULL, NULL);
}

static const TypeInfo goldfish_machine_type = {
    .name = TYPE_GOLDFISH_MACHINE,
    .parent = TYPE_MACHINE,
    .instance_size = sizeof(GoldfishMachineState),
    .instance_init = goldfish_instance_init,
    .class_init = goldfish_class_init,
    .interfaces = arm_machine_interfaces,
};

static void goldfish_machine_register_types(void)
{
    type_register_static(&goldfish_machine_type);
}

type_init(goldfish_machine_register_types)
