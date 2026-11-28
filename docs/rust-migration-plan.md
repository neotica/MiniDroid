# Draft: Incremental C → Rust Migration Plan for MiniDroid Goldfish Devices

> **Status:** Draft / Future exploration (no changes made to the working C devices yet).

## Scope

Upstream QEMU 11.1.3 (~2M lines of C) stays in C. Only MiniDroid's custom Goldfish virtual hardware in `goldfish/hw/arm/goldfish/` (**2,760 lines of C** across 10 files) is a candidate for incremental migration to Rust.

| File | Lines | Migration Fit | Notes |
| --- | ---: | --- | --- |
| `goldfish_mmc.c` | 377 | **High (Pilot)** | Self-contained SD/MMC command & CSD state machine + file I/O. |
| `goldfish_nand.c` | 432 | **High** | MTD/YAFFS2 command state machine + flash file I/O; benefits from bounds safety. |
| `goldfish_events.c` | 337 | **High** | Ring buffer & Linux `input_event` queue; thin C shim for `qemu_input_handler`. |
| `goldfish_battery.c` | 136 | **High** | Simple MMIO register state machine. |
| `goldfish_timer.c` | 172 | **Medium** | Simple state; needs thin C glue for `QEMUTimer`. |
| `goldfish_bus.c` | 324 | **Medium** | Platform bus & IRQ controller (`goldfish_pic`). |
| `goldfish_tty.c` | 186 | **Low** | Mostly `Chardev` callback plumbing. |
| `goldfish_audio.c` | 237 | **Low** | Mostly `Chardev` + DMA + timer plumbing. |
| `goldfish_fb.c` | 260 | **Keep C shim** | Tightly coupled to QEMU `DisplaySurface` / `GraphicConsole`. |
| `goldfish_android.c` | 238 | **Keep C shim** | QEMU `MachineClass` board registration & memory layout. |

## Recommended Architecture: `libgoldfish_rs.a` + Thin C FFI Glue

Instead of QEMU's experimental `--enable-rust` (which currently lacks `DisplaySurface` / `QemuInputHandler` bindings and requires `bindgen` + `libclang` cross-compile plumbing), build a standalone Cargo `staticlib` crate:

```text
goldfish-rs/
├── Cargo.toml            # crate-type = ["staticlib"], panic = "abort", lto = true
└── src/
    ├── lib.rs            # #[no_mangle] extern "C" entry points
    ├── mmc.rs            # Pilot: GoldfishMmcState + CSD/block I/O
    ├── nand.rs           # Phase 2: GoldfishNandState
    └── events.rs         # Phase 3: Event ring buffer
```

### Build Integration
1. Add Rust target once: `rustup target add aarch64-linux-android`
2. Configure `.cargo/config.toml` linker to NDK `aarch64-linux-android28-clang`.
3. Build static library before `scripts/build-android-qemu.sh`:
   ```bash
   cargo build --manifest-path goldfish-rs/Cargo.toml --target aarch64-linux-android --release
   ```
4. Link `libgoldfish_rs.a` into `libqemu.so` via `--extra-ldflags` in `scripts/build-android-qemu.sh`.
5. Keep a tiny C wrapper per migrated device (~40–60 lines) that registers `MemoryRegionOps` and forwards MMIO reads/writes + DMA callbacks (`address_space_read` / `address_space_write`) to `extern "C"` Rust functions.

## Suggested Milestones

1. **Milestone 1 (Pilot):** Migrate `goldfish_mmc.c` → `goldfish-rs/src/mmc.rs`. Verify `/sdcard` mount (`vold` + `dosfsck`) and read/write on macOS + Android `arm64-v8a` + GitHub Actions CI.
2. **Milestone 2:** Migrate `goldfish_nand.c` → `goldfish-rs/src/nand.rs`. Verify Android 1.6 boot, `/system` read, `/data` persistence, and APK install.
3. **Milestone 3:** Migrate `goldfish_events.c` → `goldfish-rs/src/events.rs`. Verify touch, soft keyboard, and hardware keys.
4. **Milestone 4 (Optional):** Implement future complex devices (such as `goldfish_pipe` for Android 4.x) directly in Rust from day one.

## Trade-Offs to Keep in Mind
- **Runtime performance:** Negligible change (QEMU's C TCG JIT accounts for ~99% of CPU time).
- **Binary size:** Adds ~300 KB–1 MB to `libqemu.so` with `opt-level = "z"`, `lto = true`, `panic = "abort"`.
- **Toolchain cost:** Adds `rustup` + `cargo` to local setup and GitHub Actions CI (cacheable alongside `libqemu.so`).
