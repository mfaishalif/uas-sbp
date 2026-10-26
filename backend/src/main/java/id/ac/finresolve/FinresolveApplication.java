package id.ac.finresolve;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FinresolveApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinresolveApplication.class, args);
    }
}
