package com.sumitdocmind;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI docmindOpenApi() {
        final String schemeName = "bearerAuth";
        return new OpenAPI()
            .info(new Info()
                .title("SumitDocMind API")
                .description("""
                    Chat with your PDF documents using local RAG.
                    Register/login to get a JWT token, upload PDFs, then ask questions —
                    answers are grounded in your documents and cite the source filenames.

                    **Auth:** When JWT is enabled (`JWT_ENABLED=true`), click *Authorize* and paste
                    your token from the register/login endpoints. By default JWT is disabled for
                    local development, so protected endpoints work without a token.
                    """)
                .version("1.0.0")
                .contact(new Contact().name("SumitDocMind").email("dev@example.com")))
            .components(new Components().addSecuritySchemes(schemeName,
                new SecurityScheme()
                    .name(schemeName)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Paste the JWT returned by /api/auth/register or /api/auth/login.")))
            .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }
}
