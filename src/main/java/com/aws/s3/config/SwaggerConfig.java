package com.aws.s3.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AWS S3 Multipart Upload API")
                        .description("""
                                REST API for uploading files to **AWS S3** using the Multipart Upload API.
                                
                                ## Upload Strategies
                                - **Simple Upload** — single PUT request, best for files < 100 MB
                                - **Large Upload** — server-side multipart upload, best for files > 100 MB
                                - **Client-Driven Multipart** — initiate → upload parts → complete (full control)
                                
                                ## Security
                                Files are validated client-side before reaching this API.
                                Always call `/multipart/abort` on failure to avoid storage charges.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Jatin Ghataliya")
                                .email("prajapati.jatin94@gmail.com")
                                .url("https://github.com/Jatinghataliya/AWSEXAMPLES"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development"),
                        new Server().url("http://localhost").description("Docker (via nginx)")
                ));
    }
}
