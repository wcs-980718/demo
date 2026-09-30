package com.agenthubfusion;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
/** Internal execution entrypoint, built and released in the middle-platform backend artifact. */
@SpringBootApplication(exclude={HibernateJpaAutoConfiguration.class,JpaRepositoriesAutoConfiguration.class})
@EnableScheduling
public class FusionApplication {
 public static void main(String[] args){SpringApplication app=new SpringApplication(FusionApplication.class);app.setAdditionalProfiles("fusion");app.run(args);}
}
