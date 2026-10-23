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
package com.viaversion.viaversion.common.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.common.PlatformTestBase;
import com.viaversion.viaversion.util.ComponentUtil;
import com.viaversion.viaversion.util.SerializerVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ComponentUtilCacheTest extends PlatformTestBase {

    private static final String JSON = "{\"text\":\"Kills: \",\"color\":\"gray\",\"extra\":[{\"text\":\"12\",\"color\":\"white\",\"bold\":true}]}";

    @Test
    void jsonToTagHandsOutIndependentCopies() {
        final JsonElement element = JsonParser.parseString(JSON);
        final Tag first = ComponentUtil.jsonToTag(element);
        final Tag snapshot = first.copy();

        ((CompoundTag) first).putString("text", "mutated");

        final Tag second = ComponentUtil.jsonToTag(JsonParser.parseString(JSON));
        Assertions.assertNotSame(first, second);
        Assertions.assertEquals(snapshot, second);
    }

    @Test
    void jsonStringToTagHandsOutIndependentCopies() {
        final Tag first = ComponentUtil.jsonStringToTag(JSON);
        final Tag snapshot = first.copy();

        ((CompoundTag) first).putString("text", "mutated");

        Assertions.assertEquals(snapshot, ComponentUtil.jsonStringToTag(JSON));
        Assertions.assertEquals(
            snapshot,
            ComponentUtil.jsonStringToTag(JSON, SerializerVersion.V1_20_3, SerializerVersion.V1_20_5)
        );
    }

    @Test
    void convertJsonOrEmptyHandsOutIndependentCopies() {
        final JsonElement first = ComponentUtil.convertJsonOrEmpty(JSON, SerializerVersion.V1_8, SerializerVersion.V1_9);
        final JsonElement snapshot = first.deepCopy();

        ((JsonObject) first).addProperty("text", "mutated");

        Assertions.assertEquals(snapshot, ComponentUtil.convertJsonOrEmpty(JSON, SerializerVersion.V1_8, SerializerVersion.V1_9));
    }

    @Test
    void cacheKeysIncludeTheVersions() {
        final String hover = "{\"text\":\"a\",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"b\"}}";
        Assertions.assertNotEquals(
            ComponentUtil.convertJsonOrEmpty(hover, SerializerVersion.V1_16, SerializerVersion.V1_16),
            ComponentUtil.convertJsonOrEmpty(hover, SerializerVersion.V1_16, SerializerVersion.V1_12)
        );
    }

    @Test
    void legacyConversionsAreStable() {
        final String legacy = "§7Kills: §f§l12";
        final JsonElement first = ComponentUtil.legacyToJson(legacy);
        final JsonElement snapshot = first.deepCopy();

        ((JsonObject) first).addProperty("text", "mutated");

        Assertions.assertEquals(snapshot, ComponentUtil.legacyToJson(legacy));
        Assertions.assertEquals(ComponentUtil.legacyToJsonString(legacy), ComponentUtil.legacyToJsonString(legacy));
        Assertions.assertNotEquals(ComponentUtil.legacyToJsonString(legacy, false), ComponentUtil.legacyToJsonString(legacy, true));
    }
}
