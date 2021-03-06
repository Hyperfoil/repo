package io.hyperfoil.tools.horreum.entity.converter;

import java.lang.reflect.Type;

import javax.json.bind.serializer.DeserializationContext;
import javax.json.bind.serializer.JsonbDeserializer;
import javax.json.bind.serializer.JsonbSerializer;
import javax.json.bind.serializer.SerializationContext;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonParser;

import io.hyperfoil.tools.horreum.entity.json.Access;

public class AccessSerializer implements JsonbDeserializer<Access>, JsonbSerializer<Access> {
   private static final Access[] VALUES = Access.values();
   @Override
   public Access deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
      String str = parser.getString();
      try {
         return VALUES[Integer.parseInt(str)];
      } catch (NumberFormatException e) {
         return Access.valueOf(str);
      }
   }

   @Override
   public void serialize(Access access, JsonGenerator generator, SerializationContext ctx) {
      generator.write(access.ordinal());
   }
}
