//! Checked source overlay for remote shutdown in pinned zig-mosh-client 7ddaa2df.
//! Generate a patched copy at build time; never modify the dependency cache.

const std = @import("std");

/// Stop only after the authenticated final state has been applied and its ACK
/// has actually been handed to the UDP socket, matching the reference client.
pub fn apply(allocator: std.mem.Allocator, source: []const u8) ![]u8 {
    const before =
        \\                    return error.CircuitBreakerTripped;
        \\                }
        \\            };
        \\        }
        \\
        \\        // Receive inbound datagrams (best-effort, non-blocking)
    ;
    const after =
        \\                    return error.CircuitBreakerTripped;
        \\                }
        \\                continue;
        \\            };
        \\            // UINT64_MAX is SSP's authenticated remote-shutdown state.
        \\            // Wait until its final ACK has been sent, not merely queued.
        \\            if (self.network_datagrams.items.len == 0 and
        \\                self.latest_received_state_num == std.math.maxInt(u64) and
        \\                self.last_ack_reported_num == std.math.maxInt(u64))
        \\            {
        \\                try self.stop();
        \\                return;
        \\            }
        \\        }
        \\
        \\        // Receive inbound datagrams (best-effort, non-blocking)
    ;
    if (std.mem.count(u8, source, before) != 1) return error.MoshShutdownPatchContextChanged;
    return std.mem.replaceOwned(u8, allocator, source, before, after);
}

test "remote shutdown is acknowledged on the wire before stopping" {
    const mosh = @import("mosh");
    const allocator = std.testing.allocator;
    var receiver: mosh.socket.MoshSocket = .{};
    defer receiver.close();
    try receiver.open(0);
    var client = try mosh.core.Client.init(allocator, .{
        .host = "127.0.0.1",
        .port = receiver.local_port,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    }, .{ .use_network_crypto = false, .ack_delay_ms = 0 });
    defer client.deinit();
    try client.start(0);
    try client.processInboundInstruction(.{ .old_num = 0, .new_num = std.math.maxInt(u64) }, 1);
    try client.maintenanceTick(2);
    try std.testing.expectEqual(mosh.types.SessionState.running, client.state);
    try client.pumpNetwork(2);
    try std.testing.expectEqual(mosh.types.SessionState.stopped, client.state);
    try std.testing.expectEqual(mosh.types.FailureCode.none, client.last_failure);
    var buffer: [2048]u8 = undefined;
    const received = (try receiver.receive(&buffer)) orelse return error.MissingShutdownAck;
    const body = buffer[mosh.wire.PacketEnvelope.bytes + mosh.wire.FragmentHeader.bytes .. received];
    const unpacked = try mosh.compress.decompressAuto(allocator, body);
    defer allocator.free(unpacked.data);
    const ack = try mosh.protolite.TransportInstruction.decode(unpacked.data);
    try std.testing.expectEqual(@as(?u64, std.math.maxInt(u64)), ack.ack_num);
}

test "ordinary server state stays running and network timeout remains a failure" {
    const mosh = @import("mosh");
    const allocator = std.testing.allocator;
    var receiver: mosh.socket.MoshSocket = .{};
    defer receiver.close();
    try receiver.open(0);
    var client = try mosh.core.Client.init(allocator, .{
        .host = "127.0.0.1",
        .port = receiver.local_port,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    }, .{ .use_network_crypto = false, .ack_delay_ms = 0 });
    defer client.deinit();
    try client.start(0);
    try client.processInboundInstruction(.{ .old_num = 0, .new_num = 1 }, 1);
    try client.maintenanceTick(2);
    try client.pumpNetwork(2);
    try std.testing.expectEqual(mosh.types.SessionState.running, client.state);
    try std.testing.expectError(error.NetworkTimeout, client.maintenanceTick(30_001));
    try std.testing.expectEqual(mosh.types.SessionState.failed, client.state);
    try std.testing.expectEqual(mosh.types.FailureCode.timeout, client.last_failure);
}
