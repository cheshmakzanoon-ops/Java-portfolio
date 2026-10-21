package io.github.cheshmakzanoon.portfolio.gatehouse;

import static org.assertj.core.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class JsonConfigurationTest {
  enum Classification {
    PUBLIC,
    CONFIDENTIAL
  }

  record Input(int clearance, Classification classification) {}

  @Test
  void actualApplicationPropertiesBindAndRejectAmbiguousJson() throws Exception {
    Properties properties = new Properties();
    try (var stream = getClass().getResourceAsStream("/application.properties")) {
      assertThat(stream).isNotNull();
      properties.load(stream);
    }
    String[] jacksonProperties =
        properties.stringPropertyNames().stream()
            .filter(key -> key.startsWith("spring.jackson."))
            .map(key -> key + "=" + properties.getProperty(key))
            .toArray(String[]::new);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withPropertyValues(jacksonProperties)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              JsonMapper mapper = context.getBean(JsonMapper.class);
              assertThat(
                      mapper.readValue(
                          "{\"clearance\":3,\"classification\":\"PUBLIC\"}", Input.class))
                  .isEqualTo(new Input(3, Classification.PUBLIC));
              for (String invalid :
                  new String[] {
                    "{\"clearance\":3.9,\"classification\":\"PUBLIC\"}",
                    "{\"clearance\":3,\"classification\":0}",
                    "{\"clearance\":3,\"classification\":\"PUBLIC\",\"subjectId\":\"admin\"}",
                    "{\"clearance\":0,\"clearance\":3,\"classification\":\"PUBLIC\"}",
                    "{\"clearance\":3,\"classification\":\"PUBLIC\"} {}"
                  }) {
                assertThatThrownBy(() -> mapper.readValue(invalid, Input.class))
                    .isInstanceOf(tools.jackson.core.JacksonException.class);
              }
            });
  }
}
