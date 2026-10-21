package io.github.cheshmakzanoon.portfolio.switchback;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SwitchbackApplication {
  public static void main(String[] args) {
    SpringApplication.run(SwitchbackApplication.class, args);
  }

  @Bean
  Store store(DataSource dataSource) {
    Store s = new Store(dataSource);
    s.initialize();
    return s;
  }

  @Bean(destroyMethod = "close")
  Dispatcher dispatcher(
      Store store,
      @Value("${switchback.source-revision:working-tree-unverified}") String sourceRevision) {
    return new Dispatcher(store, sourceRevision);
  }
}
