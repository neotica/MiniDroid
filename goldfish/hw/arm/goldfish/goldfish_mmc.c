/*
 * Android Goldfish MMC/SD card controller.
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qemu/error-report.h"
#include "qemu/log.h"
#include "system/address-spaces.h"
#include "goldfish_device.h"

/* Standard MMC/SD command opcodes */
#define MMC_GO_IDLE_STATE         0
#define MMC_SEND_OP_COND          1
#define MMC_ALL_SEND_CID          2
#define MMC_SET_RELATIVE_ADDR     3
#define MMC_SWITCH                6
#define MMC_SELECT_CARD           7
#define MMC_SEND_EXT_CSD          8
#define MMC_SEND_CSD              9
#define MMC_SEND_CID              10
#define MMC_STOP_TRANSMISSION     12
#define MMC_SEND_STATUS           13
#define MMC_SET_BLOCKLEN          16
#define MMC_READ_SINGLE_BLOCK     17
#define MMC_READ_MULTIPLE_BLOCK   18
#define MMC_WRITE_BLOCK           24
#define MMC_WRITE_MULTIPLE_BLOCK  25
#define MMC_APP_CMD               55
#define SD_APP_OP_COND            41
#define SD_APP_SEND_SCR           51

#define R1_READY_FOR_DATA         (1U << 8)
#define R1_APP_CMD                (1U << 5)
#define SET_R1_CURRENT_STATE(st)  (((uint32_t)(st) << 9) & 0x00001E00U)

enum {
    MMC_INT_STATUS          = 0x00,
    MMC_INT_ENABLE          = 0x04,
    MMC_SET_BUFFER          = 0x08,
    MMC_CMD                 = 0x0c,
    MMC_ARG                 = 0x10,
    MMC_RESP_0              = 0x14,
    MMC_RESP_1              = 0x18,
    MMC_RESP_2              = 0x1c,
    MMC_RESP_3              = 0x20,
    MMC_BLOCK_LENGTH        = 0x24,
    MMC_BLOCK_COUNT         = 0x28,
    MMC_STATE               = 0x2c,

    MMC_STAT_END_OF_CMD     = 1U << 0,
    MMC_STAT_END_OF_DATA    = 1U << 1,
    MMC_STAT_STATE_CHANGE   = 1U << 2,

    MMC_STATE_INSERTED      = 1U << 0,
    MMC_STATE_READ_ONLY     = 1U << 1,
};

typedef struct GoldfishMmcState {
    GoldfishDevice dev;
    GoldfishBus *bus;
    int fd;
    uint64_t capacity;
    bool read_only;

    hwaddr buffer_phys;
    uint32_t int_status;
    uint32_t int_enable;
    uint32_t arg;
    uint32_t resp[4];
    uint32_t block_length;
    uint32_t block_count;
    bool is_sdhc;
} GoldfishMmcState;

static void mmc_pread_all(int fd, void *buf, size_t size, off_t offset)
{
    uint8_t *p = buf;
    size_t done = 0;
    while (done < size) {
        ssize_t r = pread(fd, p + done, size - done, offset + (off_t)done);
        if (r < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (r == 0) {
            break;
        }
        done += (size_t)r;
    }
    if (done < size) {
        memset(p + done, 0, size - done);
    }
}

static void mmc_pwrite_all(int fd, const void *buf, size_t size, off_t offset)
{
    const uint8_t *p = buf;
    size_t done = 0;
    while (done < size) {
        ssize_t r = pwrite(fd, p + done, size - done, offset + (off_t)done);
        if (r < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        done += (size_t)r;
    }
}

static void goldfish_mmc_update_irq(GoldfishMmcState *s)
{
    goldfish_device_set_irq(s->bus, &s->dev, 0,
                            (s->int_status & s->int_enable) != 0);
}

static void goldfish_mmc_do_command(GoldfishMmcState *s,
                                    uint32_t cmd, uint32_t arg)
{
    uint32_t new_status = MMC_STAT_END_OF_CMD;
    int opcode = cmd & 63;

    s->resp[0] = s->resp[1] = s->resp[2] = s->resp[3] = 0;

    if (s->fd < 0) {
        s->int_status |= new_status;
        goldfish_mmc_update_irq(s);
        return;
    }

    switch (opcode) {
    case MMC_SEND_CSD: {
        uint64_t capacity = s->capacity;
        if (capacity > 2147483648ULL) {
            s->is_sdhc = true;
            s->resp[3] = 0x400E0032U;
            s->resp[2] = 0x5B590000U;
            s->resp[1] = 0x00007F80U;
            s->resp[0] = 0x0A4040DFU;

            uint32_t m = (uint32_t)(capacity / (512 * 1024)) - 1;
            s->resp[1] |= ((m & 0x0000FFFFU) << 16);
            s->resp[2] |= (m >> 16);
        } else {
            s->is_sdhc = false;
            s->resp[3] = 0x00260032U;
            s->resp[2] = 0x5F5A8000U;
            s->resp[1] = 0x3EF84FFFU;
            s->resp[0] = 0x928040CBU;

            uint32_t exponent = 0;
            uint64_t cap_kb = capacity >> 10;
            while (cap_kb > 4096) {
                exponent++;
                cap_kb >>= 1;
            }
            if (cap_kb > 0) {
                cap_kb -= 1;
            }
            exponent = exponent >= 2 ? exponent - 2 : 0;
            if (exponent > 7) {
                exponent = 7;
            }

            s->resp[2] |= (((uint32_t)cap_kb >> 2) & 0x3FFU);
            s->resp[1] |= (((uint32_t)cap_kb & 3U) << 30);
            s->resp[1] |= (exponent << (47 - 32));
        }
        break;
    }
    case MMC_SEND_EXT_CSD:
        s->resp[0] = arg;
        break;
    case MMC_APP_CMD:
        s->resp[0] = SET_R1_CURRENT_STATE(4) | R1_READY_FOR_DATA | R1_APP_CMD;
        break;
    case SD_APP_OP_COND:
        s->resp[0] = 0x80FF8000U;
        break;
    case SD_APP_SEND_SCR: {
        uint32_t scr[2] = { 0x00002502U, 0x00000000U };
        address_space_write(&address_space_memory, s->buffer_phys,
                            MEMTXATTRS_UNSPECIFIED, scr, sizeof(scr));
        s->resp[0] = SET_R1_CURRENT_STATE(4) | R1_READY_FOR_DATA | R1_APP_CMD;
        new_status |= MMC_STAT_END_OF_DATA;
        break;
    }
    case MMC_SET_RELATIVE_ADDR:
        s->resp[0] = (uint32_t)(-518519520);
        break;
    case MMC_ALL_SEND_CID:
        s->resp[3] = 55788627U;
        s->resp[2] = 1429221959U;
        s->resp[1] = (uint32_t)(-2147479692);
        s->resp[0] = (uint32_t)(-436179883);
        break;
    case MMC_SELECT_CARD:
        s->resp[0] = SET_R1_CURRENT_STATE(3) | R1_READY_FOR_DATA;
        break;
    case MMC_SWITCH:
        if (arg == 0x00FFFFF1U || arg == 0x80FFFFF1U) {
            uint8_t switchbuf[64] = { 0 };
            switchbuf[13] = 2;
            address_space_write(&address_space_memory, s->buffer_phys,
                                MEMTXATTRS_UNSPECIFIED, switchbuf,
                                sizeof(switchbuf));
            new_status |= MMC_STAT_END_OF_DATA;
        }
        s->resp[0] = SET_R1_CURRENT_STATE(4) | R1_READY_FOR_DATA | R1_APP_CMD;
        break;
    case MMC_SET_BLOCKLEN:
        s->block_length = arg;
        s->resp[0] = SET_R1_CURRENT_STATE(4) | R1_READY_FOR_DATA;
        break;
    case MMC_READ_SINGLE_BLOCK:
        s->block_count = 1;
        /* fall through */
    case MMC_READ_MULTIPLE_BLOCK: {
        off_t byte_off = s->is_sdhc ? ((off_t)arg * 512) : (off_t)arg;
        size_t xfer = (size_t)s->block_count * 512;
        uint8_t *tmp = g_malloc(xfer);
        mmc_pread_all(s->fd, tmp, xfer, byte_off);
        address_space_write(&address_space_memory, s->buffer_phys,
                            MEMTXATTRS_UNSPECIFIED, tmp, xfer);
        g_free(tmp);
        new_status |= MMC_STAT_END_OF_DATA;
        s->resp[0] = SET_R1_CURRENT_STATE(4) | R1_READY_FOR_DATA;
        break;
    }
    case MMC_WRITE_BLOCK:
        s->block_count = 1;
        /* fall through */
    case MMC_WRITE_MULTIPLE_BLOCK: {
        if (!s->read_only) {
            off_t byte_off = s->is_sdhc ? ((off_t)arg * 512) : (off_t)arg;
            size_t xfer = (size_t)s->block_count * 512;
            uint8_t *tmp = g_malloc(xfer);
            address_space_read(&address_space_memory, s->buffer_phys,
                               MEMTXATTRS_UNSPECIFIED, tmp, xfer);
            mmc_pwrite_all(s->fd, tmp, xfer, byte_off);
            g_free(tmp);
        }
        new_status |= MMC_STAT_END_OF_DATA;
        s->resp[0] = SET_R1_CURRENT_STATE(4) | R1_READY_FOR_DATA;
        break;
    }
    case MMC_STOP_TRANSMISSION:
        s->resp[0] = SET_R1_CURRENT_STATE(5) | R1_READY_FOR_DATA;
        break;
    case MMC_SEND_STATUS:
        s->resp[0] = SET_R1_CURRENT_STATE(4) | R1_READY_FOR_DATA;
        break;
    default:
        break;
    }

    s->int_status |= new_status;
    goldfish_mmc_update_irq(s);
}

static uint64_t goldfish_mmc_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishMmcState *s = opaque;

    switch (offset) {
    case MMC_INT_STATUS:
        return s->int_status & s->int_enable;
    case MMC_RESP_0:
        return s->resp[0];
    case MMC_RESP_1:
        return s->resp[1];
    case MMC_RESP_2:
        return s->resp[2];
    case MMC_RESP_3:
        return s->resp[3];
    case MMC_STATE: {
        if (s->fd < 0) {
            return 0;
        }
        uint32_t ret = MMC_STATE_INSERTED;
        if (s->read_only) {
            ret |= MMC_STATE_READ_ONLY;
        }
        return ret;
    }
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_mmc_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_mmc_write(void *opaque, hwaddr offset,
                               uint64_t val, unsigned size)
{
    GoldfishMmcState *s = opaque;

    switch (offset) {
    case MMC_INT_STATUS:
        s->int_status &= ~(uint32_t)val;
        goldfish_mmc_update_irq(s);
        break;
    case MMC_INT_ENABLE:
        s->int_enable = (uint32_t)val;
        s->int_status = 0;
        goldfish_mmc_update_irq(s);
        break;
    case MMC_SET_BUFFER:
        s->buffer_phys = (hwaddr)val;
        break;
    case MMC_CMD:
        goldfish_mmc_do_command(s, (uint32_t)val, s->arg);
        break;
    case MMC_ARG:
        s->arg = (uint32_t)val;
        break;
    case MMC_BLOCK_LENGTH:
        s->block_length = (uint32_t)val + 1;
        break;
    case MMC_BLOCK_COUNT:
        s->block_count = (uint32_t)val + 1;
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_mmc_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        break;
    }
}

static const MemoryRegionOps goldfish_mmc_ops = {
    .read = goldfish_mmc_read,
    .write = goldfish_mmc_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

void goldfish_mmc_create(GoldfishBus *bus, uint32_t base, int id,
                         const char *sdcard_path)
{
    GoldfishMmcState *s = g_new0(GoldfishMmcState, 1);

    s->bus = bus;
    s->fd = -1;
    s->block_length = 512;
    s->block_count = 1;

    if (sdcard_path && sdcard_path[0]) {
        s->fd = open(sdcard_path, O_RDWR);
        if (s->fd < 0) {
            s->fd = open(sdcard_path, O_RDONLY);
            if (s->fd >= 0) {
                s->read_only = true;
            } else {
                error_report("goldfish_mmc: cannot open %s: %s",
                             sdcard_path, strerror(errno));
            }
        }
        if (s->fd >= 0) {
            struct stat st;
            if (fstat(s->fd, &st) == 0) {
                s->capacity = (uint64_t)st.st_size;
            }
        }
    }

    s->dev.name = "goldfish_mmc";
    s->dev.id = id;
    s->dev.base = base;
    s->dev.size = 0x1000;
    s->dev.irq_count = 1;

    goldfish_bus_add_device(bus, &s->dev, &goldfish_mmc_ops, s);
}
