package University.exam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExamApplication {

	@jakarta.annotation.PostConstruct
	public void init() {
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
	}

	public static void main(String[] args) {
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
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
				
				System.out.println("--- Railway PostgreSQL Configuration Prepared from DATABASE_URL ---");
				System.out.println("URL: " + dbUrl);
				System.out.println("Username: " + (userInfo != null ? userInfo.split(":")[0] : ""));
				System.out.println("------------------------------------------------------------------");
				return;
			} catch (Exception e) {
				System.err.println("Failed to parse Railway DATABASE_URL: " + e.getMessage());
			}
		}

		// Fallback to individual PG* environment variables if present
		String pgHost = System.getenv("PGHOST");
		String pgPort = System.getenv("PGPORT");
		String pgDatabase = System.getenv("PGDATABASE");
		String pgUser = System.getenv("PGUSER");
		String pgPassword = System.getenv("PGPASSWORD");

		if (pgHost != null && !pgHost.trim().isEmpty() && pgUser != null && !pgUser.trim().isEmpty()) {
			String port = (pgPort == null || pgPort.trim().isEmpty()) ? "5432" : pgPort;
			String database = (pgDatabase == null || pgDatabase.trim().isEmpty()) ? "postgres" : pgDatabase;
			String dbUrl = "jdbc:postgresql://" + pgHost + ":" + port + "/" + database;

			System.setProperty("spring.datasource.url", dbUrl);
			System.setProperty("spring.datasource.username", pgUser);
			System.setProperty("spring.datasource.password", pgPassword != null ? pgPassword : "");

			System.out.println("--- Railway PostgreSQL Configuration Prepared from PG* Environment Variables ---");
			System.out.println("URL: " + dbUrl);
			System.out.println("Username: " + pgUser);
			System.out.println("--------------------------------------------------------------------------------");
		} else {
			System.out.println("No DATABASE_URL or PG* environment variables found. Falling back to local configuration.");
		}
	}
  
}                                                                                                         