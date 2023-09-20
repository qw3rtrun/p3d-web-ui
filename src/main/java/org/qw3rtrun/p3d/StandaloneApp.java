package org.qw3rtrun.p3d;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
//@Import(ApiApplication.class)
public class StandaloneApp {

    public static void main(String[] args) {
        SpringApplication.run(StandaloneApp.class, args);
    }

}

