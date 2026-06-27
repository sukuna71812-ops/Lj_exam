package University.exam.controller;

import University.exam.Entity.*;
import University.exam.repository.*;
import University.exam.service.StudentExamActivityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class LiveMonitorController {

    @Autowired
    private StudentExamActivityService studentExamActivityService;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private StudentActiveSessionRepository studentActiveSessionRepository;

    @Autowired
    private ExamEligibleStudentRepository examEligibleStudentRepository;

    @Autowired
    private ExamAttemptRepository examAttemptRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private AnswerRepository answerRepository;

    @Autowired
    private ResultRepository resultRepository;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private ExamRepository examRepository;

    private String getRoomNoFromIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return "Local";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return "Room " + parts[2];
        }
        return "N/A";
    }

    private String getComputerNoFromIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return "PC-Local";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return "PC-" + parts[3];
        }
        return "N/A";
    }

    private Admin getLoggedInAdmin(HttpSession session) {
        if (session == null) {
            return null;
        }
        String adminName = (String) session.getAttribute("loggedInAdmin");
        if (adminName == null) {
            return null;
        }
        List<Admin> admins = adminRepository.findByAdminNameIgnoreCase(adminName.trim());
        if (admins != null && !admins.isEmpty()) {
            return admins.get(0);
        }
        return null;
    }

    private void addAdminAttributes(HttpSession session, Model model) {
        String adminName = (String) session.getAttribute("loggedInAdmin");
        model.addAttribute("adminName", adminName != null ? adminName : "Super Admin");
        model.addAttribute("logoUrl", "/images/logo.png");
    }

    @GetMapping("/admin/live-monitor")
    public Object liveMonitor(
            @RequestParam(value = "examSelect", required = false) String examSelect,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "division", required = false) String division,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "format", required = false) String format,
            HttpServletRequest request,
            HttpSession session,
            Model model) {

        Admin admin = getLoggedInAdmin(session);
        boolean isJson = "json".equalsIgnoreCase(format) || 
                         (request.getHeader("Accept") != null && request.getHeader("Accept").contains("application/json"));

        if (admin == null) {
            if (isJson) {
                return ResponseEntity.status(401).body("Unauthorized");
            }
            return "redirect:/admin-login";
        }

        System.out.println("[Dashboard Refresh Log] Refreshing live monitor dashboard. Request URI: " + request.getRequestURI());

        // 1. Resolve examSelect to type and examId
        String type = null;
        Long examId = null;

        if (examSelect != null && examSelect.contains(":")) {
            String[] parts = examSelect.split(":");
            type = parts[0];
            examId = Long.parseLong(parts[1]);
        } else {
            // Default selection: find an ACTIVE paper or exam first
            List<Paper> activePapers = paperRepository.findAll().stream()
                    .filter(p -> "ACTIVE".equals(p.getExamStatus()))
                    .collect(Collectors.toList());
            List<Exam> activeExams = examRepository.findAll().stream()
                    .filter(e -> "ACTIVE".equals(e.getExamStatus()))
                    .collect(Collectors.toList());

            if (!activePapers.isEmpty()) {
                type = "paper";
                examId = activePapers.get(0).getId();
            } else if (!activeExams.isEmpty()) {
                type = "exam";
                examId = activeExams.get(0).getId();
            } else {
                // Fallback to latest paper or exam in the database
                List<Paper> papers = paperRepository.findAll();
                List<Exam> exams = examRepository.findAll();
                if (!papers.isEmpty()) {
                    papers.sort((p1, p2) -> p2.getId().compareTo(p1.getId()));
                    type = "paper";
                    examId = papers.get(0).getId();
                } else if (!exams.isEmpty()) {
                    exams.sort((e1, e2) -> e2.getId().compareTo(e1.getId()));
                    type = "exam";
                    examId = exams.get(0).getId();
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        final Long finalExamId = examId;
        final String finalType = type;
        final LocalDateTime finalNow = now;

        // 2. Automatically mark the exam/paper as completed if the duration has expired
        if (finalExamId != null && finalType != null) {
            if ("paper".equals(finalType)) {
                paperRepository.findById(finalExamId).ifPresent(paper -> {
                    if ("ACTIVE".equals(paper.getExamStatus()) && paper.getActivationTime() != null && paper.getExamDuration() != null) {
                        LocalDateTime endTime = paper.getActivationTime().plusMinutes(paper.getExamDuration());
                        if (finalNow.isAfter(endTime)) {
                            System.out.println("[Exam Timer Log] Expiration detected during live-monitor load. Auto-ending Paper ID: " + finalExamId);
                            paper.setExamStatus("COMPLETED");
                            paper.setEndTime(finalNow);
                            paperRepository.save(paper);
                            
                            // Auto-submit all ongoing student submissions
                            List<Submission> submissions = submissionRepository.findByPaperId(finalExamId);
                            for (Submission sub : submissions) {
                                if ("Ongoing".equals(sub.getStatus())) {
                                    sub.setStatus("Submitted");
                                    sub.setSubmittedAt(finalNow);
                                    submissionRepository.save(sub);

                                    // Save empty answers for unanswered questions
                                    List<Question> questions = questionRepository.findByPaperId(finalExamId);
                                    List<Answer> existingAnswers = answerRepository.findBySubmissionId(sub.getId());
                                    Set<Long> answeredQuestionIds = new java.util.HashSet<>();
                                    if (existingAnswers != null) {
                                        for (Answer ans : existingAnswers) {
                                            if (ans.getQuestion() != null) {
                                                answeredQuestionIds.add(ans.getQuestion().getId());
                                            }
                                        }
                                    }
                                    if (questions != null) {
                                        for (Question q : questions) {
                                            if (!answeredQuestionIds.contains(q.getId())) {
                                                Answer ans = new Answer();
                                                ans.setSubmission(sub);
                                                ans.setQuestion(q);
                                                ans.setQuestionText(q.getText());
                                                ans.setStudentAnswer("");
                                                ans.setMaxMarks(q.getMarks() != null ? q.getMarks() : 0.0);
                                                ans.setMarksObtained(0.0);
                                                ans.setUpdatedAt(finalNow);
                                                answerRepository.save(ans);
                                            }
                                        }
                                    }

                                    // Terminate student session
                                    if (sub.getStudent() != null) {
                                        studentActiveSessionRepository.findByStudentIdAndStatus(sub.getStudent().getEnrollmentNo(), "ACTIVE")
                                            .ifPresent(as -> {
                                                as.setStatus("COMPLETED");
                                                as.setLogoutTime(finalNow);
                                                studentActiveSessionRepository.save(as);
                                            });
                                        try {
                                            studentExamActivityService.updateActivity(sub.getStudent().getEnrollmentNo(), finalExamId, null, null, "00:00:00", "Submitted");
                                        } catch (Exception e) {}
                                    }
                                }
                            }
                            submissionRepository.flush();
                        }
                    }
                });
            } else {
                examRepository.findById(finalExamId).ifPresent(exam -> {
                    if ("ACTIVE".equals(exam.getExamStatus()) && exam.getActivationTime() != null && exam.getExamDuration() != null) {
                        LocalDateTime endTime = exam.getActivationTime().plusMinutes(exam.getExamDuration());
                        if (finalNow.isAfter(endTime)) {
                            System.out.println("[Exam Timer Log] Expiration detected during live-monitor load. Auto-ending Exam ID: " + finalExamId);
                            exam.setExamStatus("COMPLETED");
                            exam.setEndTime(finalNow);
                            examRepository.save(exam);
                            
                            // Auto-submit all ongoing student attempts
                            List<ExamAttempt> attempts = examAttemptRepository.findByExamId(finalExamId);
                            for (ExamAttempt attempt : attempts) {
                                if ("Ongoing".equals(attempt.getStatus())) {
                                    attempt.setStatus("Submitted");
                                    attempt.setEndTime(finalNow);
                                    examAttemptRepository.save(attempt);

                                    // Save empty answers for unanswered questions
                                    List<Question> questions = questionRepository.findByExamId(finalExamId);
                                    List<Answer> existingAnswers = answerRepository.findByExamAttemptId(attempt.getId());
                                    Set<Long> answeredQuestionIds = new java.util.HashSet<>();
                                    if (existingAnswers != null) {
                                        for (Answer ans : existingAnswers) {
                                            if (ans.getQuestion() != null) {
                                                answeredQuestionIds.add(ans.getQuestion().getId());
                                            }
                                        }
                                    }
                                    if (questions != null) {
                                        for (Question q : questions) {
                                            if (!answeredQuestionIds.contains(q.getId())) {
                                                Answer ans = new Answer();
                                                ans.setExamAttempt(attempt);
                                                ans.setQuestion(q);
                                                ans.setQuestionText(q.getText());
                                                ans.setStudentAnswer("");
                                                ans.setMaxMarks(q.getMarks() != null ? q.getMarks() : 0.0);
                                                ans.setMarksObtained(0.0);
                                                ans.setUpdatedAt(finalNow);
                                                answerRepository.save(ans);
                                            }
                                        }
                                    }

                                    // Terminate student session
                                    if (attempt.getStudent() != null) {
                                        studentActiveSessionRepository.findByStudentIdAndStatus(attempt.getStudent().getEnrollmentNo(), "ACTIVE")
                                            .ifPresent(as -> {
                                                as.setStatus("COMPLETED");
                                                as.setLogoutTime(finalNow);
                                                studentActiveSessionRepository.save(as);
                                            });
                                        try {
                                            studentExamActivityService.updateActivity(attempt.getStudent().getEnrollmentNo(), finalExamId, null, null, "00:00:00", "Submitted");
                                        } catch (Exception e) {}
                                    }
                                }
                            }
                            examAttemptRepository.flush();
                        }
                    }
                });
            }
        }

        // 3. Gather eligible students for this selected exam
        List<ExamEligibleStudent> eligibleStudents = new ArrayList<>();
        if (finalExamId != null && finalType != null) {
            String dbExamType = "paper".equalsIgnoreCase(finalType) ? "PAPER" : "EXAM";
            eligibleStudents = examEligibleStudentRepository.findByExamId(finalExamId).stream()
                    .filter(es -> dbExamType.equalsIgnoreCase(es.getExamType()))
                    .collect(Collectors.toList());
        }

        // 4. Construct complete student monitoring data map (merging eligible students and actual attempts)
        Map<String, Map<String, Object>> studentMap = new LinkedHashMap<>();

        // Initialize with eligible list
        for (ExamEligibleStudent es : eligibleStudents) {
            if (es.getEnrollmentNo() != null && !es.getEnrollmentNo().trim().isEmpty()) {
                String enroll = es.getEnrollmentNo().trim();
                Map<String, Object> map = new HashMap<>();
                map.put("studentName", es.getStudentName());
                map.put("enrollmentNo", enroll);
                map.put("division", es.getDivision() != null ? es.getDivision() : "N/A");
                map.put("semester", es.getSemester() != null ? es.getSemester() : "N/A");
                map.put("rollNo", es.getRollNo() != null ? es.getRollNo() : "N/A");
                map.put("currentSection", "N/A");
                map.put("currentQuestionNo", "N/A");
                map.put("timeRemaining", "N/A");
                map.put("status", "NotLoggedIn");
                map.put("lastActivity", "N/A");
                map.put("progress", 0);
                map.put("loginTime", "N/A");
                map.put("lastSaveTime", "N/A");
                map.put("connectionStatus", "Disconnected");
                studentMap.put(enroll, map);
            }
        }

        // Merge actual started student attempts
        int totalQuestions = 0;
        if (examId != null && type != null) {
            totalQuestions = "paper".equals(type) ? questionRepository.findByPaperId(examId).size() : questionRepository.findByExamId(examId).size();
        }

        if (examId != null && "paper".equals(type)) {
            List<Submission> submissions = submissionRepository.findByPaperId(examId);
            for (Submission sub : submissions) {
                Student s = sub.getStudent();
                if (s == null) continue;
                String enroll = s.getEnrollmentNo().trim();
                studentMap.putIfAbsent(enroll, new HashMap<>());
                Map<String, Object> map = studentMap.get(enroll);

                map.put("studentName", s.getStudentName());
                map.put("enrollmentNo", enroll);
                map.put("division", s.getDivision() != null ? s.getDivision() : "N/A");
                map.put("semester", s.getSemester() != null ? s.getSemester() : "N/A");
                map.put("rollNo", s.getRollNo() != null ? s.getRollNo() : "N/A");
                map.put("status", sub.getStatus());

                int answered = answerRepository.findBySubmissionId(sub.getId()).stream()
                        .filter(ans -> ans.getStudentAnswer() != null && !ans.getStudentAnswer().trim().isEmpty())
                        .collect(Collectors.toSet()).size();
                int progress = totalQuestions > 0 ? (answered * 100) / totalQuestions : 0;
                map.put("progress", progress);

                if (sub.getLastSavedAt() != null) {
                    map.put("lastSaveTime", sub.getLastSavedAt().format(DateTimeFormatter.ofPattern("hh:mm:ss a")));
                } else {
                    map.put("lastSaveTime", "N/A");
                }
            }
        } else if (examId != null && "exam".equals(type)) {
            List<ExamAttempt> attempts = examAttemptRepository.findByExamId(examId);
            for (ExamAttempt attempt : attempts) {
                Student s = attempt.getStudent();
                if (s == null) continue;
                String enroll = s.getEnrollmentNo().trim();
                studentMap.putIfAbsent(enroll, new HashMap<>());
                Map<String, Object> map = studentMap.get(enroll);

                map.put("studentName", s.getStudentName());
                map.put("enrollmentNo", enroll);
                map.put("division", s.getDivision() != null ? s.getDivision() : "N/A");
                map.put("semester", s.getSemester() != null ? s.getSemester() : "N/A");
                map.put("rollNo", s.getRollNo() != null ? s.getRollNo() : "N/A");
                map.put("status", attempt.getStatus());

                int answered = answerRepository.findByExamAttemptId(attempt.getId()).stream()
                        .filter(ans -> ans.getStudentAnswer() != null && !ans.getStudentAnswer().trim().isEmpty())
                        .collect(Collectors.toSet()).size();
                int progress = totalQuestions > 0 ? (answered * 100) / totalQuestions : 0;
                map.put("progress", progress);

                if (attempt.getLastSavedAt() != null) {
                    map.put("lastSaveTime", attempt.getLastSavedAt().format(DateTimeFormatter.ofPattern("hh:mm:ss a")));
                } else {
                    map.put("lastSaveTime", "N/A");
                }
            }
        }

        // Add real-time activity and session data
        List<StudentExamActivity> activities = studentExamActivityService.getAllActivities();
        Map<String, StudentExamActivity> activityMap = activities.stream()
                .filter(act -> act != null && act.getStudent() != null && finalExamId != null && finalExamId.equals(act.getExamId()))
                .collect(Collectors.toMap(act -> act.getStudent().getEnrollmentNo().trim(), act -> act, (a1, a2) -> a1));

        List<StudentActiveSession> activeSessions = studentActiveSessionRepository.findAll();
        Map<String, StudentActiveSession> sessionMap = activeSessions.stream()
                .filter(s -> s.getStudentId() != null)
                .collect(Collectors.toMap(s -> s.getStudentId().trim(), s -> s, (s1, s2) -> s1));

        for (Map.Entry<String, Map<String, Object>> entry : studentMap.entrySet()) {
            String enroll = entry.getKey();
            Map<String, Object> map = entry.getValue();

            StudentExamActivity activity = activityMap.get(enroll);
            StudentActiveSession sSession = sessionMap.get(enroll);

            // Login Time
            if (sSession != null && sSession.getLoginTime() != null) {
                map.put("loginTime", sSession.getLoginTime().format(DateTimeFormatter.ofPattern("hh:mm:ss a")));
            } else {
                map.put("loginTime", "N/A");
            }

            // In-exam Section & Question & Remaining Time
            if (activity != null) {
                map.put("currentSection", activity.getCurrentSection() != null ? activity.getCurrentSection() : "N/A");
                map.put("currentQuestionNo", activity.getCurrentQuestionNo() != null ? activity.getCurrentQuestionNo() : "N/A");
                map.put("timeRemaining", activity.getTimeRemaining() != null ? activity.getTimeRemaining() : "N/A");
                if (map.get("status") == null || "NotLoggedIn".equals(map.get("status"))) {
                    map.put("status", activity.getStatus());
                }
                map.put("lastActivity", activity.getLastActivityTime() != null ? activity.getLastActivityTime().toString() : "N/A");
            } else {
                map.put("currentSection", "N/A");
                map.put("currentQuestionNo", "N/A");
                map.put("timeRemaining", "N/A");
                if (sSession != null && "ACTIVE".equalsIgnoreCase(sSession.getStatus())) {
                    map.put("status", "LoggedInNotStarted");
                }
                map.put("lastActivity", "N/A");
            }

            // Connection Status determination: set to Connected if recent activity is within 60s and attempt is not completed
            String estatus = (String) map.get("status");
            boolean isCompleted = "Submitted".equalsIgnoreCase(estatus) || "Terminated".equalsIgnoreCase(estatus) || "COMPLETED".equalsIgnoreCase(estatus);

            if (isCompleted) {
                map.put("connectionStatus", "Completed");
            } else {
                LocalDateTime maxActivity = null;
                if (activity != null && activity.getLastActivityTime() != null) {
                    maxActivity = activity.getLastActivityTime();
                }
                if (sSession != null && sSession.getLastActivity() != null) {
                    if (maxActivity == null || sSession.getLastActivity().isAfter(maxActivity)) {
                        maxActivity = sSession.getLastActivity();
                    }
                }
                if (maxActivity != null && maxActivity.isAfter(now.minusSeconds(60))) {
                    map.put("connectionStatus", "Connected");
                } else {
                    map.put("connectionStatus", "Disconnected");
                }
            }
        }

        // Filter student list in memory based on query params
        List<Map<String, Object>> studentDataList = new ArrayList<>(studentMap.values());
        List<Map<String, Object>> filteredData = studentDataList.stream().filter(map -> {
            boolean matches = true;
            if (search != null && !search.trim().isEmpty()) {
                String q = search.trim().toLowerCase();
                boolean matchesEnrollment = map.get("enrollmentNo") != null && 
                                            ((String) map.get("enrollmentNo")).toLowerCase().contains(q);
                boolean matchesName = map.get("studentName") != null && 
                                      ((String) map.get("studentName")).toLowerCase().contains(q);
                boolean matchesRollNo = map.get("rollNo") != null && 
                                        ((String) map.get("rollNo")).toLowerCase().contains(q);
                matches = matchesEnrollment || matchesName || matchesRollNo;
            }
            if (matches && division != null && !division.trim().isEmpty()) {
                matches = division.equalsIgnoreCase((String) map.get("division"));
            }
            return matches;
        }).collect(Collectors.toList());

        // Sort: Connected/Active first, then Disconnected/Ongoing, then Remaining/Offline
        filteredData.sort((m1, m2) -> {
            String c1 = (String) m1.get("connectionStatus");
            String c2 = (String) m2.get("connectionStatus");
            
            int score1 = "Connected".equalsIgnoreCase(c1) ? 0 : ("Disconnected".equalsIgnoreCase(c1) ? 1 : 2);
            int score2 = "Connected".equalsIgnoreCase(c2) ? 0 : ("Disconnected".equalsIgnoreCase(c2) ? 1 : 2);
            
            if (score1 != score2) {
                return Integer.compare(score1, score2);
            }
            
            String e1 = (String) m1.get("enrollmentNo");
            String e2 = (String) m2.get("enrollmentNo");
            return e1.compareTo(e2);
        });

        // 5. Calculate dashboard summary metrics
        int countEligible = studentMap.size();
        int countActive = 0;
        int countSubmitted = 0;
        int countDisconnected = 0;
        int countRemaining = 0;

        for (Map<String, Object> map : studentMap.values()) {
            String cStatus = (String) map.get("connectionStatus");
            String eStatus = (String) map.get("status");

            if ("Submitted".equalsIgnoreCase(eStatus) || "Terminated".equalsIgnoreCase(eStatus) || "COMPLETED".equalsIgnoreCase(eStatus)) {
                countSubmitted++;
            } else if ("Ongoing".equalsIgnoreCase(eStatus) || "Active".equalsIgnoreCase(eStatus)) {
                if ("Connected".equalsIgnoreCase(cStatus)) {
                    countActive++;
                } else {
                    countDisconnected++;
                }
            } else {
                countRemaining++;
            }
        }

        Map<String, Object> summaryData = new HashMap<>();
        summaryData.put("eligible", countEligible);
        summaryData.put("active", countActive);
        summaryData.put("submitted", countSubmitted);
        summaryData.put("disconnected", countDisconnected);
        summaryData.put("remaining", countRemaining);

        // 6. Calculate live countdown timer details
        Map<String, Object> timerData = new HashMap<>();
        String examStatus = "DRAFT";
        Integer duration = 0;
        String startedTime = "N/A";
        String endsTime = "N/A";
        long remainingSecs = 0;
        String remainingString = "00:00:00";

        if (examId != null && type != null) {
            LocalDateTime startTime = null;
            if ("paper".equals(type)) {
                Paper paper = paperRepository.findById(examId).orElse(null);
                if (paper != null) {
                    examStatus = paper.getExamStatus();
                    duration = paper.getExamDuration() != null ? paper.getExamDuration() : 0;
                    startTime = paper.getActivationTime();
                }
            } else {
                Exam exam = examRepository.findById(examId).orElse(null);
                if (exam != null) {
                    examStatus = exam.getExamStatus();
                    duration = exam.getExamDuration() != null ? exam.getExamDuration() : 0;
                    startTime = exam.getActivationTime();
                }
            }

            if (startTime != null && duration > 0) {
                LocalDateTime endTime = startTime.plusMinutes(duration);
                DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");
                startedTime = startTime.format(timeFormatter);
                endsTime = endTime.format(timeFormatter);

                if ("ACTIVE".equals(examStatus)) {
                    remainingSecs = java.time.Duration.between(LocalDateTime.now(), endTime).getSeconds();
                    if (remainingSecs < 0) remainingSecs = 0;
                } else if ("COMPLETED".equals(examStatus) || "ENDED".equals(examStatus)) {
                    remainingSecs = 0;
                } else {
                    remainingSecs = duration * 60L;
                }

                long hrs = remainingSecs / 3600;
                long mins = (remainingSecs % 3600) / 60;
                long secs = remainingSecs % 60;
                remainingString = String.format("%02d:%02d:%02d", hrs, mins, secs);
            }
        }

        timerData.put("duration", duration);
        timerData.put("started", startedTime);
        timerData.put("ends", endsTime);
        timerData.put("remainingSeconds", remainingSecs);
        timerData.put("remainingString", remainingString);
        timerData.put("examStatus", examStatus);

        System.out.println("[Exam Timer Log] Exam Timer Status - Selected ID: " + examId + " | Status: " + examStatus + " | Remaining: " + remainingString);
        System.out.println("[Active Sessions Log] Currently active sessions in memory count: " + countActive);

        // Return JSON for ajax updates
        if (isJson) {
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("students", filteredData);
            jsonResponse.put("summary", summaryData);
            jsonResponse.put("timer", timerData);
            return ResponseEntity.ok(jsonResponse);
        }

        // Render HTML for initial page load
        addAdminAttributes(session, model);
        model.addAttribute("activeMenu", "live-monitor");
        model.addAttribute("searchQuery", search);
        model.addAttribute("selectedDivision", division);
        model.addAttribute("selectedStatus", status);

        // Fetch distinct divisions of all eligible students for dropdown dynamic filter options
        Set<String> divisions = eligibleStudents.stream()
                .map(ExamEligibleStudent::getDivision)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(d -> !d.isEmpty())
                .collect(Collectors.toSet());
        model.addAttribute("distinctDivisions", divisions);

        // Populate dropdown menu list for all exams/papers
        List<Map<String, Object>> allExamsList = new ArrayList<>();
        for (Paper p : paperRepository.findAll()) {
            Map<String, Object> map = new HashMap<>();
            map.put("value", "paper:" + p.getId());
            map.put("text", "Paper: " + p.getSubject() + " (" + p.getCourse() + " Sem " + p.getSemester() + ") - [" + p.getExamStatus() + "]");
            map.put("selected", "paper".equals(type) && p.getId().equals(examId));
            allExamsList.add(map);
        }
        for (Exam e : examRepository.findAll()) {
            Map<String, Object> map = new HashMap<>();
            map.put("value", "exam:" + e.getId());
            map.put("text", "Exam: " + e.getSubject() + " (" + e.getCourse() + " Sem " + e.getSemester() + ") - [" + e.getExamStatus() + "]");
            map.put("selected", "exam".equals(type) && e.getId().equals(examId));
            allExamsList.add(map);
        }
        model.addAttribute("allExamsList", allExamsList);
        model.addAttribute("selectedExamSelect", type + ":" + examId);
        model.addAttribute("selectedExamId", examId);
        model.addAttribute("selectedType", type);

        return "admin/live-monitor";
    }

    @PostMapping("/admin/live-monitor/end-exam")
    @ResponseBody
    public ResponseEntity<?> endExam(@RequestBody Map<String, Object> payload, HttpSession session) {
        Admin admin = getLoggedInAdmin(session);
        if (admin == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        Long examId = payload.get("examId") != null ? Long.valueOf(payload.get("examId").toString()) : null;
        String type = payload.get("type") != null ? payload.get("type").toString() : null;

        System.out.println("[End Exam Request Log] Admin requested to end exam - ID: " + examId + " | Type: " + type);

        if (examId == null || type == null) {
            return ResponseEntity.badRequest().body("Missing examId or type");
        }

        LocalDateTime now = LocalDateTime.now();

        if ("paper".equals(type)) {
            Optional<Paper> paperOpt = paperRepository.findById(examId);
            if (paperOpt.isPresent()) {
                Paper paper = paperOpt.get();
                paper.setExamStatus("COMPLETED");
                paper.setEndTime(now);
                paperRepository.save(paper);

                // Auto-submit all ongoing student submissions
                List<Submission> submissions = submissionRepository.findByPaperId(examId);
                for (Submission sub : submissions) {
                    if ("Ongoing".equals(sub.getStatus())) {
                        sub.setStatus("Submitted");
                        sub.setSubmittedAt(now);
                        submissionRepository.save(sub);

                        // Save empty answers for unanswered questions
                        List<Question> questions = questionRepository.findByPaperId(examId);
                        List<Answer> existingAnswers = answerRepository.findBySubmissionId(sub.getId());
                        Set<Long> answeredQuestionIds = new java.util.HashSet<>();
                        if (existingAnswers != null) {
                            for (Answer ans : existingAnswers) {
                                if (ans.getQuestion() != null) {
                                    answeredQuestionIds.add(ans.getQuestion().getId());
                                }
                            }
                        }
                        if (questions != null) {
                            for (Question q : questions) {
                                if (!answeredQuestionIds.contains(q.getId())) {
                                    Answer ans = new Answer();
                                    ans.setSubmission(sub);
                                    ans.setQuestion(q);
                                    ans.setQuestionText(q.getText());
                                    ans.setStudentAnswer("");
                                    ans.setMaxMarks(q.getMarks() != null ? q.getMarks() : 0.0);
                                    ans.setMarksObtained(0.0);
                                    ans.setUpdatedAt(now);
                                    answerRepository.save(ans);
                                }
                            }
                        }

                        // Close student active session
                        if (sub.getStudent() != null) {
                            studentActiveSessionRepository.findByStudentIdAndStatus(sub.getStudent().getEnrollmentNo(), "ACTIVE")
                                .ifPresent(activeSession -> {
                                    activeSession.setStatus("COMPLETED");
                                    activeSession.setLogoutTime(now);
                                    studentActiveSessionRepository.save(activeSession);
                                });
                            
                            try {
                                studentExamActivityService.updateActivity(sub.getStudent().getEnrollmentNo(), examId, null, null, "00:00:00", "Submitted");
                            } catch (Exception e) {}
                        }
                    }
                }
                submissionRepository.flush();
                System.out.println("[Exam Completion Log] Paper ID: " + examId + " ended and COMPLETED.");
            }
        } else {
            Optional<Exam> examOpt = examRepository.findById(examId);
            if (examOpt.isPresent()) {
                Exam exam = examOpt.get();
                exam.setExamStatus("COMPLETED");
                exam.setEndTime(now);
                examRepository.save(exam);

                // Auto-submit all ongoing student attempts
                List<ExamAttempt> attempts = examAttemptRepository.findByExamId(examId);
                for (ExamAttempt attempt : attempts) {
                    if ("Ongoing".equals(attempt.getStatus())) {
                        attempt.setStatus("Submitted");
                        attempt.setEndTime(now);
                        examAttemptRepository.save(attempt);

                        // Save empty answers for unanswered questions
                        List<Question> questions = questionRepository.findByExamId(examId);
                        List<Answer> existingAnswers = answerRepository.findByExamAttemptId(attempt.getId());
                        Set<Long> answeredQuestionIds = new java.util.HashSet<>();
                        if (existingAnswers != null) {
                            for (Answer ans : existingAnswers) {
                                if (ans.getQuestion() != null) {
                                    answeredQuestionIds.add(ans.getQuestion().getId());
                                }
                            }
                        }
                        if (questions != null) {
                            for (Question q : questions) {
                                if (!answeredQuestionIds.contains(q.getId())) {
                                    Answer ans = new Answer();
                                    ans.setExamAttempt(attempt);
                                    ans.setQuestion(q);
                                    ans.setQuestionText(q.getText());
                                    ans.setStudentAnswer("");
                                    ans.setMaxMarks(q.getMarks() != null ? q.getMarks() : 0.0);
                                    ans.setMarksObtained(0.0);
                                    ans.setUpdatedAt(now);
                                    answerRepository.save(ans);
                                }
                            }
                        }

                        // Close student active session
                        if (attempt.getStudent() != null) {
                            studentActiveSessionRepository.findByStudentIdAndStatus(attempt.getStudent().getEnrollmentNo(), "ACTIVE")
                                .ifPresent(activeSession -> {
                                    activeSession.setStatus("COMPLETED");
                                    activeSession.setLogoutTime(now);
                                    studentActiveSessionRepository.save(activeSession);
                                });

                            try {
                                studentExamActivityService.updateActivity(attempt.getStudent().getEnrollmentNo(), examId, null, null, "00:00:00", "Submitted");
                            } catch (Exception e) {}
                        }
                    }
                }
                examAttemptRepository.flush();
                System.out.println("[Exam Completion Log] Exam ID: " + examId + " ended and COMPLETED.");
            }
        }

        return ResponseEntity.ok(Collections.singletonMap("status", "ended"));
    }

    @PostMapping("/student/update-activity")
    @ResponseBody
    public ResponseEntity<?> updateActivity(@RequestBody Map<String, Object> payload, HttpSession session) {
        String enrollmentNo = (String) session.getAttribute("loggedInStudent");
        if (enrollmentNo == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        Long examId = payload.get("examId") != null ? Long.valueOf(payload.get("examId").toString()) : null;
        String currentSection = payload.get("currentSection") != null ? payload.get("currentSection").toString() : null;
        Integer currentQuestionNo = payload.get("currentQuestionNo") != null ? Integer.valueOf(payload.get("currentQuestionNo").toString()) : null;
        String timeRemaining = payload.get("timeRemaining") != null ? payload.get("timeRemaining").toString() : null;
        String status = payload.get("status") != null ? payload.get("status").toString() : null;

        studentExamActivityService.updateActivity(enrollmentNo, examId, currentSection, currentQuestionNo, timeRemaining, status);

        return ResponseEntity.ok(Collections.singletonMap("status", "updated"));
    }
}
