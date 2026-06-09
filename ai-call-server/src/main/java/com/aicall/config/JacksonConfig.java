package com.aicall.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 与前端 Element Plus {@code value-format="YYYY-MM-DD HH:mm:ss"} 对齐。
 */
@Configuration
public class JacksonConfig {

    public static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(DATETIME))
                .deserializerByType(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
    }

    static final class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        private static final DateTimeFormatter[] FORMATS = {
                DATETIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        };

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getValueAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            text = text.trim();
            for (DateTimeFormatter fmt : FORMATS) {
                try {
                    return LocalDateTime.parse(text, fmt);
                } catch (DateTimeParseException ignored) {
                    // try next
                }
            }
            try {
                return LocalDateTime.parse(text.replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                throw ctxt.weirdStringException(text, LocalDateTime.class,
                        "无法解析日期时间，请使用 yyyy-MM-dd HH:mm:ss");
            }
        }
    }
}
