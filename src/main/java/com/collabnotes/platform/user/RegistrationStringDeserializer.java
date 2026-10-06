package com.collabnotes.platform.user;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

/** 不把 JSON 数字、布尔值等悄悄转换成用户名或密码字符串。 */
public class RegistrationStringDeserializer extends StdScalarDeserializer<String> {
    public RegistrationStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
        return parser.getText();
    }
}
