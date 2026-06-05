package dev.polygate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class PolyGateApplication {
  public static void main(String[] args) {
    if (args.length >= 3 && args[0].equals("runtime-adapter") && args[1].equals("validate")) {
      try {
        var doc =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(java.nio.file.Files.readString(java.nio.file.Path.of(args[2])));
        RuntimeAdapterController.validate(doc);
        System.out.println("{\"valid\":true,\"kind\":\"" + doc.path("kind").asText() + "\"}");
        return;
      } catch (Exception e) {
        System.err.println("Runtime Adapter validation failed");
        System.exit(2);
      }
    }
    SpringApplication.run(PolyGateApplication.class, args);
  }
}
