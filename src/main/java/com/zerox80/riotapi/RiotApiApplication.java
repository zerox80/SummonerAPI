package com.zerox80.riotapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;

@SpringBootApplication
@EnableCaching
public class RiotApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiotApiApplication.class, args);
    }

    // =================================================================
    // DIESER CODE-BLOCK IST NEU - UNSER "SPION"
    // Er liest das Passwort, das die App WIRKLICH benutzt, und druckt es aus.
    // =================================================================
    @Bean
    public CommandLineRunner commandLineRunner(@Value("${spring.datasource.password}") String password) {
        return args -> {
            System.out.println("\n\n\n");
            System.out.println("****************************************************************");
            System.out.println("****************************************************************");
            System.out.println("DAS PASSWORT, DAS DIE APP WIRKLICH BENUTZT: [" + password + "]");
            System.out.println("****************************************************************");
            System.out.println("****************************************************************");
            System.out.println("\n\n\n");
        };
    }
}