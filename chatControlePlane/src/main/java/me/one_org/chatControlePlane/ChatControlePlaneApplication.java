package me.one_org.chatControlePlane;

import me.one_org.chatControlePlane.properties.HmacProperties;
import me.one_org.chatControlePlane.properties.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ JwtProperties.class, HmacProperties.class })
public class ChatControlePlaneApplication {
	public static void main(String[] args) {
		SpringApplication.run(ChatControlePlaneApplication.class, args);
	}

}
