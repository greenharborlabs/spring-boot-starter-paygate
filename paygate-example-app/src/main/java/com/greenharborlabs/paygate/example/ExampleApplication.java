package com.greenharborlabs.paygate.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExampleApplication {

  public static void main(String[] args) {
    application().run(args);
  }

  static SpringApplication application() {
    var application = new SpringApplication(ExampleApplication.class);
    application.addInitializers(new LocalWalletBootstrapInitializer());
    return application;
  }
}
