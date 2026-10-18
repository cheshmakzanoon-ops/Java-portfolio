package io.github.cheshmakzanoon.portfolio.routeweaver;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RouteWeaverApplication {
  public static void main(String[] args) {
    SpringApplication.run(RouteWeaverApplication.class, args);
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
      @Value("${routeweaver.source-revision:working-tree-unverified}") String sourceRevision) {
    return new Dispatcher(store, sourceRevision);
  }
}
