//! Native protocol regression tests; run with `zig build test-mosh`.

test {
    _ = @import("vendor/mosh/shutdown_patch.zig");
}
