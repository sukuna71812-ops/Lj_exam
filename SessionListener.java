package University.exam.listener;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import University.exam.repository.StudentActiveSessionRepository;
import University.exam.Entity.StudentActiveSession;
import java.util.Optional;

@Component
public class SessionListener implements HttpSessionListener {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SessionListener.class);

    @Autowired
    private StudentActiveSessionRepository sessionRepository;

    @Override
    public void sessionCreated(HttpSessionEvent se) {
        // No action needed on creation
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        String sessionId = se.getSession().getId();
        try {
            Optional<StudentActiveSession> activeSessionOpt = sessionRepository.findBySessionId(sessionId);
            if (activeSessionOpt.isPresent()) {
                StudentActiveSession activeSession = activeSessionOpt.get();
                // Delete the session record to instantly free future logins
                sessionRepository.delete(activeSession);
                logger.info("Session destroyed: {} for student: {}", sessionId, activeSession.getStudentId());
            }
        } catch (Exception e) {
            logger.error("Error cleaning up session {}: {}", sessionId, e.getMessage());
        }
    }
}
