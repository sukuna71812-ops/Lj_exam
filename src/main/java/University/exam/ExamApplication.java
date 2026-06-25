package University.exam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.net.URI;

@SpringBootApplication
public class ExamApplication {

	public static void main(String[] args) {
		String databaseUrl = System.getenv("DATABASE_URL");
		if (databaseUrl != null && !databaseUrl.isEmpty()) {
			try {
				if (databaseUrl.startsWith("postgres://")) {
					databaseUrl = databaseUrl.replace("postgres://", "postgresql://");
				}
				URI dbUri = new URI(databaseUrl);
				String username = "";
				String password = "";
				if (dbUri.getUserInfo() != null) {
					String[] userInfo = dbUri.getUserInfo().split(":");
					if (userInfo.length > 0) {
						username = userInfo[0];
					}
					if (userInfo.length > 1) {
						password = userInfo[1];
					}
				}
				int port = dbUri.getPort();
				String portStr = port == -1 ? "" : ":" + port;
				String dbUrl = "jdbc:postgresql://" + dbUri.getHost() + portStr + dbUri.getPath();

				System.setProperty("SPRING_DATASOURCE_URL", dbUrl);
				System.setProperty("SPRING_DATASOURCE_USERNAME", username);
				System.setProperty("SPRING_DATASOURCE_PASSWORD", password);
			} catch (Exception e) {
				System.err.println("Failed to parse DATABASE_URL: " + e.getMessage());
			}
		}

		SpringApplication.run(ExamApplication.class, args);
	}
  
}                                                                                                   