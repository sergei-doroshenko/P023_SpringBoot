package org.sergei;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;

@RestController
@SpringBootApplication
public class MyRestController {

    @RequestMapping("/")
    String home() {
        return "Hello Sergei!";
    }

    public static void main(String[] args) throws Exception {
        SpringApplication.run( MyRestController.class, args);
    }
}