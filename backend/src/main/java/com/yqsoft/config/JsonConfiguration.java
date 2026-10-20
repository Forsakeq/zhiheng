package com.yqsoft.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;

@Configuration
public class JsonConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer apiJsonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            module.addSerializer(BigInteger.class, ToStringSerializer.instance);
            module.addSerializer(
                    Instant.class,
                    new JsonSerializer<>() {
                        @Override
                        public void serialize(
                                Instant value,
                                JsonGenerator generator,
                                SerializerProvider serializers)
                                throws IOException {
                            generator.writeString(
                                    new DateTimeFormatterBuilder()
                                            .appendInstant(3)
                                            .toFormatter()
                                            .format(value));
                        }
                    });
            builder.modulesToInstall(module);
            builder.featuresToEnable(
                    com.fasterxml.jackson.databind.DeserializationFeature
                            .FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }
}
