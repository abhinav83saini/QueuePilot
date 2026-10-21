package com.queuepilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling 
public class QueuepilotApplication {

	public static void main(String[] args) {
		SpringApplication.run(QueuepilotApplication.class, args);
	}

}
