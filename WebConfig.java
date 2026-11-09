package University.exam;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import University.exam.interceptor.ActiveSessionInterceptor;
import University.exam.interceptor.CsrfInterceptor;

import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(WebConfig.class);

    @Autowired
    private ActiveSessionInterceptor activeSessionInterceptor;

    @Autowired
    private CsrfInterceptor csrfInterceptor;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Value("${app.upload.dir:}")
    private String configuredUploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadDir = configuredUploadDir;
        if (uploadDir == null || uploadDir.trim().isEmpty()) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                uploadDir = "C:/uploads/";
            } else {
                uploadDir = "/tmp/uploads/";
            }
        }

        // Ensure directories exist
        Path path = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                logger.error("Failed to create upload directory {}: {}", path, e.getMessage());
            }
        }

        // Convert path to file URI for Spring resource location
        String location = path.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/static/**", "/css/**", "/js/**", "/images/**", "/uploads/**");

        registry.addInterceptor(activeSessionInterceptor)
                .addPathPatterns("/student/**", "/api/**")
                .excludePathPatterns("/static/**", "/css/**", "/js/**", "/images/**");
    }
}
 