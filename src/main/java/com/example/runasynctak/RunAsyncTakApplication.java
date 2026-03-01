package com.example.runasynctak;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Main application entry point.
 *
 * {@code @EnableAsync} activates Spring's async method execution capability,
 * allowing methods annotated with {@code @Async} to run in separate threads.
 */
@SpringBootApplication
@EnableAsync
public class RunAsyncTakApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunAsyncTakApplication.class, args);
    }
}
