/*
 * This file is part of ViaVersion - https://github.com/ViaVersion/ViaVersion
 * Copyright (C) 2016-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.viaversion.protocol.packet;

import com.google.common.base.Preconditions;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.TypeConverter;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.CancelException;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.util.ArrayUtil;
import com.viaversion.viaversion.util.PipelineUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.Nullable;

public class PacketWrapperImpl implements PacketWrapper {
    private final FieldBuffer packetValues = new FieldBuffer();
    private final ByteBuf inputBuffer;
    private final UserConnection userConnection;
    private boolean send = true;
    /**
     * Only non-null if specifically set and gotten before packet transformation
     */
    private PacketType packetType;
    /**
     * Makes passthrough act as a read, and write no-ops. Only use this if really necessary for avoiding large amounts of code duplication.
     */
    private boolean allActionsRead;
    private int id;

    public PacketWrapperImpl(int packetId, @Nullable ByteBuf inputBuffer, UserConnection userConnection) {
        this.id = packetId;
        this.inputBuffer = inputBuffer;
        this.userConnection = userConnection;
    }

    public PacketWrapperImpl(@Nullable PacketType packetType, @Nullable ByteBuf inputBuffer, UserConnection userConnection) {
        this.packetType = packetType;
        this.id = packetType != null ? packetType.getId() : -1;
        this.inputBuffer = inputBuffer;
        this.userConnection = userConnection;
    }

    @Override
    public <T> T get(Type<T> type, int index) throws InformativeException {
        int currentIndex = 0;
        for (int field = 0; field < packetValues.size(); field++) {
            Type<?> fieldType = packetValues.type(field);
            if (fieldType != type) {
                continue;
            }
            if (currentIndex == index) {
                //noinspection unchecked
                return (T) packetValues.value(field);
            }
            currentIndex++;
        }
        throw createInformativeException(new ArrayIndexOutOfBoundsException("Could not find type " + type.getTypeName() + " at " + index), type, index);
    }

    @Override
    public boolean is(Type type, int index) {
        int currentIndex = 0;
        for (int field = 0; field < packetValues.size(); field++) {
            Type<?> fieldType = packetValues.type(field);
            if (fieldType != type) {
                continue;
            }
            if (currentIndex == index) {
                return true;
            }
            currentIndex++;
        }
        return false;
    }

    @Override
    public boolean isReadable(Type type, int index) {
        int currentIndex = 0;
        for (int i = 0; i < packetValues.readableSize(); i++) {
            if (packetValues.readableType(i).getBaseClass() != type.getBaseClass()) {
                continue;
            }
            if (currentIndex == index) {
                return true;
            }
            currentIndex++;
        }
        return false;
    }


    @Override
    public <T> void set(Type<T> type, int index, @Nullable T value) throws InformativeException {
        int currentIndex = 0;
        for (int field = 0; field < packetValues.size(); field++) {
            Type<?> fieldType = packetValues.type(field);
            if (fieldType != type) {
                continue;
            }
            if (currentIndex == index) {
                packetValues.setValue(field, value);
                return;
            }
            currentIndex++;
        }
        throw createInformativeException(new ArrayIndexOutOfBoundsException("Could not find type " + type.getTypeName() + " at " + index), type, index);
    }

    @Override
    public <T> T read(Type<T> type) {
        return packetValues.readableSize() == 0 ? readFromBuffer(type) : pollReadableObject(type);
    }

    private <T> T readFromBuffer(Type<T> type) {
        Preconditions.checkNotNull(inputBuffer, "This packet does not have an input buffer.");
        try {
            return type.read(inputBuffer);
        } catch (Exception e) {
            throw createInformativeException(e, type, packetValues.size() + 1);
        }
    }

    private <T> T pollReadableObject(Type<T> type) {
        Type<?> readType = packetValues.readableType(0);
        Object value = packetValues.readableValue();
        packetValues.discardReadable();
        if (readType == type
            || (type.getBaseClass() == readType.getBaseClass()
            && type.getOutputClass() == readType.getOutputClass())) {
            //noinspection unchecked
            return (T) value;
        }
        throw createInformativeException(new IOException("Unable to read type " + type.getTypeName() + ", found " + readType.getTypeName()), type, packetValues.readableSize());
    }

    @Override
    public <T> void write(Type<T> type, T value) {
        if (!allActionsRead) {
            packetValues.add(type, value);
        }
    }

    /**
     * Returns the value if already matching, else the converted value or possibly unmatched value.
     *
     * @param expectedType expected type
     * @param value        value
     * @return value if already matching, else the converted value or possibly unmatched value
     */
    private <T> @Nullable T attemptTransform(Type<T> expectedType, @Nullable Object value) {
        if (value != null && !expectedType.getOutputClass().isAssignableFrom(value.getClass())) {
            // Attempt conversion
            if (expectedType instanceof TypeConverter<?>) {
                //noinspection unchecked
                return ((TypeConverter<T>) expectedType).from(value);
            }

            Via.getPlatform().getLogger().warning("Possible type mismatch: " + value.getClass().getName() + " -> " + expectedType.getOutputClass());
        }
        //noinspection unchecked
        return (T) value;
    }

    @Override
    public <T> T passthrough(Type<T> type) throws InformativeException {
        Type<?> storedType = packetValues.readableSize() == 0 ? type : packetValues.readableType(0);
        T value = read(type);
        if (!allActionsRead) {
            packetValues.add(storedType, value);
        }
        return value;
    }

    @Override
    public <T> T passthroughAndMap(Type<?> type, Type<T> mappedType) throws InformativeException {
        if (type == mappedType) {
            return passthrough(mappedType);
        }

        final Object value = read(type);
        final T mappedValue = attemptTransform(mappedType, value);
        write(mappedType, mappedValue);
        return mappedValue;
    }

    @Override
    public void passthroughAll() throws InformativeException {
        // Copy previous objects
        appendUnreadValues();
        // If the buffer has readable bytes, copy them.
        if (inputBuffer.isReadable()) {
            passthrough(Types.REMAINING_BYTES);
        }
    }

    @Override
    public void writeToBuffer(ByteBuf buffer) throws InformativeException {
        writeProcessedValues(buffer);
        if (inputBuffer != null) {
            buffer.writeBytes(inputBuffer);
        }
    }

    public boolean areStoredPacketValuesEmpty() {
        // Check for read/added packet values, not the input buffer
        return packetValues.isEmpty() && packetValues.readableSize() == 0;
    }

    public void writeProcessedValues(ByteBuf buffer) throws InformativeException {
        if (id != -1) {
            Types.VAR_INT.writePrimitive(buffer, id);
        }
        if (packetValues.readableSize() != 0) {
            appendUnreadValues();
        }

        for (int i = 0; i < packetValues.size(); i++) {
            Type<?> fieldType = packetValues.type(i);
            try {
                writeField(fieldType, buffer, packetValues.value(i));
            } catch (final Exception e) {
                throw createInformativeException(e, fieldType, i);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeField(Type type, ByteBuf buffer, Object value) throws Exception {
        type.write(buffer, value);
    }

    private InformativeException createInformativeException(final Exception cause, final Type<?> type, final int index) {
        final ProtocolInfo protocolInfo = this.user().getProtocolInfo();
        return new InformativeException(cause)
            .set("Packet Type", this.packetType)
            .set("Client Protocol", protocolInfo.protocolVersion().getName())
            .set("Server Protocol", protocolInfo.serverProtocolVersion().getName())
            .set("Index", index)
            .set("Type", type.getTypeName())
            .set("Data", new PacketValuesSummary(this.packetValues))
            .set("Packet ID", this.id);
    }

    @Override
    public void clearInputBuffer() {
        if (inputBuffer != null) {
            inputBuffer.clear();
        }
        packetValues.clearReadable();
    }

    @Override
    public void clearPacket() {
        clearInputBuffer();
        packetValues.clear();
    }

    @Override
    public void send(Class<? extends Protocol> protocol, boolean skipCurrentPipeline) throws InformativeException {
        send0(protocol, skipCurrentPipeline, true);
    }

    @Override
    public void scheduleSend(Class<? extends Protocol> protocol, boolean skipCurrentPipeline) throws InformativeException {
        send0(protocol, skipCurrentPipeline, false);
    }

    private void send0(Class<? extends Protocol> protocol, boolean skipCurrentPipeline, boolean currentThread) throws InformativeException {
        if (isCancelled()) {
            return;
        }

        final UserConnection connection = user();
        if (currentThread) {
            sendNow(protocol, skipCurrentPipeline);
        } else {
            connection.getChannel().eventLoop().execute(() -> {
                // By the time a scheduled send runs the channel may have closed, and the connection's
                // stored objects are cleared on close, so the pipeline would mangle the packet
                if (connection.getChannel().isActive()) {
                    sendNow(protocol, skipCurrentPipeline);
                }
            });
        }
    }

    private void sendNow(final Class<? extends Protocol> protocol, final boolean skipCurrentPipeline) throws InformativeException {
        try {
            final ByteBuf output = constructPacket(protocol, skipCurrentPipeline, Direction.CLIENTBOUND);
            user().sendRawPacket(output);
        } catch (InformativeException e) {
            throw e;
        } catch (CancelException ignored) {
        } catch (Exception e) {
            if (!PipelineUtil.containsCause(e, CancelException.class)) {
                throw new InformativeException(e);
            }
        }
    }

    /**
     * Let the packet go through the protocol pipes and write it to ByteBuf
     *
     * @param protocolClass       protocol class to send the packet from, or null to go through the full pipeline
     * @param skipCurrentPipeline whether to start from the next protocol in the pipeline, or the provided one
     * @return created packet buffer
     */
    private ByteBuf constructPacket(@Nullable Class<? extends Protocol> protocolClass, boolean skipCurrentPipeline, Direction direction) throws InformativeException, CancelException {
        resetReader(); // Reset reader before we start

        final ProtocolInfo protocolInfo = user().getProtocolInfo();
        final List<Protocol> protocols = protocolInfo.getPipeline().pipes(protocolClass, skipCurrentPipeline, direction);
        // Use the packet's own state if known to avoid issues with stale tracked connection state
        // (e.g. a previous protocol in the pipeline already having changed serverState to PLAY while still processing FINISH_CONFIGURATION)
        final State state = this.packetType != null ? this.packetType.state() : protocolInfo.getState(direction);
        apply(direction, state, protocols);
        final ByteBuf output = allocateOutputBuffer();
        try {
            writeToBuffer(output);
            return output.retain();
        } finally {
            output.release();
        }
    }

    @Override
    public ChannelFuture sendFuture(Class<? extends Protocol> protocolClass) throws InformativeException {
        if (!isCancelled()) {
            final ByteBuf output;
            try {
                output = constructPacket(protocolClass, true, Direction.CLIENTBOUND);
            } catch (final CancelException e) {
                return user().getChannel().newFailedFuture(new RuntimeException("Cancelled packet"));
            }
            return user().sendRawPacketFuture(output);
        }
        return cancelledFuture();
    }

    @Override
    public void sendRaw() throws InformativeException {
        sendRaw(true);
    }

    @Override
    public ChannelFuture sendFutureRaw() throws InformativeException {
        if (isCancelled()) {
            return cancelledFuture();
        }

        ByteBuf output = allocateOutputBuffer();
        try {
            writeToBuffer(output);
            return user().sendRawPacketFuture(output.retain());
        } finally {
            output.release();
        }
    }

    @Override
    public void scheduleSendRaw() throws InformativeException {
        sendRaw(false);
    }

    private void sendRaw(boolean currentThread) throws InformativeException {
        if (isCancelled()) {
            return;
        }

        ByteBuf output = allocateOutputBuffer();
        try {
            writeToBuffer(output);
            if (currentThread) {
                user().sendRawPacket(output.retain());
            } else {
                user().scheduleSendRawPacket(output.retain());
            }
        } finally {
            output.release();
        }
    }

    private ByteBuf allocateOutputBuffer() {
        if (inputBuffer == null) {
            return user().getChannel().alloc().buffer();
        }
        // May have already been partially or fully read
        return inputBuffer.alloc().buffer(Math.max(inputBuffer.readableBytes(), 256));
    }

    private ChannelFuture cancelledFuture() {
        return user().getChannel().newFailedFuture(new RuntimeException("Tried to send cancelled packet"));
    }

    @Override
    public PacketWrapperImpl create(int packetId) {
        return new PacketWrapperImpl(packetId, null, user());
    }

    @Override
    public PacketWrapperImpl create(int packetId, PacketHandler handler) throws InformativeException {
        PacketWrapperImpl wrapper = create(packetId);
        handler.handle(wrapper);
        return wrapper;
    }

    @Override
    public PacketWrapperImpl create(final PacketType packetType) {
        return new PacketWrapperImpl(packetType, null, user());
    }

    @Override
    public PacketWrapperImpl create(final PacketType packetType, final PacketHandler handler) throws InformativeException {
        PacketWrapperImpl wrapper = create(packetType);
        handler.handle(wrapper);
        return wrapper;
    }

    @Override
    public void apply(Direction direction, State state, List<Protocol> pipeline) throws InformativeException, CancelException {
        // Indexed loop to allow additions to the tail
        for (int i = 0, size = pipeline.size(); i < size; i++) {
            Protocol<?, ?, ?, ?> protocol = pipeline.get(i);
            protocol.transform(direction, state, this);
            resetReader();
            if (this.packetType != null) {
                state = this.packetType.state();
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return !this.send;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.send = !cancel;
    }

    @Override
    public UserConnection user() {
        return this.userConnection;
    }

    @Override
    public void resetReader() {
        packetValues.resetReader();
    }

    private void appendUnreadValues() {
        packetValues.appendUnread();
    }

    @Override
    public void rewindReader(final int values) {
        if (allActionsRead) {
            return;
        }
        Preconditions.checkArgument(values >= 0 && values <= packetValues.size(), "Tried resetting more values than there are readable values");
        packetValues.rewind(values);
    }

    @Override
    public void sendToServerRaw() throws InformativeException {
        sendToServerRaw(true);
    }

    @Override
    public void scheduleSendToServerRaw() throws InformativeException {
        sendToServerRaw(false);
    }

    private void sendToServerRaw(boolean currentThread) throws InformativeException {
        if (isCancelled()) {
            return;
        }

        ByteBuf output = allocateOutputBuffer();
        try {
            writeToBuffer(output);
            if (currentThread) {
                user().sendRawPacketToServer(output.retain());
            } else {
                user().scheduleSendRawPacketToServer(output.retain());
            }
        } finally {
            output.release();
        }
    }

    @Override
    public void sendToServer(Class<? extends Protocol> protocol, boolean skipCurrentPipeline) throws InformativeException {
        sendToServer0(protocol, skipCurrentPipeline, true);
    }

    @Override
    public void scheduleSendToServer(Class<? extends Protocol> protocol, boolean skipCurrentPipeline) throws InformativeException {
        sendToServer0(protocol, skipCurrentPipeline, false);
    }

    private void sendToServer0(Class<? extends Protocol> protocol, boolean skipCurrentPipeline, boolean currentThread) throws InformativeException {
        if (isCancelled()) {
            return;
        }

        final UserConnection connection = user();
        if (currentThread) {
            try {
                final ByteBuf output = constructPacket(protocol, skipCurrentPipeline, Direction.SERVERBOUND);
                connection.sendRawPacketToServer(output);
            } catch (InformativeException e) {
                throw e;
            } catch (CancelException ignored) {
            } catch (Exception e) {
                if (!PipelineUtil.containsCause(e, CancelException.class)) {
                    throw new InformativeException(e);
                }
            }
            return;
        }

        connection.getChannel().eventLoop().execute(() -> {
            try {
                final ByteBuf output = constructPacket(protocol, skipCurrentPipeline, Direction.SERVERBOUND);
                connection.sendRawPacketToServer(output);
            } catch (InformativeException e) {
                throw e;
            } catch (CancelException ignored) {
            } catch (Exception e) {
                if (!PipelineUtil.containsCause(e, CancelException.class)) {
                    throw new InformativeException(e);
                }
            }
        });
    }

    @Override
    public @Nullable PacketType getPacketType() {
        return packetType;
    }

    @Override
    public void setPacketType(PacketType packetType) {
        this.packetType = packetType;
        this.id = packetType != null ? packetType.getId() : -1;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    @Deprecated
    public void setId(int id) {
        // Loses packet type info
        this.packetType = null;
        this.id = id;
    }

    public @Nullable ByteBuf getInputBuffer() {
        return inputBuffer;
    }

    public void setAllActionsRead(final boolean allActionsRead) {
        this.allActionsRead = allActionsRead;
    }

    @Override
    public void consumeReadsOnly(final Runnable runnable) {
        final boolean previous = this.allActionsRead;
        this.allActionsRead = true;
        try {
            runnable.run();
        } finally {
            this.allActionsRead = previous;
        }
    }

    @Override
    public String toString() {
        return "PacketWrapper{" +
            "type=" + packetType +
            ", id=" + id +
            ", values=" + packetValues +
            ", readable=" + packetValues.describeReadable() +
            '}';
    }

    private static final class PacketValuesSummary {
        private final FieldBuffer values;
        private String s;

        private PacketValuesSummary(final FieldBuffer values) {
            this.values = values;
        }

        @Override
        public String toString() {
            if (this.s == null) {
                this.s = Via.getManager().isDebug() ? this.values.toString() : summarize();
            }
            return this.s;
        }

        private String summarize() {
            final int maxLength = Math.max(0, Via.getConfig().maxErrorLength());
            final StringBuilder builder = new StringBuilder("[");
            for (int field = 0; field < values.size(); field++) {
                if (builder.length() > 1) {
                    builder.append(", ");
                }
                builder.append('{').append(values.type(field)).append(": ").append(stringToLog(values.value(field))).append('}');
                if (builder.length() >= maxLength) {
                    builder.setLength(maxLength);
                    return builder.append("...").toString();
                }
            }
            return builder.append(']').toString();
        }

        private static String stringToLog(@Nullable final Object value) {
            if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof CharSequence || value instanceof UUID || value instanceof Enum<?>) {
                return String.valueOf(value);
            } else if (value instanceof Item item) {
                return "Item{id=" + item.identifier() + ", amount=" + item.amount() + "}";
            }
            return "<" + value.getClass().getSimpleName() + ">";
        }
    }

    /** Unread fields followed by written fields in one ring; consumed slots are immediately reusable. */
    private static final class FieldBuffer {
        private static final Object[] EMPTY = new Object[0];
        private Object[] fields = EMPTY;
        private int head;
        private int readable;
        private int count;

        int size() { return count - readable; }
        boolean isEmpty() { return size() == 0; }
        int readableSize() { return readable; }
        Type<?> type(int index) { return (Type<?>) fields[slot(readable + index)]; }
        Object value(int index) { return fields[slot(readable + index) + 1]; }
        void setValue(int index, Object value) { fields[slot(readable + index) + 1] = value; }
        Type<?> readableType(int index) { return (Type<?>) fields[slot(index)]; }
        Object readableValue() { return fields[slot(0) + 1]; }

        private int slot(int index) {
            return ((head + index) & ((fields.length >> 1) - 1)) << 1;
        }

        void add(Type<?> type, Object value) {
            if (count == fields.length >> 1) {
                Object[] grown = new Object[Math.max(8, Math.multiplyExact(fields.length, 2))];
                for (int i = 0; i < count; i++) {
                    int source = slot(i);
                    grown[i * 2] = fields[source];
                    grown[i * 2 + 1] = fields[source + 1];
                }
                fields = grown;
                head = 0;
            }
            int target = slot(count++);
            fields[target] = type;
            fields[target + 1] = value;
        }

        void discardReadable() {
            int source = slot(0);
            fields[source] = null;
            fields[source + 1] = null;
            head = (head + 1) & ((fields.length >> 1) - 1);
            readable--;
            count--;
        }

        void clearReadable() {
            while (readable != 0) {
                discardReadable();
            }
        }

        void clear() {
            for (int i = 0; i < count; i++) {
                int source = slot(i);
                fields[source] = null;
                fields[source + 1] = null;
            }
            head = readable = count = 0;
        }

        void resetReader() {
            if (isEmpty()) {
                return;
            }
            appendUnread();
            readable = count;
        }

        void appendUnread() {
            // A partially consumed stage writes its output before its unread tail.
            if (readable != 0 && readable != count) {
                reverse(0, readable);
                reverse(readable, count);
                reverse(0, count);
            }
            readable = 0;
        }

        void rewind(int values) {
            if (values != 0) {
                // Move the written suffix before the existing unread fields, keeping the prefix written.
                reverse(0, count - values);
                reverse(count - values, count);
                reverse(0, count);
                readable += values;
            }
        }

        private void reverse(int from, int to) {
            for (int left = from, right = to - 1; left < right; left++, right--) {
                int a = slot(left), b = slot(right);
                Object type = fields[a], value = fields[a + 1];
                fields[a] = fields[b];
                fields[a + 1] = fields[b + 1];
                fields[b] = type;
                fields[b + 1] = value;
            }
        }

        String describeReadable() { return describe(0, readable); }
        private String describe(int from, int to) {
            StringBuilder out = new StringBuilder("[");
            for (int i = from; i < to; i++) {
                if (i != from) out.append(", ");
                int source = slot(i);
                out.append('{').append(fields[source]).append(": ").append(ArrayUtil.toString(fields[source + 1])).append('}');
            }
            return out.append(']').toString();
        }
        @Override public String toString() { return describe(readable, count); }
    }

    public static final class PacketValue<T> {
        private final Type<T> type;
        private T value;

        private PacketValue(final Type<T> type, @Nullable final T value) {
            this.type = type;
            this.value = value;
        }

        public Type<T> type() {
            return type;
        }

        public @Nullable Object value() {
            return value;
        }

        public void write(final ByteBuf buffer) throws Exception {
            type.write(buffer, value);
        }

        public void setValue(@Nullable final T value) {
            this.value = value;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final PacketValue<?> that = (PacketValue<?>) o;
            if (!type.equals(that.type)) return false;
            return Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + (value != null ? value.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "{" + type + ": " + ArrayUtil.toString(value) + "}";
        }
    }
}
