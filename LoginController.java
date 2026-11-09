package University.exam.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LoginController.class);

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.PaperRepository paperRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.StudentRepository studentRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.ExamRepository examRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.StudentActiveSessionRepository studentActiveSessionRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.AdminRepository adminRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.QuestionRepository questionRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.ExamAttemptRepository examAttemptRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.SubmissionRepository submissionRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.StudentLoginAttemptRepository studentLoginAttemptRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private University.exam.repository.ExamEligibleStudentRepository examEligibleStudentRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @GetMapping("/")
    public String login() {
        return "auth/student_login";
    }

    @GetMapping("/login")
    public String loginGetFallback() {
        return "redirect:/";
    }

    @org.springframework.web.bind.annotation.PostMapping("/login")
    public String performLogin(String enrollmentNo, String password, jakarta.servlet.http.HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        if (enrollmentNo == null || password == null) {
            return "redirect:/?error=invalid_credentials";
        }
        String trimmedEnrollment = enrollmentNo.trim();
        String trimmedPassword = password.trim();

        if (trimmedEnrollment.isEmpty() || trimmedPassword.isEmpty()) {
            return "redirect:/?error=invalid_credentials";
        }

        // Get request details early for logging and rate limiting
        String ipAddress = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        String browserInfo = getBrowserInfo(userAgent);
        String deviceInfo = getDeviceInfo(userAgent);

        // Rate limiting / lockout: check failed attempts within last 15 minutes
        java.time.LocalDateTime lockoutWindow = java.time.LocalDateTime.now().minusMinutes(15);
        long failedAttempts = studentLoginAttemptRepository.countByStudentIdAndResultAndAttemptTimeAfter(
            trimmedEnrollment, "FAILED", lockoutWindow
        );
        if (failedAttempts >= 5) {
            logger.warn("Student {} locked out due to {} failed attempts in last 15 min", trimmedEnrollment, failedAttempts);
            studentLoginAttemptRepository.save(new University.exam.Entity.StudentLoginAttempt(
                trimmedEnrollment, ipAddress, browserInfo, deviceInfo, "LOCKED"
            ));
            return "redirect:/?error=account_locked";
        }

        java.util.Optional<University.exam.Entity.Student> studentOpt = studentRepository.findByEnrollmentNo(trimmedEnrollment);
        if (studentOpt.isEmpty()) {
            studentLoginAttemptRepository.save(new University.exam.Entity.StudentLoginAttempt(
                trimmedEnrollment, ipAddress, browserInfo, deviceInfo, "FAILED"
            ));
            return "redirect:/?error=invalid_credentials";
        }
        University.exam.Entity.Student student = studentOpt.get();
        String dbPassword = student.getPassword();
        if (dbPassword == null) {
            studentLoginAttemptRepository.save(new University.exam.Entity.StudentLoginAttempt(
                trimmedEnrollment, ipAddress, browserInfo, deviceInfo, "FAILED"
            ));
            return "redirect:/?error=invalid_credentials";
        }

        boolean passwordMatches = false;
        boolean needsMigration = false;
        if (University.exam.util.PasswordUtil.isBCrypt(dbPassword)) {
            if (passwordEncoder.matches(password, dbPassword) || passwordEncoder.matches(trimmedPassword, dbPassword)) {
                passwordMatches = true;
            }
        } else {
            if (dbPassword.equals(password) || dbPassword.equals(trimmedPassword)) {
                passwordMatches = true;
                needsMigration = true;
            }
        }

        if (!passwordMatches) {
            studentLoginAttemptRepository.save(new University.exam.Entity.StudentLoginAttempt(
                trimmedEnrollment, ipAddress, browserInfo, deviceInfo, "FAILED"
            ));
            return "redirect:/?error=invalid_credentials";
        }

        if (needsMigration) {
            student.setPassword(passwordEncoder.encode(trimmedPassword));
            studentRepository.save(student);
        }

        // Enforce student eligibility check
        if (!isStudentEligibleForActiveExams(trimmedEnrollment, student.getSemester())) {
            return "redirect:/?error=not_eligible";
        }

        // Enforce single active session per student account: Check if an ACTIVE session already exists
        java.util.Optional<University.exam.Entity.StudentActiveSession> activeSessionOpt = 
            studentActiveSessionRepository.findByStudentIdAndStatus(trimmedEnrollment, "ACTIVE");
             
        if (activeSessionOpt.isPresent()) {
            University.exam.Entity.StudentActiveSession activeSession = activeSessionOpt.get();
            java.time.LocalDateTime lastActivity = activeSession.getLastActivity();
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusSeconds(60);

            // Allow re-login if the session is disconnected (inactive for >60s), OR if the login attempt is from the exact same device and browser
            boolean isSameDevice = ipAddress != null && ipAddress.equals(activeSession.getIpAddress())
                && browserInfo != null && browserInfo.equals(activeSession.getBrowserInfo());

            if (lastActivity != null && lastActivity.isAfter(cutoff) && !isSameDevice) {
                // Block login immediately since the session is actively connected on another device
                logger.warn("[Student Login Blocked] Enrollment: {} already active (lastActivity: {})", trimmedEnrollment, lastActivity);
                University.exam.Entity.StudentLoginAttempt attempt = new University.exam.Entity.StudentLoginAttempt(
                    trimmedEnrollment, ipAddress, browserInfo, deviceInfo, "BLOCKED"
                );
                studentLoginAttemptRepository.save(attempt);
                return "redirect:/?error=already_logged_in";
            } else {
                // The session is disconnected (no activity for >60s), OR the same student is reconnecting from the same device.
                // Mark the old active session record as COMPLETED so they can login again cleanly
                activeSession.setStatus("COMPLETED");
                activeSession.setLogoutTime(java.time.LocalDateTime.now());
                studentActiveSessionRepository.save(activeSession);
                studentActiveSessionRepository.flush();
                logger.info("[Student Login] Re-login allowed for enrollment: {} (previous session was inactive or matching device)", trimmedEnrollment);
            }
        }

        // Call request.changeSessionId() at login to prevent session fixation and update the stored id there
        String newSessionId = request.changeSessionId();
        jakarta.servlet.http.HttpSession currentSession = request.getSession();

        // Log successful login attempt
        logger.info("[Student Login] Enrollment: {} logged in from IP: {} (Session ID: {})", trimmedEnrollment, ipAddress, newSessionId);
        University.exam.Entity.StudentLoginAttempt attempt = new University.exam.Entity.StudentLoginAttempt(
            trimmedEnrollment, ipAddress, browserInfo, deviceInfo, "SUCCESS"
        );
        studentLoginAttemptRepository.save(attempt);

        // Clean up any existing session record with the same session ID to prevent unique constraint violation
        studentActiveSessionRepository.findBySessionId(newSessionId).ifPresent(s -> {
            studentActiveSessionRepository.delete(s);
            studentActiveSessionRepository.flush();
        });

        // Register the new active session
        University.exam.Entity.StudentActiveSession newSession = new University.exam.Entity.StudentActiveSession(
            trimmedEnrollment, newSessionId, ipAddress, browserInfo, deviceInfo
        );
        newSession.setStatus("ACTIVE");
        studentActiveSessionRepository.save(newSession);

        // Mock authentication
        currentSession.setAttribute("loggedInStudent", trimmedEnrollment);
        currentSession.setAttribute("enrollment_no", trimmedEnrollment);
        
        // Redirect to smart routing page which validates semester and redirects appropriately
        return "redirect:/student/rules";
    }

    private boolean isStudentEligibleForActiveExams(String enrollmentNo, String studentSem) {
        // 1. If there are any eligible students imported in the database, the student must exist in the ExamEligibleStudent repository.
        long totalEligibleCount = examEligibleStudentRepository.count();
        if (totalEligibleCount > 0) {
            boolean existsAtAll = examEligibleStudentRepository.existsByEnrollmentNo(enrollmentNo);
            if (!existsAtAll) {
                return false;
            }
        }

        String digit = (studentSem != null) ? studentSem.replaceAll("\\D+", "") : "";
        String semSafe = (studentSem != null) ? studentSem.trim() : "";

        // 2. Check if there is an active paper matching their semester via repository queries
        java.util.List<University.exam.Entity.Paper> papers = paperRepository.findActiveBySemester("ENDED", semSafe, digit);
        if (papers != null) {
            for (University.exam.Entity.Paper p : papers) {
                if (University.exam.Entity.Student.matchesSemester(studentSem, p.getSemester())) {
                    if (questionRepository.existsByPaperId(p.getId())) {
                        long count = examEligibleStudentRepository.countByExamId(p.getId());
                        if (count > 0 && !examEligibleStudentRepository.existsByExamIdAndEnrollmentNo(p.getId(), enrollmentNo)) {
                            return false;
                        }
                    }
                }
            }
        }

        // 3. Check if there is an active traditional exam matching their semester via repository queries
        java.util.List<University.exam.Entity.Exam> exams = examRepository.findActiveBySemester("ENDED", semSafe, digit);
        if (exams != null) {
            for (University.exam.Entity.Exam e : exams) {
                if (University.exam.Entity.Student.matchesSemester(studentSem, e.getSemester())) {
                    if (questionRepository.existsByExamId(e.getId())) {
                        long count = examEligibleStudentRepository.countByExamId(e.getId());
                        if (count > 0 && !examEligibleStudentRepository.existsByExamIdAndEnrollmentNo(e.getId(), enrollmentNo)) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    private String getBrowserInfo(String userAgent) {
        if (userAgent == null) return "Unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("edg")) return "Edge";
        if (ua.contains("chrome") && !ua.contains("chromium")) return "Chrome";
        if (ua.contains("safari") && !ua.contains("chrome")) return "Safari";
        if (ua.contains("firefox")) return "Firefox";
        if (ua.contains("opr") || ua.contains("opera")) return "Opera";
        return "Browser";
    }

    private String getDeviceInfo(String userAgent) {
        if (userAgent == null) return "Unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("android")) return "Android Device";
        if (ua.contains("iphone") || ua.contains("ipad")) return "iOS Device";
        if (ua.contains("windows")) return "Windows PC";
        if (ua.contains("macintosh") || ua.contains("mac os x")) return "macOS Device";
        if (ua.contains("linux")) return "Linux PC";
        return "Mobile/Desktop";
    }

    @org.springframework.web.bind.annotation.PostMapping("/student/logout")
    public String studentLogout(jakarta.servlet.http.HttpSession session) {
        if (session != null) {
            String enrollmentNo = (String) session.getAttribute("loggedInStudent");
            if (enrollmentNo != null) {
                studentActiveSessionRepository.findByStudentIdAndStatus(enrollmentNo, "ACTIVE").ifPresent(activeSession -> {
                    activeSession.setStatus("COMPLETED");
                    activeSession.setLogoutTime(java.time.LocalDateTime.now());
                    studentActiveSessionRepository.save(activeSession);
                });
            }
            session.invalidate();
        }
        return "redirect:/";
    }

    @GetMapping("/student/logout")
    public String studentLogoutGetFallback(jakarta.servlet.http.HttpSession session) {
        return studentLogout(session);
    }

    @org.springframework.web.bind.annotation.PostMapping("/logout")
    public String logout(jakarta.servlet.http.HttpSession session) {
        if (session != null) {
            String enrollmentNo = (String) session.getAttribute("loggedInStudent");
            if (enrollmentNo != null) {
                studentActiveSessionRepository.findByStudentIdAndStatus(enrollmentNo, "ACTIVE").ifPresent(activeSession -> {
                    activeSession.setStatus("COMPLETED");
                    activeSession.setLogoutTime(java.time.LocalDateTime.now());
                    studentActiveSessionRepository.save(activeSession);
                });
            }
            session.invalidate();
        }
        return "redirect:/";
    }

    @GetMapping("/logout")
    public String logoutGetFallback(jakarta.servlet.http.HttpSession session) {
        return logout(session);
    }

    @org.springframework.web.bind.annotation.PostMapping("/admin/logout")
    public String adminLogout(jakarta.servlet.http.HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/admin-login";
    }

    @GetMapping("/admin/logout")
    public String adminLogoutGetFallback(jakarta.servlet.http.HttpSession session) {
        return adminLogout(session);
    }

 
    @GetMapping("/student/rules")
    public String genericRules(jakarta.servlet.http.HttpSession session, org.springframework.ui.Model model, @org.springframework.web.bind.annotation.RequestParam(name = "error", required = false) String error) {
        if (session.getAttribute("loggedInStudent") == null) return "redirect:/";

        String enrollmentNo = (String) session.getAttribute("loggedInStudent");
        
        // Check for ongoing attempt/submission to force resume screen
        java.util.List<University.exam.Entity.ExamAttempt> attempts = examAttemptRepository.findByStudentEnrollmentNo(enrollmentNo);
        if (attempts != null) {
            for (University.exam.Entity.ExamAttempt attempt : attempts) {
                if ("Ongoing".equals(attempt.getStatus())) {
                    return "redirect:/student/exam/resume";
                }
            }
        }
        java.util.List<University.exam.Entity.Submission> submissions = submissionRepository.findByStudentEnrollmentNo(enrollmentNo);
        if (submissions != null) {
            for (University.exam.Entity.Submission sub : submissions) {
                if ("Ongoing".equals(sub.getStatus())) {
                    return "redirect:/student/exam/resume";
                }
            }
        }

        University.exam.Entity.Student student = studentRepository.findByEnrollmentNo(enrollmentNo).orElse(null);
        if (student == null) {
            return "redirect:/";
        }
        String studentSem = student.getSemester();

        // Check if admin has uploaded a paper now (filtered by semester)
        java.util.List<University.exam.Entity.Paper> papers = paperRepository.findAll();
        java.util.List<University.exam.Entity.Paper> matchingPapers = new java.util.ArrayList<>();
        if (papers != null) {
            for (University.exam.Entity.Paper p : papers) {
                if (University.exam.Entity.Student.matchesSemester(studentSem, p.getSemester()) && !"ENDED".equals(p.getExamStatus())) {
                    // Avoid redirect loops by making sure the paper has questions
                    java.util.List<University.exam.Entity.Question> questions = questionRepository.findByPaperId(p.getId());
                    if (questions != null && !questions.isEmpty()) {
                        matchingPapers.add(p);
                    }
                }
            }
        }

        if (!matchingPapers.isEmpty()) {
            matchingPapers.sort((p1, p2) -> p2.getId().compareTo(p1.getId()));
            University.exam.Entity.Paper targetPaper = matchingPapers.get(0);
            // --- Eligibility Check for PDF-Based Paper ---
            long eligibleCountForPaper = examEligibleStudentRepository.countByExamId(targetPaper.getId());
            if (eligibleCountForPaper > 0) {
                // An eligibility list exists — check if this student is on it
                if (!examEligibleStudentRepository.existsByExamIdAndEnrollmentNo(targetPaper.getId(), enrollmentNo)) {
                    // Student not eligible
                    model.addAttribute("enrollmentNo", enrollmentNo);
                    model.addAttribute("examName", targetPaper.getSubject() + " (Sem " + targetPaper.getSemester() + ")");
                    return "student/not_eligible";
                }
            }
            return "redirect:/student/exam/paper-rules/" + targetPaper.getId();
        }

        // Fallback: If no papers, find the latest traditional exam matching their semester
        java.util.List<University.exam.Entity.Exam> exams = examRepository.findAll();
        java.util.List<University.exam.Entity.Exam> matchingExams = new java.util.ArrayList<>();
        if (exams != null) {
            for (University.exam.Entity.Exam e : exams) {
                if (University.exam.Entity.Student.matchesSemester(studentSem, e.getSemester()) && !"ENDED".equals(e.getExamStatus())) {
                    // Avoid redirect loops by making sure the exam has questions
                    java.util.List<University.exam.Entity.Question> questions = questionRepository.findByExamId(e.getId());
                    if (questions != null && !questions.isEmpty()) {
                        matchingExams.add(e);
                    }
                }
            }
        }

        if (!matchingExams.isEmpty()) {
            matchingExams.sort((e1, e2) -> e2.getId().compareTo(e1.getId()));
            University.exam.Entity.Exam targetExam = matchingExams.get(0);
            // --- Eligibility Check for Traditional Exam ---
            long eligibleCountForExam = examEligibleStudentRepository.countByExamId(targetExam.getId());
            if (eligibleCountForExam > 0) {
                // An eligibility list exists — check if this student is on it
                if (!examEligibleStudentRepository.existsByExamIdAndEnrollmentNo(targetExam.getId(), enrollmentNo)) {
                    // Student not eligible
                    model.addAttribute("enrollmentNo", enrollmentNo);
                    model.addAttribute("examName", targetExam.getExamName() + " (Sem " + targetExam.getSemester() + ")");
                    return "student/not_eligible";
                }
            }
            return "redirect:/student/exam/rules/" + targetExam.getId();
        }

        // Create a mock exam so the rules.html template doesn't crash
        University.exam.Entity.Exam mockExam = new University.exam.Entity.Exam();
        mockExam.setId(0L); // Use 0 to indicate it's a mock
        mockExam.setExamName("Waiting for Exam...");
        mockExam.setSubject("Please wait for the admin to upload the paper.");
        mockExam.setExamDuration(120);
        mockExam.setTotalMarks(100.0);
        
        if (error != null) {
            model.addAttribute("error", error);
        }
        
        model.addAttribute("exam", mockExam);
        model.addAttribute("isFallback", true);
        return "student/rules";
    }

    @GetMapping("/student/rules/start")
    public String startFallbackExam(jakarta.servlet.http.HttpSession session) {
        if (session.getAttribute("loggedInStudent") == null) return "redirect:/";

        // Check if a paper has been uploaded
        java.util.List<University.exam.Entity.Paper> papers = paperRepository.findAll();
        if (papers != null && !papers.isEmpty()) {
            papers.sort((p1, p2) -> p2.getId().compareTo(p1.getId()));
            return "redirect:/student/exam/confirm-paper/" + papers.get(0).getId();
        }

        // If still no paper, redirect back with error
        return "redirect:/student/rules?error=Exam is not available yet. Please wait.";
    }

    @GetMapping("/admin-login")
    public String adminLogin() {
        return "auth/admin_login";
    }

    @GetMapping("/admim-login")
    public String admimLoginRedirect() {
        return "redirect:/admin-login";
    }

    @org.springframework.web.bind.annotation.PostMapping("/admin-login")
    public String performAdminLogin(String adminName, String password, jakarta.servlet.http.HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        logger.debug("performAdminLogin called with adminName={}", adminName);
        if (adminName == null || password == null) {
            logger.warn("Admin login failed: adminName or password is null");
            return "redirect:/admin-login?error=invalid_credentials";
        }

        String trimmedAdminName = adminName.trim();
        String trimmedPassword = password.trim();

        String ipAddress = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        String browserInfo = getBrowserInfo(userAgent);
        String deviceInfo = getDeviceInfo(userAgent);

        String adminIdentifier = "admin:" + trimmedAdminName;
        java.time.LocalDateTime lockoutWindow = java.time.LocalDateTime.now().minusMinutes(15);
        long failedAttempts = studentLoginAttemptRepository.countByStudentIdAndResultAndAttemptTimeAfter(
            adminIdentifier, "FAILED", lockoutWindow
        );
        if (failedAttempts >= 5) {
            logger.warn("Admin {} locked out due to {} failed attempts in last 15 min", trimmedAdminName, failedAttempts);
            studentLoginAttemptRepository.save(new University.exam.Entity.StudentLoginAttempt(
                adminIdentifier, ipAddress, browserInfo, deviceInfo, "LOCKED"
            ));
            return "redirect:/admin-login?error=account_locked";
        }
        
        java.util.List<University.exam.Entity.Admin> admins = adminRepository.findByAdminNameIgnoreCase(trimmedAdminName);
        if (admins != null && !admins.isEmpty()) {
            for (University.exam.Entity.Admin admin : admins) {
                String dbPassword = admin.getPassword();
                boolean passwordMatches = false;
                boolean needsMigration = false;
                if (dbPassword != null) {
                    if (University.exam.util.PasswordUtil.isBCrypt(dbPassword)) {
                        if (passwordEncoder.matches(password, dbPassword) || passwordEncoder.matches(trimmedPassword, dbPassword)) {
                            passwordMatches = true;
                        }
                    } else {
                        if (dbPassword.equals(password) || dbPassword.equals(trimmedPassword)) {
                            passwordMatches = true;
                            needsMigration = true;
                        }
                    }
                }
                if (passwordMatches) {
                    if (needsMigration) {
                        admin.setPassword(passwordEncoder.encode(trimmedPassword));
                        adminRepository.save(admin);
                    }
                    if (request != null) {
                        request.changeSessionId();
                    }
                    jakarta.servlet.http.HttpSession currentSession = request != null ? request.getSession() : session;
                    currentSession.setAttribute("loggedInAdmin", admin.getAdminName());
                    logger.info("Admin {} logged in successfully", admin.getAdminName());
                    studentLoginAttemptRepository.save(new University.exam.Entity.StudentLoginAttempt(
                        adminIdentifier, ipAddress, browserInfo, deviceInfo, "SUCCESS"
                    ));
                    return "redirect:/admin/dashboard";
                }
            }
            // If admin exists in database but password check fails, reject directly
            logger.warn("Admin login failed: password mismatch for admin {}", trimmedAdminName);
            studentLoginAttemptRepository.save(new University.exam.Entity.StudentLoginAttempt(
                adminIdentifier, ipAddress, browserInfo, deviceInfo, "FAILED"
            ));
            return "redirect:/admin-login?error=invalid_credentials";
        } else {
            logger.warn("Admin login failed: admin not found for name {}", trimmedAdminName);
        }

        logger.warn("Admin login failed, redirecting back with error.");
        studentLoginAttemptRepository.save(new University.exam.Entity.StudentLoginAttempt(
            adminIdentifier, ipAddress, browserInfo, deviceInfo, "FAILED"
        ));
        return "redirect:/admin-login?error=invalid_credentials";
    } 
}