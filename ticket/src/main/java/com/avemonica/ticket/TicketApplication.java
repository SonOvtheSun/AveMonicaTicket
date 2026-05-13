package com.avemonica.ticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.avemonica.ticket.mapper")
public class TicketApplication {

    public static void main(String[] args) {

        SpringApplication.run(TicketApplication.class, args);
    }

}
