package mail.mcp.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Copied from redmine-mcp-server {@code config/JsonConfig} @ {@code f62b3e5}.
 */
@Configuration
public class JsonConfig {

    @Bean
    @Primary
    public ObjectMapper mailMcpObjectMapper() {
        // Jackson 3 mapper. SORT_PROPERTIES_ALPHABETICALLY is *on* by default in J3 — disabled here
        // so responses keep the declaration order of the api/ records.
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }
}
