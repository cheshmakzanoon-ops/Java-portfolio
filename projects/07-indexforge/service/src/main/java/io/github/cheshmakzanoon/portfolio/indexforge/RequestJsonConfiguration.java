package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.List;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/** Keep the HTTP input exactly typed before source acquisition or durable registration. */
@Configuration
class RequestJsonConfiguration {
  @Bean
  JsonMapperBuilderCustomizer strictRequestJson() {
    return builder ->
        builder
            .withCoercionConfig(
                LogicalType.Textual,
                config -> {
                  for (var shape :
                      List.of(
                          CoercionInputShape.Integer,
                          CoercionInputShape.Float,
                          CoercionInputShape.Boolean))
                    config.setCoercion(shape, CoercionAction.Fail);
                })
            .withCoercionConfig(
                LogicalType.Integer,
                config -> {
                  config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                  config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                })
            .withCoercionConfig(
                LogicalType.Boolean,
                config -> {
                  for (var shape :
                      List.of(
                          CoercionInputShape.String,
                          CoercionInputShape.EmptyString,
                          CoercionInputShape.Integer,
                          CoercionInputShape.Float)) config.setCoercion(shape, CoercionAction.Fail);
                })
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }
}
