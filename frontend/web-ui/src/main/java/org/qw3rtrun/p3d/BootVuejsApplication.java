package org.qw3rtrun.p3d;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class BootVuejsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootVuejsApplication.class, args);
    }

}

@Controller
class HelloThymeleafController {

    @GetMapping(path = "/thymeleaf")
    public String thymleaf(Model model) {
        model.addAttribute("name", "Thymeleaf");
        return "hellothymeleaf";
    }

}

@RestController
class CustomEndpoint {
    @GetMapping(path = "/custom")
    public CustomData custom() {
        return new CustomData(123, "Vasya Petrov");
    }

    record CustomData(int id, String name) {}
}
