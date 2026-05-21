package com.gemini.krakenbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gemini.krakenbot.service.PortfolioManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableScheduling
public class KrakenRebalancerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KrakenRebalancerApplication.class, args);
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public CommandLineRunner run(PortfolioManager portfolioManager,
            @Value("${app.enable-rebalancing:true}") boolean enableRebalancing) {
        return _ -> {
            if (enableRebalancing) {
                portfolioManager.startRebalancingLoop();
            }
        };
    }
}
