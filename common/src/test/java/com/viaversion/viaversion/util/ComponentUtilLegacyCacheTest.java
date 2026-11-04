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
import com.google.gson.JsonParser;
import com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ComponentUtilLegacyCacheTest {

    private static final List<String> INPUTS = List.of(
        "\"plain\"",
        "{\"text\":\"\"}",
        "{\"text\":\"Steve\",\"color\":\"gold\",\"bold\":true}",
        "{\"text\":\"\",\"extra\":[{\"text\":\"[VIP] \",\"color\":\"green\"},{\"text\":\"Alex\",\"italic\":true}]}",
        "{\"translate\":\"chat.type.text\",\"with\":[\"a\",{\"text\":\"b\",\"color\":\"red\"}]}"
    );

    @Test
    void cachedJsonElementMatchesDirectConversion() {
        for (final String json : INPUTS) {
            final JsonElement element = JsonParser.parseString(json);
            final String expected = SerializerVersion.V1_12.toComponent(element).asLegacyFormatString();
            Assertions.assertEquals(expected, ComponentUtil.jsonToLegacy(element), json);
            // The second call is served from the cache.
            Assertions.assertEquals(expected, ComponentUtil.jsonToLegacy(JsonParser.parseString(json)), json);
        }
    }

    @Test
    void cachedJsonStringMatchesDirectConversion() {
        for (final String json : INPUTS) {
            final String expected = TextComponentSerializer.V1_12.deserializeReader(json).asLegacyFormatString();
            Assertions.assertEquals(expected, ComponentUtil.jsonToLegacy(json), json);
            Assertions.assertEquals(expected, ComponentUtil.jsonToLegacy(json), json);
        }
    }
}
