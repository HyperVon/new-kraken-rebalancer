package com.gemini.krakenbot;

import com.gemini.krakenbot.service.PortfolioManager;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KrakenRebalancerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KrakenRebalancerApplication.class, args);
    }

    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    @Bean
    public CommandLineRunner run(PortfolioManager portfolioManager) {
        return args -> {
            portfolioManager.startRebalancingLoop();
        };
    }
}
