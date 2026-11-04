# Configuration replay after disconnect

A legacy-fluid 26.2-client / 1.8-server connection logged `DISGUISED_CHAT` conversion failure at 2026-11-04 21:52:46 UTC: `readerIndex(5) + length(28) exceeds writerIndex(5)`. The stack passed through `ConfigurationState.sendQueuedPackets` while processing configuration completion.

A full 1.8-to-26.2 pipeline fixture reproduces exactly that signature with a queued 1.19.4 `ENTITY_EVENT` packet: id 0x1c and body `000000011c` (entity 1, status 28). Active replay correctly produces `22000000011c` for 26.2. If the connection is deactivated before replay, `Protocol1_20To1_20_2` skips its clientbound transform but later protocol stages still run. The old entity-event id is consequently interpreted as 1.20.2 disguised chat. `ConnectionManagerImpl.onDisconnect` sets the active flag false before deferring stored-object removal, so in-flight queue work can still hold this state. This reproduces the observed failure mechanism; the exact ordering of the production disconnect was not instrumented.

Stop the drained batch when translation is inactive or a disconnect is pending, including after an earlier send deactivates the connection. Release unsent buffers when stopping or when any send throws. Successfully sent entries retain their existing per-packet cleanup. Newly queued entries remain owned by the next batch.

Six regression tests exercise active replay through the full protocol path, deactivation before/during replay, pending disconnect including a stored join-game packet, send failure cleanup and reentrant queue additions. All 75 common and 5 Bukkit tests pass. Version is `5.12.2-arch.7`.

This addresses invalid replay during shutdown and buffer ownership. It does not establish a cause or cure for the historical mass player drops or all unexpected-pong warnings. An earlier queued resource-pack failure remains a separate observation until its exact payload and ordering are reproduced.
