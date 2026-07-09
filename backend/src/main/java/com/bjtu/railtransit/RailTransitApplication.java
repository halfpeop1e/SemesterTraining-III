package com.bjtu.railtransit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RailTransitApplication {

    public static void main(String[] args) {
        SpringApplication.run(RailTransitApplication.class, args);
    }
}
