package com.hammez.stride;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StrideMcpApplication {

	public static void main(String[] args) {
		// The commands run once and exit with their own exit code.
		System.exit(SpringApplication.exit(SpringApplication.run(StrideMcpApplication.class, args)));
	}

}
