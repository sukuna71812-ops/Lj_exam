package University.exam.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import University.exam.Entity.StudentActiveSession;
import University.exam.repository.StudentActiveSessionRepository;
import University.exam.service.StudentExamActivityService;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class ActiveSessionInterceptor implements HandlerInterceptor {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ActiveSessionInterceptor.class);

    @Autowired
    private StudentActiveSessionRepository studentActiveSessionRepository;

    @Autowired
    private University.exam.repository.ExamAttemptRepository examAttemptRepository;

    @Autowired
    private University.exam.repository.SubmissionRepository submissionRepository;

    @Autowired
    private StudentExamActivityService studentExamActivityService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return true;
        }

        String enrollmentNo = (String) session.getAttribute("loggedInStudent");
        if (enrollmentNo == null) {
            return true;
        }

        String uri = request.getRequestURI();
        if (uri.contains("/student/logout") || uri.contains("/logout")) {
            return true;
        }

        // SESSION LOCK: Enforce that stored sessionId must match current HTTP session id
        Optional<StudentActiveSession> activeSessionOpt = 
            studentActiveSessionRepository.findByStudentIdAndStatus(enrollmentNo, "ACTIVE");

        if (activeSessionOpt.isEmpty()) {
            logger.warn("ActiveSessionInterceptor: No ACTIVE session found in DB for student {}. Rejecting request.", enrollmentNo);
            session.invalidate();
            if (uri.contains("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Unauthorized: No active session\"}");
            } else {
                response.sendRedirect(request.getContextPath() + "/?error=session_expired");
            }
            return false;
        }

        StudentActiveSession activeSession = activeSessionOpt.get();
        if (!activeSession.getSessionId().equals(session.getId())) {
            logger.warn("ActiveSessionInterceptor: Stored sessionId [{}] differs from current sessionId [{}] for student {}. Rejecting request.",
                activeSession.getSessionId(), session.getId(), enrollmentNo);
            session.invalidate();
            if (uri.contains("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Unauthorized: Session expired or replaced\"}");
            } else {
                response.sendRedirect(request.getContextPath() + "/?error=session_expired");
            }
            return false;
        }

        // Auto-register/refresh exam activity
        try {
            Long currentExamId = (Long) session.getAttribute("currentExamId");
            String statusToSet = (currentExamId == null) ? "LoggedInNotStarted" : null;
            studentExamActivityService.updateActivity(enrollmentNo, currentExamId, null, null, null, statusToSet);
        } catch (Exception e) {
            logger.error("Failed to update student activity in interceptor: {}", e.getMessage());
        }

        if (uri.contains("/student/exam/resume") || uri.contains("/api/drawing/save")) {
            activeSession.setLastActivity(LocalDateTime.now());
            studentActiveSessionRepository.save(activeSession);
            return true;
        }

        // Find ongoing attempts/submissions
        University.exam.Entity.ExamAttempt ongoingAttempt = null;
        java.util.List<University.exam.Entity.ExamAttempt> attempts = examAttemptRepository.findByStudentEnrollmentNo(enrollmentNo);
        if (attempts != null) {
            for (University.exam.Entity.ExamAttempt a : attempts) {
                if ("Ongoing".equals(a.getStatus())) {
                    ongoingAttempt = a;
                    break;
                }
            }
        }

        University.exam.Entity.Submission ongoingSubmission = null;
        java.util.List<University.exam.Entity.Submission> submissions = submissionRepository.findByStudentEnrollmentNo(enrollmentNo);
        if (submissions != null) {
            for (University.exam.Entity.Submission s : submissions) {
                if ("Ongoing".equals(s.getStatus())) {
                    ongoingSubmission = s;
                    break;
                }
            }
        }

        if (ongoingAttempt != null || ongoingSubmission != null) {
            boolean isPaused = ongoingAttempt != null ? Boolean.TRUE.equals(ongoingAttempt.getIsPaused()) : Boolean.TRUE.equals(ongoingSubmission.getIsPaused());
            if (isPaused) {
                if (uri.contains("/api/")) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.getWriter().write("{\"status\": \"paused\"}");
                } else {
                    response.sendRedirect(request.getContextPath() + "/student/exam/resume");
                }
                return false;
            }

            // Check for inactivity/offline gap since last request
            LocalDateTime lastAct = activeSession.getLastActivity();
            if (lastAct != null) {
                long gap = java.time.Duration.between(lastAct, LocalDateTime.now()).getSeconds();
                boolean isSaveOrActivity = uri.contains("/api/answer/save") || uri.contains("/student/update-activity");
                boolean recentlySaved = false;
                if (ongoingAttempt != null && ongoingAttempt.getLastSavedAt() != null) {
                    recentlySaved = java.time.Duration.between(ongoingAttempt.getLastSavedAt(), LocalDateTime.now()).getSeconds() <= 300;
                } else if (ongoingSubmission != null && ongoingSubmission.getLastSavedAt() != null) {
                    recentlySaved = java.time.Duration.between(ongoingSubmission.getLastSavedAt(), LocalDateTime.now()).getSeconds() <= 300;
                }

                if (gap > 300 && !isSaveOrActivity && !recentlySaved) {
                    if (ongoingAttempt != null) {
                        ongoingAttempt.setIsPaused(true);
                        ongoingAttempt.setPausedAt(lastAct);
                        ongoingAttempt.setPauseCount((ongoingAttempt.getPauseCount() != null ? ongoingAttempt.getPauseCount() : 0) + 1);
                        
                        long totalSeconds = ongoingAttempt.getExam().getExamDuration() != null ? ongoingAttempt.getExam().getExamDuration() * 60 : 3600;
                        long elapsed = java.time.Duration.between(ongoingAttempt.getStartTime(), lastAct).getSeconds();
                        int remaining = (int) Math.max(0, totalSeconds - elapsed);
                        ongoingAttempt.setRemainingTimeSeconds(remaining);
                        
                        examAttemptRepository.save(ongoingAttempt);
                    } else {
                        ongoingSubmission.setIsPaused(true);
                        ongoingSubmission.setPausedAt(lastAct);
                        ongoingSubmission.setPauseCount((ongoingSubmission.getPauseCount() != null ? ongoingSubmission.getPauseCount() : 0) + 1);

                        long totalSeconds = (ongoingSubmission.getPaper().getExamDuration() != null ? ongoingSubmission.getPaper().getExamDuration() : 120) * 60;
                        long elapsed = java.time.Duration.between(ongoingSubmission.getStartTime(), lastAct).getSeconds();
                        int remaining = (int) Math.max(0, totalSeconds - elapsed);
                        ongoingSubmission.setRemainingTimeSeconds(remaining);

                        submissionRepository.save(ongoingSubmission);
                    }
                    
                    if (uri.contains("/api/")) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.getWriter().write("{\"status\": \"paused\"}");
                    } else {
                        response.sendRedirect(request.getContextPath() + "/student/exam/resume");
                    }
                    return false;
                }
            }
        }

        // Update last activity timestamp and save
        activeSession.setLastActivity(LocalDateTime.now());
        studentActiveSessionRepository.save(activeSession);

        return true;
    }
}
