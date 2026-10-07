package com.collabnotes.platform.note;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

/** 不把字符串或数字悄悄转换为完成状态。null 由 DTO 的必填校验拒绝。 */
public class StrictBooleanDeserializer extends StdScalarDeserializer<Boolean> {
    public StrictBooleanDeserializer() { super(Boolean.class); }

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_TRUE) && !parser.hasToken(JsonToken.VALUE_FALSE)) {
            return context.reportInputMismatch(Boolean.class, "完成状态必须是 JSON 布尔值");
        }
        return parser.getBooleanValue();
    }
}
