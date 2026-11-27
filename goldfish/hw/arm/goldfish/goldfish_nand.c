/*
 * Android Goldfish MTD NAND flash controller (YAFFS2 2048+64B OOB).
 * Ported from AOSP external/qemu (donut-release2) to QEMU 11.
 */

#include "qemu/osdep.h"
#include "qemu/error-report.h"
#include "qemu/log.h"
#include "goldfish_device.h"

enum {
    NAND_CMD_GET_DEV_NAME  = 0,
    NAND_CMD_READ          = 1,
    NAND_CMD_WRITE         = 2,
    NAND_CMD_ERASE         = 3,
    NAND_CMD_BLOCK_BAD_GET = 4,
    NAND_CMD_BLOCK_BAD_SET = 5,
};

enum {
    NAND_DEV_FLAG_READ_ONLY = 0x00000001,
};

#define NAND_VERSION_CURRENT 1

enum {
    NAND_VERSION        = 0x000,
    NAND_NUM_DEV        = 0x004,
    NAND_DEV            = 0x008,

    NAND_DEV_FLAGS      = 0x010,
    NAND_DEV_NAME_LEN   = 0x014,
    NAND_DEV_PAGE_SIZE  = 0x018,
    NAND_DEV_EXTRA_SIZE = 0x01c,
    NAND_DEV_ERASE_SIZE = 0x020,
    NAND_DEV_SIZE_LOW   = 0x028,
    NAND_DEV_SIZE_HIGH  = 0x02c,

    NAND_RESULT         = 0x040,
    NAND_COMMAND        = 0x044,
    NAND_DATA           = 0x048,
    NAND_TRANSFER_SIZE  = 0x04c,
    NAND_ADDR_LOW       = 0x050,
    NAND_ADDR_HIGH      = 0x054,
};

typedef struct GoldfishNandPart {
    char *name;
    size_t name_len;
    uint8_t *buf;
    int fd;
    uint32_t flags;
    uint32_t page_size;
    uint32_t extra_size;
    uint32_t erase_size;
    uint64_t size;
} GoldfishNandPart;

typedef struct GoldfishNandState {
    GoldfishDevice dev;
    GoldfishNandPart parts[4];
    uint32_t part_count;

    uint32_t cur_dev;
    uint32_t addr_low;
    uint32_t addr_high;
    uint32_t transfer_size;
    uint32_t data_vaddr;
    uint32_t result;
} GoldfishNandState;

static ssize_t pread_all(int fd, void *buf, size_t size, off_t offset)
{
    uint8_t *p = buf;
    size_t done = 0;
    while (done < size) {
        ssize_t r = pread(fd, p + done, size - done, offset + (off_t)done);
        if (r < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (r == 0) {
            break;
        }
        done += (size_t)r;
    }
    return (ssize_t)done;
}

static ssize_t pwrite_all(int fd, const void *buf, size_t size, off_t offset)
{
    const uint8_t *p = buf;
    size_t done = 0;
    while (done < size) {
        ssize_t r = pwrite(fd, p + done, size - done, offset + (off_t)done);
        if (r < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        done += (size_t)r;
    }
    return (ssize_t)done;
}

static uint32_t nand_read_file(GoldfishNandPart *p, uint32_t vaddr,
                               uint64_t addr, uint32_t total_len)
{
    uint32_t rem = total_len;
    uint64_t off = addr;

    while (rem > 0) {
        uint32_t chunk = rem > p->erase_size ? p->erase_size : rem;
        ssize_t got = pread_all(p->fd, p->buf, chunk, (off_t)off);
        if (got < 0) {
            memset(p->buf, 0xff, chunk);
        } else if ((uint32_t)got < chunk) {
            /* Unwritten / erased NAND reads as 0xff */
            memset(p->buf + got, 0xff, chunk - (uint32_t)got);
        }
        goldfish_vmem_write(vaddr, p->buf, chunk);
        vaddr += chunk;
        off += chunk;
        rem -= chunk;
    }
    return total_len;
}

static uint32_t nand_write_file(GoldfishNandPart *p, uint32_t vaddr,
                                uint64_t addr, uint32_t total_len)
{
    uint32_t rem = total_len;
    uint64_t off = addr;

    while (rem > 0) {
        uint32_t chunk = rem > p->erase_size ? p->erase_size : rem;
        goldfish_vmem_read(vaddr, p->buf, chunk);
        if (pwrite_all(p->fd, p->buf, chunk, (off_t)off) < 0) {
            break;
        }
        vaddr += chunk;
        off += chunk;
        rem -= chunk;
    }
    return total_len - rem;
}

static uint32_t nand_erase_file(GoldfishNandPart *p, uint64_t addr,
                                uint32_t total_len)
{
    uint32_t rem = total_len;
    uint64_t off = addr;

    memset(p->buf, 0xff, p->erase_size);
    while (rem > 0) {
        uint32_t chunk = rem > p->erase_size ? p->erase_size : rem;
        if (pwrite_all(p->fd, p->buf, chunk, (off_t)off) < 0) {
            break;
        }
        off += chunk;
        rem -= chunk;
    }
    return total_len - rem;
}

static uint32_t nand_do_cmd(GoldfishNandState *s, uint32_t cmd)
{
    uint64_t addr = s->addr_low | ((uint64_t)s->addr_high << 32);
    uint32_t size = s->transfer_size;
    GoldfishNandPart *p;

    if (s->cur_dev >= s->part_count) {
        return 0;
    }
    p = &s->parts[s->cur_dev];

    switch (cmd) {
    case NAND_CMD_GET_DEV_NAME:
        if (size > p->name_len) {
            size = (uint32_t)p->name_len;
        }
        goldfish_vmem_write(s->data_vaddr, p->name, size);
        return size;

    case NAND_CMD_READ:
        if (addr >= p->size) {
            return 0;
        }
        if (addr + size > p->size) {
            size = (uint32_t)(p->size - addr);
        }
        return nand_read_file(p, s->data_vaddr, addr, size);

    case NAND_CMD_WRITE:
        if (p->flags & NAND_DEV_FLAG_READ_ONLY) {
            return 0;
        }
        if (addr >= p->size) {
            return 0;
        }
        if (addr + size > p->size) {
            size = (uint32_t)(p->size - addr);
        }
        return nand_write_file(p, s->data_vaddr, addr, size);

    case NAND_CMD_ERASE:
        if (p->flags & NAND_DEV_FLAG_READ_ONLY) {
            return 0;
        }
        if (addr >= p->size) {
            return 0;
        }
        if (addr + size > p->size) {
            size = (uint32_t)(p->size - addr);
        }
        return nand_erase_file(p, addr, size);

    case NAND_CMD_BLOCK_BAD_GET:
    case NAND_CMD_BLOCK_BAD_SET:
        return 0;

    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_nand: bad cmd 0x%x\n", cmd);
        return 0;
    }
}

static uint64_t goldfish_nand_read(void *opaque, hwaddr offset, unsigned size)
{
    GoldfishNandState *s = opaque;
    GoldfishNandPart *p;

    switch (offset) {
    case NAND_VERSION:
        return NAND_VERSION_CURRENT;
    case NAND_NUM_DEV:
        return s->part_count;
    case NAND_RESULT:
        return s->result;
    }

    if (s->cur_dev >= s->part_count) {
        return 0;
    }
    p = &s->parts[s->cur_dev];

    switch (offset) {
    case NAND_DEV_FLAGS:
        return p->flags;
    case NAND_DEV_NAME_LEN:
        return (uint32_t)p->name_len;
    case NAND_DEV_PAGE_SIZE:
        return p->page_size;
    case NAND_DEV_EXTRA_SIZE:
        return p->extra_size;
    case NAND_DEV_ERASE_SIZE:
        return p->erase_size;
    case NAND_DEV_SIZE_LOW:
        return (uint32_t)p->size;
    case NAND_DEV_SIZE_HIGH:
        return (uint32_t)(p->size >> 32);
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_nand_read: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        return 0;
    }
}

static void goldfish_nand_write(void *opaque, hwaddr offset,
                                uint64_t value, unsigned size)
{
    GoldfishNandState *s = opaque;

    switch (offset) {
    case NAND_DEV:
        s->cur_dev = (uint32_t)value;
        break;
    case NAND_ADDR_HIGH:
        s->addr_high = (uint32_t)value;
        break;
    case NAND_ADDR_LOW:
        s->addr_low = (uint32_t)value;
        break;
    case NAND_TRANSFER_SIZE:
        s->transfer_size = (uint32_t)value;
        break;
    case NAND_DATA:
        s->data_vaddr = (uint32_t)value;
        break;
    case NAND_COMMAND:
        s->result = nand_do_cmd(s, (uint32_t)value);
        break;
    default:
        qemu_log_mask(LOG_GUEST_ERROR,
                      "goldfish_nand_write: bad offset 0x%" HWADDR_PRIx "\n",
                      offset);
        break;
    }
}

static const MemoryRegionOps goldfish_nand_ops = {
    .read = goldfish_nand_read,
    .write = goldfish_nand_write,
    .endianness = DEVICE_NATIVE_ENDIAN,
};

static void nand_add_partition(GoldfishNandState *s, const char *name,
                               const char *path, uint64_t min_size,
                               bool copy_to_tmp)
{
    GoldfishNandPart *p = &s->parts[s->part_count];
    uint32_t page_size = 2048;
    uint32_t extra_size = 64;
    uint32_t erase_pages = 64;
    uint32_t erase_size = erase_pages * (page_size + extra_size);
    uint64_t dev_size = min_size;
    int fd = -1;

    p->name = g_strdup(name);
    p->name_len = strlen(name);
    p->page_size = page_size;
    p->extra_size = extra_size;
    p->erase_size = erase_size;
    p->flags = 0;
    p->buf = g_malloc(erase_size);

    if (copy_to_tmp) {
        /* Copy pristine system.img to an unlinked temp file so YAFFS2 rw
         * checkpoint mount succeeds without modifying the original image. */
        GError *gerr = NULL;
        char *tmpname = NULL;
        fd = g_file_open_tmp("goldfish-nand-XXXXXX", &tmpname, &gerr);
        if (fd < 0) {
            error_report("goldfish_nand: failed to create temp file for %s: %s",
                         name, gerr ? gerr->message : strerror(errno));
            exit(1);
        }
        unlink(tmpname);
        g_free(tmpname);

        if (path && path[0]) {
            int src = open(path, O_RDONLY);
            if (src < 0) {
                error_report("goldfish_nand: cannot open %s (%s): %s",
                             name, path, strerror(errno));
                exit(1);
            }
            struct stat st;
            if (fstat(src, &st) == 0 && (uint64_t)st.st_size > dev_size) {
                dev_size = (uint64_t)st.st_size;
            }
            off_t off = 0;
            for (;;) {
                ssize_t r = read(src, p->buf, erase_size);
                if (r < 0) {
                    if (errno == EINTR) {
                        continue;
                    }
                    error_report("goldfish_nand: read failed on %s: %s",
                                 path, strerror(errno));
                    exit(1);
                }
                if (r == 0) {
                    break;
                }
                if (pwrite_all(fd, p->buf, (size_t)r, off) < 0) {
                    error_report("goldfish_nand: write tmp failed for %s: %s",
                                 name, strerror(errno));
                    exit(1);
                }
                off += r;
            }
            close(src);
        }
    } else if (path && path[0]) {
        fd = open(path, O_RDWR | O_CREAT, 0644);
        if (fd < 0) {
            error_report("goldfish_nand: cannot open %s (%s): %s",
                         name, path, strerror(errno));
            exit(1);
        }
        struct stat st;
        if (fstat(fd, &st) == 0 && (uint64_t)st.st_size > dev_size) {
            dev_size = (uint64_t)st.st_size;
        }
    } else {
        GError *gerr = NULL;
        char *tmpname = NULL;
        fd = g_file_open_tmp("goldfish-nand-XXXXXX", &tmpname, &gerr);
        if (fd < 0) {
            error_report("goldfish_nand: failed to create temp file for %s",
                         name);
            exit(1);
        }
        unlink(tmpname);
        g_free(tmpname);
    }

    if (dev_size % erase_size != 0) {
        dev_size += erase_size - (dev_size % erase_size);
    }
    p->size = dev_size;
    p->fd = fd;
    s->part_count++;
}

void goldfish_nand_init(GoldfishBus *bus, const char *system_path,
                        const char *userdata_path, const char *cache_path)
{
    GoldfishNandState *s = g_new0(GoldfishNandState, 1);

    if (system_path && system_path[0]) {
        nand_add_partition(s, "system", system_path,
                           72ULL * 1024 * 1024, true);
    }
    nand_add_partition(s, "userdata", userdata_path,
                       128ULL * 1024 * 1024, false);
    nand_add_partition(s, "cache", cache_path,
                       64ULL * 1024 * 1024, false);

    s->dev.name = "goldfish_nand";
    s->dev.id = 0;
    s->dev.size = 0x1000;
    s->dev.irq_count = 0;

    goldfish_bus_add_device(bus, &s->dev, &goldfish_nand_ops, s);
}
