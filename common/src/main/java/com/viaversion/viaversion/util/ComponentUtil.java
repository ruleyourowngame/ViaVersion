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
package com.viaversion.viaversion.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.libs.mcstructs.text.Style;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.StringComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.TranslationComponent;
import com.viaversion.viaversion.libs.mcstructs.text.events.hover.HoverEvent;
import com.viaversion.viaversion.libs.mcstructs.text.events.hover.impl.EntityHoverEvent;
import com.viaversion.viaversion.libs.mcstructs.text.events.hover.impl.ItemHoverEvent;
import com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer;
import com.viaversion.viaversion.libs.mcstructs.text.stringformat.StringFormat;
import com.viaversion.viaversion.libs.mcstructs.text.stringformat.handling.ColorHandling;
import com.viaversion.viaversion.libs.mcstructs.text.stringformat.handling.DeserializerUnknownHandling;
import com.viaversion.viaversion.libs.mcstructs.text.utils.TextUtils;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Component conversion utility, trying to divert most calls to the component library to this class instead for easy replacement.
 */
public final class ComponentUtil {

    private static final int MAX_UNSIGNED_SHORT = 65535;

    // Arch start - the same text goes to every player (chat broadcasts, tab header/footer, team
    // prefixes, hologram names, item lore), and each modern player's pipeline converted it again.
    // These conversions are pure, so results are kept and copied out. Long inputs (books, big
    // hover events) are rare and not worth holding.
    private static final int CACHE_CAPACITY = 1024;
    private static final int MAX_CACHED_INPUT = 8192;
    private static final ConversionCache<String, Tag> JSON_TO_TAG = new ConversionCache<>(CACHE_CAPACITY);
    private static final ConversionCache<VersionedInput, Tag> JSON_STRING_TO_TAG = new ConversionCache<>(CACHE_CAPACITY);
    private static final ConversionCache<VersionedInput, JsonElement> JSON_OR_EMPTY = new ConversionCache<>(CACHE_CAPACITY);
    private static final ConversionCache<String, JsonElement> LEGACY_TO_JSON = new ConversionCache<>(CACHE_CAPACITY);
    private static final ConversionCache<LegacyInput, String> LEGACY_TO_JSON_STRING = new ConversionCache<>(CACHE_CAPACITY);

    private record VersionedInput(String input, SerializerVersion from, SerializerVersion to) {
    }

    private record LegacyInput(String message, boolean itemData) {
    }

    private static boolean cacheable(final String input) {
        return ConversionCache.ENABLED && input.length() <= MAX_CACHED_INPUT;
    }
    // Arch end

    public static JsonObject emptyJsonComponent() {
        return plainToJson("");
    }

    public static String emptyJsonComponentString() {
        return "{\"text\":\"\"}";
    }

    public static JsonObject plainToJson(final String message) {
        final JsonObject object = new JsonObject();
        object.addProperty("text", message);
        return object;
    }

    public static @Nullable JsonElement tagToJson(@Nullable final Tag tag) {
        try {
            final TextComponent component = SerializerVersion.V1_20_3.toComponent(tag);
            return component != null ? SerializerVersion.V1_19_4.toJson(component) : null;
        } catch (final Exception e) {
            if (Via.getConfig().logTextComponentConversionErrors()) {
                Via.getPlatform().getLogger().log(Level.SEVERE, "Error converting tag: " + StringUtil.forLogging(tag), e);
            }
            return plainToJson("<error>");
        }
    }

    public static @Nullable Tag jsonToTag(@Nullable final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        try {
            // Arch start
            final String key = ConversionCache.ENABLED ? element.toString() : null;
            if (key == null || !cacheable(key)) {
                return convertJsonToTag(element);
            }

            final Tag tag = JSON_TO_TAG.get(key, ignored -> convertJsonToTag(element));
            return tag != null ? tag.copy() : null;
            // Arch end
        } catch (final Exception e) {
            if (Via.getConfig().logTextComponentConversionErrors()) {
                Via.getPlatform().getLogger().log(Level.SEVERE, "Error converting component: " + StringUtil.forLogging(element), e);
            }
            return new StringTag("<error>");
        }
    }

    private static @Nullable Tag convertJsonToTag(final JsonElement element) {
        final TextComponent component = SerializerVersion.V1_19_4.toComponent(element);
        return trimStrings(SerializerVersion.V1_20_3.toTag(component));
    }

    public static Tag trimStrings(final Tag input) {
        // Dirty fix for https://github.com/ViaVersion/ViaVersion/issues/3650
        // Usually tripped by hover event data being too long, e.g. book or shulker box contents, which have to be held in full as SNBT
        if (input == null) {
            return null;
        }
        return TagUtil.handleDeep(input, (key, tag) -> {
            if (tag instanceof StringTag stringTag) {
                final String value = stringTag.getValue();
                // Cheap upper bound check first, then check the actual bytes
                if (value.length() > MAX_UNSIGNED_SHORT / 3 && value.getBytes(StandardCharsets.UTF_8).length > MAX_UNSIGNED_SHORT) {
                    stringTag.setValue("{}");
                }
            }
            return tag;
        });
    }

    public static @Nullable String tagToJsonString(@Nullable final Tag tag) {
        try {
            final TextComponent component = SerializerVersion.V1_20_5.toComponent(tag);
            return component != null ? SerializerVersion.V1_20_3.toString(component) : null;
        } catch (final Exception e) {
            if (Via.getConfig().logTextComponentConversionErrors()) {
                Via.getPlatform().getLogger().log(Level.SEVERE, "Error converting tag: " + StringUtil.forLogging(tag), e);
            }
            return plainToJson("<error>").toString();
        }
    }

    public static @Nullable Tag jsonStringToTag(@Nullable final String json) {
        return jsonStringToTag(json, SerializerVersion.V1_20_3, SerializerVersion.V1_20_5);
    }

    public static @Nullable Tag jsonStringToTag(@Nullable final String json, final SerializerVersion from, final SerializerVersion to) {
        if (json == null) {
            return null;
        }
        // Arch start
        if (!cacheable(json)) {
            return to.toTag(from.jsonSerializer.deserialize(json));
        }

        final Tag tag = JSON_STRING_TO_TAG.get(new VersionedInput(json, from, to), input -> to.toTag(from.jsonSerializer.deserialize(json)));
        return tag != null ? tag.copy() : null;
        // Arch end
    }

    public static @Nullable JsonElement convertJson(@Nullable final JsonElement element, final SerializerVersion from, final SerializerVersion to) {
        return element != null ? convert(from, to, from.toComponent(element)) : null;
    }

    public static @Nullable JsonElement convertJson(@Nullable final String json, final SerializerVersion from, final SerializerVersion to) {
        return json != null ? convert(from, to, from.toComponent(json)) : null;
    }

    public static @Nullable JsonElement convertJsonOrEmpty(@Nullable final String json, final SerializerVersion from, final SerializerVersion to) {
        // Arch start
        if (json != null && cacheable(json)) {
            return JSON_OR_EMPTY.get(new VersionedInput(json, from, to), input -> convertJsonOrEmptyUncached(json, from, to)).deepCopy();
        }
        return convertJsonOrEmptyUncached(json, from, to);
    }

    private static JsonElement convertJsonOrEmptyUncached(@Nullable final String json, final SerializerVersion from, final SerializerVersion to) {
        // Arch end
        final TextComponent component = from.toComponent(json);
        if (component == null) {
            return emptyJsonComponent();
        }
        return to.toJson(component);
    }

    private static JsonElement convert(final SerializerVersion from, final SerializerVersion to, final TextComponent component) {
        if (from.ordinal() >= SerializerVersion.V1_16.ordinal() && to.ordinal() < SerializerVersion.V1_16.ordinal()) {
            // Convert hover event to legacy format
            component.forEach(c -> convertHoverToLegacy(to, c));
        }
        return to.toJson(component);
    }

    private static void convertHoverToLegacy(final SerializerVersion to, final TextComponent component) {
        if (component instanceof TranslationComponent translationComponent) {
            for (final Object arg : translationComponent.getArgs()) {
                if (arg instanceof TextComponent componentArg) {
                    convertHoverToLegacy(to, componentArg);
                }
            }
        }

        final Style style = component.getStyle();
        final HoverEvent hoverEvent = style.getHoverEvent();
        if (hoverEvent instanceof EntityHoverEvent entityHoverEvent && entityHoverEvent.isModern()) {
            final EntityHoverEvent.ModernHolder entityData = entityHoverEvent.asModern();
            final CompoundTag tag = new CompoundTag();
            tag.putString("type", entityData.getType().get());
            tag.putString("id", entityData.getUuid().toString());
            tag.putString("name", to.toString(entityData.getName() != null ? entityData.getName() : new StringComponent("")));
            entityHoverEvent.setLegacyData(new StringComponent(to.toSNBT(tag)));
        } else if (hoverEvent instanceof ItemHoverEvent itemHoverEvent && itemHoverEvent.isModern()) {
            final ItemHoverEvent.ModernHolder itemData = itemHoverEvent.asModern();
            final CompoundTag tag = new CompoundTag();
            tag.putString("id", itemData.getId().get());
            tag.putByte("Count", (byte) itemData.getCount());
            if (itemData.getTag() != null) {
                tag.put("tag", itemData.getTag());
            }
            itemHoverEvent.setLegacyData(to.toSNBT(tag));
        }
    }

    public static JsonElement legacyToJson(final String message) {
        // Arch start
        if (cacheable(message)) {
            return LEGACY_TO_JSON.get(message, ComponentUtil::legacyToJsonUncached).deepCopy();
        }
        return legacyToJsonUncached(message);
    }

    private static JsonElement legacyToJsonUncached(final String message) {
        // Arch end
        return SerializerVersion.V1_12.toJson(StringFormat.vanilla().fromString(message, ColorHandling.RESET, DeserializerUnknownHandling.WHITE));
    }

    public static String legacyToJsonString(final String message) {
        return legacyToJsonString(message, false);
    }

    public static String legacyToJsonString(final String message, final boolean itemData) {
        // Arch start
        if (cacheable(message)) {
            return LEGACY_TO_JSON_STRING.get(new LegacyInput(message, itemData), input -> legacyToJsonStringUncached(message, itemData));
        }
        return legacyToJsonStringUncached(message, itemData);
    }

    private static String legacyToJsonStringUncached(final String message, final boolean itemData) {
        // Arch end
        final TextComponent component = StringFormat.vanilla().fromString(message, ColorHandling.RESET, DeserializerUnknownHandling.WHITE);
        if (itemData) {
            TextUtils.iterateAll(component, c -> {
                if (!c.getStyle().isEmpty()) {
                    c.setParentStyle(new Style().setItalic(false));
                }
            });
        }
        return SerializerVersion.V1_12.toString(component);
    }

    public static String jsonToLegacy(final String value) {
        return TextComponentSerializer.V1_12.deserializeReader(value).asLegacyFormatString();
    }

    public static String jsonToLegacy(final JsonElement value) {
        return SerializerVersion.V1_12.toComponent(value).asLegacyFormatString();
    }

    public static CompoundTag deserializeLegacyShowItem(final JsonElement element, final SerializerVersion version) {
        return (CompoundTag) version.toTag(version.toComponent(element).asUnformattedString());
    }

    public static CompoundTag deserializeShowItem(final Tag value, final SerializerVersion version) {
        return (CompoundTag) version.toTag(version.toComponent(value).asUnformattedString());
    }
}
