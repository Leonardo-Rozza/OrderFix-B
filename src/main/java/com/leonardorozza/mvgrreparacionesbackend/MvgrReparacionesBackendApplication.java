package com.leonardorozza.mvgrreparacionesbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MvgrReparacionesBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MvgrReparacionesBackendApplication.class, args);
    }

}
