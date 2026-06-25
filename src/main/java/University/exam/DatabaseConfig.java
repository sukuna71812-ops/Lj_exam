package University.exam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import javax.sql.DataSource;
import java.net.URI;

@Configuration
public class DatabaseConfig {

    @Value("${spring.datasource.url}")
    private String defaultUrl;

    @Value("${spring.datasource.username}")
    private String defaultUsername;

    @Value("${spring.datasource.password}")
    private String defaultPassword;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    @Bean
    public DataSource dataSource() {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl != null && !databaseUrl.isEmpty() && databaseUrl.startsWith("postgres")) {
            try {
                // Parse standard DATABASE_URL format: postgresql://username:password@host:port/database
                URI dbUri = new URI(databaseUrl);
                String username = dbUri.getUserInfo().split(":")[0];
                String password = dbUri.getUserInfo().split(":")[1];
                String dbUrl = "jdbc:postgresql://" + dbUri.getHost() + ":" + dbUri.getPort() + dbUri.getPath();

                return DataSourceBuilder.create()
                        .url(dbUrl)
                        .username(username)
                        .password(password)
                        .driverClassName(driverClassName)
                        .build();
            } catch (Exception e) {
                System.err.println("Error parsing DATABASE_URL, falling back to other environment variables: " + e.getMessage());
            }
        }

        // Fall back to application.properties values (which resolves PGHOST, PGPORT, etc., or local defaults)
        return DataSourceBuilder.create()
                .url(defaultUrl)
                .username(defaultUsername)
                .password(defaultPassword)
                .driverClassName(driverClassName)
                .build();
    }
}
