package University.exam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExamApplication {

	public static void main(String[] args) {
		String databaseUrl = System.getenv("DATABASE_URL");
		configureDatabaseConnection(databaseUrl);
		SpringApplication.run(ExamApplication.class, args);
	}

	public static void configureDatabaseConnection(String databaseUrl) {
		if (databaseUrl != null && !databaseUrl.trim().isEmpty()) {
			try {
				java.net.URI dbUri = new java.net.URI(databaseUrl);
				
				String userInfo = dbUri.getUserInfo();
				if (userInfo != null && userInfo.contains(":")) {
					String username = userInfo.split(":")[0];
					String password = userInfo.split(":")[1];
					System.setProperty("spring.datasource.username", username);
					System.setProperty("spring.datasource.password", password);
				}
				
				String host = dbUri.getHost();
				int port = dbUri.getPort();
				String path = dbUri.getPath();
				String query = dbUri.getQuery();
				
				String portStr = (port == -1) ? "5432" : String.valueOf(port);
				
				String dbUrl = "jdbc:postgresql://" + host + ":" + portStr + path;
				if (query != null && !query.trim().isEmpty()) {
					dbUrl += "?" + query;
				}
				
				System.setProperty("spring.datasource.url", dbUrl);
				
				System.out.println("--- Railway PostgreSQL Configuration Prepared ---");
				System.out.println("URL: " + dbUrl);
				System.out.println("Username: " + (userInfo != null ? userInfo.split(":")[0] : ""));
				System.out.println("-------------------------------------------------");
			} catch (Exception e) {
				System.err.println("Failed to parse Railway DATABASE_URL: " + e.getMessage());
			}
		} else {
			System.out.println("No DATABASE_URL environment variable found. Falling back to local configuration.");
		}
	}
  
}                                                                                                   