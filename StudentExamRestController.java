package University.exam.controller;

import University.exam.Entity.*;
import University.exam.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class StudentExamRestController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(StudentExamRestController.class);

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private ExamAttemptRepository examAttemptRepository;

    @Autowired
    private AnswerRepository answerRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ExamPasteLogRepository examPasteLogRepository;

    @Autowired
    private StudentActiveSessionRepository studentActiveSessionRepository;

    @Autowired
    private ExamViolationRepository examViolationRepository;

    @Autowired
    private University.exam.service.StudentExamActivityService studentExamActivityService;

    private boolean isAuthenticated(HttpSession session) {
        return session != null && (session.getAttribute("loggedInAdmin") != null || session.getAttribute("loggedInStudent") != null);
    }

    @GetMapping("/exam/{id}/status")
    public ResponseEntity<?> getExamStatus(@PathVariable("id") Long id, @RequestParam(name = "type", required = false) String type, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        logger.debug("[Student Status Check Request] Exam/Paper ID: {} | Type: {}", id, type);
        String status = "DRAFT";
        if ("exam".equals(type)) {
            Optional<Exam> examOpt = examRepository.findById(id);
            if (examOpt.isPresent()) {
                status = examOpt.get().getExamStatus();
            }
        } else {
            Optional<Paper> paperOpt = paperRepository.findById(id);
            if (paperOpt.isPresent()) {
                status = paperOpt.get().getExamStatus();
            } else {
                Optional<Exam> examOpt = examRepository.findById(id);
                if (examOpt.isPresent()) {
                    status = examOpt.get().getExamStatus();
                }
            }
        }
        if (status == null) status = "DRAFT";
        logger.debug("[Student Status Check Response] Exam/Paper ID: {} | Type: {} | Returned Status: {}", id, type, status);
        Map<String, String> response = new HashMap<>();
        response.put("status", status);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/exams")
    public ResponseEntity<?> getExams(HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        List<University.exam.dto.ExamDTO> dtos = examRepository.findAll().stream()
                .map(University.exam.dto.ExamDTO::fromEntity)
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(dtos);
    }


    @GetMapping("/exam/{id}")
    public ResponseEntity<?> getExamDetails(@PathVariable("id") Long id, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return examRepository.findById(id)
                .map(University.exam.dto.ExamDTO::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/exam/{id}/questions")
    public ResponseEntity<?> getShuffledQuestions(@PathVariable("id") Long id, @RequestParam(name = "type", required = false) String type, HttpSession session) {
        String enrollmentNo = (String) session.getAttribute("loggedInStudent");
        if (enrollmentNo == null) return ResponseEntity.status(401).body("Unauthorized");

        Student student = studentRepository.findByEnrollmentNo(enrollmentNo).orElse(null);
        if (student == null) {
            return ResponseEntity.status(404).body("Student not found");
        }
        String studentSem = student.getSemester();

        String examSem = null;
        if ("paper".equals(type)) {
            Paper paper = paperRepository.findById(id).orElse(null);
            if (paper != null) examSem = paper.getSemester();
        } else {
            Exam exam = examRepository.findById(id).orElse(null);
            if (exam != null) examSem = exam.getSemester();
        }

        if (examSem == null || !Student.matchesSemester(studentSem, examSem)) {
            return ResponseEntity.status(403).body("Access Denied. This examination is not assigned to your semester.");
        }

        List<Question> questions = "paper".equals(type) ? 
            questionRepository.findByPaperId(id) : questionRepository.findByExamId(id);
        
        logger.debug("API Request - Type: {}, ID: {}, Questions Found: {}", type, id, (questions != null ? questions.size() : 0));

        if (questions == null || questions.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        long seed = (enrollmentNo.hashCode() * 31L) + id;

        List<Map<String, Object>> result = new ArrayList<>();

        if (seed != 0) {
            // Group by dynamic questionGroup and preserve order of appearance initially
            List<String> originalSectionNames = new ArrayList<>();
            Map<String, List<Question>> groupedQuestions = new LinkedHashMap<>();
            
            for (Question q : questions) {
                String group = q.getQuestionGroup() != null ? q.getQuestionGroup() : "Q1";
                if (!groupedQuestions.containsKey(group)) {
                    originalSectionNames.add(group);
                }
                groupedQuestions.computeIfAbsent(group, k -> new ArrayList<>()).add(q);
            }
            
            List<List<Question>> sections = new ArrayList<>();
            for (String groupName : originalSectionNames) {
                sections.add(new ArrayList<>(groupedQuestions.get(groupName)));
            }
            
            // Shuffle sections (blocks) as a whole unit
            Random rand = new Random(seed);
            Collections.shuffle(sections, rand);
            
            // Map the shuffled questions to the original section names (fixed names, shifted content)
            for (int i = 0; i < sections.size(); i++) {
                String assignedSectionName = originalSectionNames.get(i);
                List<Question> sectionList = sections.get(i);
                
                for (Question q : sectionList) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", q.getId());
                    map.put("text", q.getText());
                    map.put("marks", q.getMarks());
                    map.put("questionGroup", assignedSectionName); // Reassigned fixed section name
                    map.put("isOptional", q.isOptional());
                    
                    if ("paper".equals(type)) {
                        submissionRepository.findByStudentEnrollmentNoAndPaperId(enrollmentNo, id)
                            .flatMap(sub -> answerRepository.findFirstBySubmissionIdAndQuestionIdOrderByUpdatedAtDesc(sub.getId(), q.getId()))
                            .ifPresent(ans -> map.put("savedAnswer", ans.getStudentAnswer()));
                    } else {
                        examAttemptRepository.findByStudentEnrollmentNoAndExamId(enrollmentNo, id)
                            .flatMap(attempt -> answerRepository.findFirstByExamAttemptIdAndQuestionIdOrderByUpdatedAtDesc(attempt.getId(), q.getId()))
                            .ifPresent(ans -> map.put("savedAnswer", ans.getStudentAnswer()));
                    }
                    result.add(map);
                }
            }
        } else {
            // No seed or shuffle disabled - return original section names
            for (Question q : questions) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", q.getId());
                map.put("text", q.getText());
                map.put("marks", q.getMarks());
                map.put("questionGroup", q.getQuestionGroup());
                map.put("isOptional", q.isOptional());
                
                if ("paper".equals(type)) {
                    submissionRepository.findByStudentEnrollmentNoAndPaperId(enrollmentNo, id)
                        .flatMap(sub -> answerRepository.findFirstBySubmissionIdAndQuestionIdOrderByUpdatedAtDesc(sub.getId(), q.getId()))
                        .ifPresent(ans -> map.put("savedAnswer", ans.getStudentAnswer()));
                } else {
                    examAttemptRepository.findByStudentEnrollmentNoAndExamId(enrollmentNo, id)
                        .flatMap(attempt -> answerRepository.findFirstByExamAttemptIdAndQuestionIdOrderByUpdatedAtDesc(attempt.getId(), q.getId()))
                        .ifPresent(ans -> map.put("savedAnswer", ans.getStudentAnswer()));
                }
                result.add(map);
            }
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/answer/save")
    public ResponseEntity<?> saveAnswer(@RequestBody Map<String, Object> payload, HttpSession session) {
        String enrollmentNo = (String) session.getAttribute("loggedInStudent");
        if (enrollmentNo == null) return ResponseEntity.status(401).body("Unauthorized");

        Student student = studentRepository.findByEnrollmentNo(enrollmentNo).orElse(null);
        if (student == null) {
            return ResponseEntity.status(404).body("Student not found");
        }

        Long attemptId = payload.get("attemptId") != null ? Long.valueOf(payload.get("attemptId").toString()) : null;
        Long submissionId = payload.get("submissionId") != null ? Long.valueOf(payload.get("submissionId").toString()) : null;
        Long questionId = payload.get("questionId") != null ? Long.valueOf(payload.get("questionId").toString()) : null;
        String answerText = payload.get("answer") != null ? payload.get("answer").toString() : "";

        if (questionId == null) {
            return ResponseEntity.badRequest().body("questionId is required");
        }

        Question currentQuestion = questionRepository.findById(questionId).orElse(null);
        if (currentQuestion == null) {
            return ResponseEntity.badRequest().body("Question not found");
        }

        if (attemptId == null && submissionId == null) {
            return ResponseEntity.badRequest().body("Missing attemptId or submissionId");
        }

        ExamAttempt attempt = null;
        Submission submission = null;

        if (attemptId != null) {
            attempt = examAttemptRepository.findById(attemptId).orElse(null);
            if (attempt == null) {
                return ResponseEntity.status(404).body("Attempt not found");
            }
            if (attempt.getStudent() == null || !enrollmentNo.equals(attempt.getStudent().getEnrollmentNo())) {
                return ResponseEntity.status(403).body("Access Denied: Attempt does not belong to logged-in student");
            }
            if (attempt.getExam() == null || currentQuestion.getExam() == null || !attempt.getExam().getId().equals(currentQuestion.getExam().getId())) {
                return ResponseEntity.badRequest().body("Question does not belong to this exam");
            }
            if (!Student.matchesSemester(student.getSemester(), attempt.getExam().getSemester())) {
                return ResponseEntity.status(403).body("Access Denied: Semester mismatch");
            }
            if ("Submitted".equals(attempt.getStatus())) {
                return ResponseEntity.badRequest().body("Invalid attempt status");
            }
        } else {
            submission = submissionRepository.findById(submissionId).orElse(null);
            if (submission == null) {
                return ResponseEntity.status(404).body("Submission not found");
            }
            if (submission.getStudent() == null || !enrollmentNo.equals(submission.getStudent().getEnrollmentNo())) {
                return ResponseEntity.status(403).body("Access Denied: Submission does not belong to logged-in student");
            }
            if (submission.getPaper() == null || currentQuestion.getPaper() == null || !submission.getPaper().getId().equals(currentQuestion.getPaper().getId())) {
                return ResponseEntity.badRequest().body("Question does not belong to this paper");
            }
            if (!Student.matchesSemester(student.getSemester(), submission.getPaper().getSemester())) {
                return ResponseEntity.status(403).body("Access Denied: Semester mismatch");
            }
            if ("Submitted".equals(submission.getStatus())) {
                return ResponseEntity.badRequest().body("Invalid submission status");
            }
        }

        // OR Question Logic: If this is an OR question and answer is not empty, ensure partner is cleared
        if (currentQuestion.getPairId() != null && !currentQuestion.getPairId().isEmpty() && !answerText.trim().isEmpty()) {
            List<Question> partners = questionRepository.findByPairId(currentQuestion.getPairId());
            for (Question partner : partners) {
                if (!partner.getId().equals(questionId)) {
                    // Find and clear partner answer
                    if (attemptId != null) {
                        answerRepository.findFirstByExamAttemptIdAndQuestionIdOrderByUpdatedAtDesc(attemptId, partner.getId())
                            .ifPresent(ans -> {
                                ans.setStudentAnswer("");
                                ans.setUpdatedAt(LocalDateTime.now());
                                answerRepository.save(ans);
                            });
                    } else if (submissionId != null) {
                        answerRepository.findFirstBySubmissionIdAndQuestionIdOrderByUpdatedAtDesc(submissionId, partner.getId())
                            .ifPresent(ans -> {
                                ans.setStudentAnswer("");
                                ans.setUpdatedAt(LocalDateTime.now());
                                answerRepository.save(ans);
                            });
                    }
                }
            }
        }

        // Read tracking parameters
        Integer currentSection = payload.get("currentSection") != null ? Integer.valueOf(payload.get("currentSection").toString()) : null;
        Long currentQuestionId = payload.get("currentQuestionId") != null ? Long.valueOf(payload.get("currentQuestionId").toString()) : null;
        long gracePeriodSeconds = 120L;

        Answer answer;
        if (attempt != null) {
            LocalDateTime start = attempt.getStartTime() != null ? attempt.getStartTime() : LocalDateTime.now();
            int durationMinutes = (attempt.getExam() != null && attempt.getExam().getExamDuration() != null) ? attempt.getExam().getExamDuration() : 60;
            long allowedSeconds = durationMinutes * 60L;
            long elapsedSeconds = java.time.Duration.between(start, LocalDateTime.now()).getSeconds();
            if (elapsedSeconds > (allowedSeconds + gracePeriodSeconds)) {
                return ResponseEntity.status(403).body("Exam time limit exceeded. Answer cannot be saved.");
            }

            int serverRemaining = (int) Math.max(0, allowedSeconds - elapsedSeconds);
            attempt.setRemainingTimeSeconds(serverRemaining);
            if (currentSection != null) attempt.setLastActiveSection(currentSection);
            if (currentQuestionId != null) attempt.setLastActiveQuestionId(currentQuestionId);
            attempt.setLastSavedAt(LocalDateTime.now());
            examAttemptRepository.save(attempt);

            final ExamAttempt finalAttempt = attempt;
            answer = answerRepository.findFirstByExamAttemptIdAndQuestionIdOrderByUpdatedAtDesc(attemptId, questionId)
                    .orElseGet(() -> {
                        Answer a = new Answer();
                        a.setExamAttempt(finalAttempt);
                        a.setQuestion(currentQuestion);
                        a.setQuestionText(currentQuestion.getText() != null ? currentQuestion.getText() : "Theory Answer");
                        a.setMaxMarks(currentQuestion.getMarks() != null ? currentQuestion.getMarks() : 0.0);
                        return a;
                    });
        } else {
            LocalDateTime start = submission.getStartTime() != null ? submission.getStartTime() : LocalDateTime.now();
            int durationMinutes = (submission.getPaper() != null && submission.getPaper().getExamDuration() != null) ? submission.getPaper().getExamDuration() : 120;
            long allowedSeconds = durationMinutes * 60L;
            long elapsedSeconds = java.time.Duration.between(start, LocalDateTime.now()).getSeconds();
            if (elapsedSeconds > (allowedSeconds + gracePeriodSeconds)) {
                return ResponseEntity.status(403).body("Exam time limit exceeded. Answer cannot be saved.");
            }

            int serverRemaining = (int) Math.max(0, allowedSeconds - elapsedSeconds);
            submission.setRemainingTimeSeconds(serverRemaining);
            if (currentSection != null) submission.setLastActiveSection(currentSection);
            if (currentQuestionId != null) submission.setLastActiveQuestionId(currentQuestionId);
            submission.setLastSavedAt(LocalDateTime.now());
            submissionRepository.save(submission);

            final Submission finalSub = submission;
            answer = answerRepository.findFirstBySubmissionIdAndQuestionIdOrderByUpdatedAtDesc(submissionId, questionId)
                    .orElseGet(() -> {
                        Answer a = new Answer();
                        a.setSubmission(finalSub);
                        a.setQuestion(currentQuestion);
                        a.setQuestionText(currentQuestion.getText() != null ? currentQuestion.getText() : "Paper Answer");
                        a.setMaxMarks(currentQuestion.getMarks() != null ? currentQuestion.getMarks() : 0.0);
                        return a;
                    });
        }

        answer.setStudentAnswer(answerText);
        answer.setUpdatedAt(LocalDateTime.now());
        answerRepository.save(answer);

        return ResponseEntity.ok(Collections.singletonMap("status", "saved"));
    }

    @PostMapping("/exam/log-paste")
    public ResponseEntity<?> logPasteAttempt(@RequestBody Map<String, Object> payload, HttpSession session) {
        String enrollmentNo = (String) session.getAttribute("loggedInStudent");
        if (enrollmentNo == null) return ResponseEntity.status(401).body("Unauthorized");

        Long questionId = payload.get("questionId") != null ? Long.valueOf(payload.get("questionId").toString()) : null;
        String pasteAttempt = payload.get("pasteAttempt") != null ? payload.get("pasteAttempt").toString() : "";
        String sourceType = payload.get("sourceType") != null ? payload.get("sourceType").toString() : "";

        ExamPasteLog log = new ExamPasteLog(enrollmentNo, questionId, LocalDateTime.now(), pasteAttempt, sourceType);
        examPasteLogRepository.save(log);

        return ResponseEntity.ok(Collections.singletonMap("status", "logged"));
    }

    @PostMapping("/exam/log-violation")
    public ResponseEntity<?> logViolation(@RequestBody Map<String, Object> payload, HttpSession session) {
        String enrollmentNo = (String) session.getAttribute("loggedInStudent");
        if (enrollmentNo == null) return ResponseEntity.status(401).body("Unauthorized");

        String eventType = payload.get("eventType") != null ? payload.get("eventType").toString() : "UNKNOWN";
        Long examId = (Long) session.getAttribute("currentExamId");
        String type = (String) session.getAttribute("currentExamType");

        ExamViolation violation = new ExamViolation(enrollmentNo, LocalDateTime.now(), eventType, examId, type);
        examViolationRepository.save(violation);

        return ResponseEntity.ok(Collections.singletonMap("status", "logged"));
    }

    @PostMapping("/exam/submit")
    public ResponseEntity<?> submitExam(@RequestBody Map<String, Object> payload, HttpSession session) {
        String enrollmentNo = (String) session.getAttribute("loggedInStudent");
        if (enrollmentNo == null) return ResponseEntity.status(401).body("Unauthorized");

        Student student = studentRepository.findByEnrollmentNo(enrollmentNo).orElse(null);
        if (student == null) {
            return ResponseEntity.status(404).body("Student not found");
        }

        Long attemptId = payload.get("attemptId") != null ? Long.valueOf(payload.get("attemptId").toString()) : null;
        Long submissionId = payload.get("submissionId") != null ? Long.valueOf(payload.get("submissionId").toString()) : null;

        if (attemptId == null && submissionId == null) {
            return ResponseEntity.badRequest().body("Missing attemptId or submissionId");
        }

        if (attemptId != null) {
            ExamAttempt attempt = examAttemptRepository.findById(attemptId).orElse(null);
            if (attempt == null) {
                return ResponseEntity.status(404).body("Exam attempt not found");
            }
            if (attempt.getStudent() == null || !enrollmentNo.equals(attempt.getStudent().getEnrollmentNo())) {
                return ResponseEntity.status(403).body("Access Denied: Attempt does not belong to logged-in student");
            }
            if (attempt.getExam() == null) {
                return ResponseEntity.status(404).body("Exam not found");
            }
            if (!Student.matchesSemester(student.getSemester(), attempt.getExam().getSemester())) {
                return ResponseEntity.status(403).body("Access Denied. This examination is not assigned to your semester.");
            }
            if ("Submitted".equals(attempt.getStatus())) {
                return ResponseEntity.badRequest().body("Already submitted");
            }

            LocalDateTime start = attempt.getStartTime() != null ? attempt.getStartTime() : LocalDateTime.now();
            int durationMinutes = (attempt.getExam() != null && attempt.getExam().getExamDuration() != null) ? attempt.getExam().getExamDuration() : 60;
            long allowedSeconds = durationMinutes * 60L;
            long elapsedSeconds = java.time.Duration.between(start, LocalDateTime.now()).getSeconds();
            long gracePeriodSeconds = 120L;
            if (elapsedSeconds > (allowedSeconds + gracePeriodSeconds)) {
                return ResponseEntity.status(403).body("Exam deadline has expired. Submission rejected.");
            }

            // Auto-create missing/skipped Answers with 0 marks
            List<Question> questions = questionRepository.findByExamId(attempt.getExam().getId());
            List<Answer> existingAnswers = answerRepository.findByExamAttemptId(attemptId);
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
                        ans.setUpdatedAt(LocalDateTime.now());
                        answerRepository.save(ans);
                    }
                }
            }
            
            attempt.setStatus("Submitted");
            attempt.setEndTime(LocalDateTime.now());
            examAttemptRepository.save(attempt);

            studentActiveSessionRepository.findByStudentIdAndStatus(enrollmentNo, "ACTIVE").ifPresent(activeSession -> {
                activeSession.setStatus("COMPLETED");
                activeSession.setLogoutTime(LocalDateTime.now());
                studentActiveSessionRepository.save(activeSession);
            });

            try {
                studentExamActivityService.updateActivity(enrollmentNo, attempt.getExam().getId(), null, null, "00:00:00", "Submitted");
            } catch (Exception e) {
                logger.error("Failed to update activity to Submitted: {}", e.getMessage());
            }

            return ResponseEntity.ok(Collections.singletonMap("status", "submitted"));
        } else {
            Submission submission = submissionRepository.findById(submissionId).orElse(null);
            if (submission == null) {
                return ResponseEntity.status(404).body("Submission not found");
            }
            if (submission.getStudent() == null || !enrollmentNo.equals(submission.getStudent().getEnrollmentNo())) {
                return ResponseEntity.status(403).body("Access Denied: Submission does not belong to logged-in student");
            }
            if (submission.getPaper() == null) {
                return ResponseEntity.status(404).body("Paper not found");
            }
            if (!Student.matchesSemester(student.getSemester(), submission.getPaper().getSemester())) {
                return ResponseEntity.status(403).body("Access Denied. This examination is not assigned to your semester.");
            }
            if ("Submitted".equals(submission.getStatus())) {
                return ResponseEntity.badRequest().body("Already submitted");
            }

            LocalDateTime start = submission.getStartTime() != null ? submission.getStartTime() : LocalDateTime.now();
            int durationMinutes = (submission.getPaper() != null && submission.getPaper().getExamDuration() != null) ? submission.getPaper().getExamDuration() : 120;
            long allowedSeconds = durationMinutes * 60L;
            long elapsedSeconds = java.time.Duration.between(start, LocalDateTime.now()).getSeconds();
            long gracePeriodSeconds = 120L;
            if (elapsedSeconds > (allowedSeconds + gracePeriodSeconds)) {
                return ResponseEntity.status(403).body("Exam deadline has expired. Submission rejected.");
            }

            // Auto-create missing/skipped Answers with 0 marks
            List<Question> questions = questionRepository.findByPaperId(submission.getPaper().getId());
            List<Answer> existingAnswers = answerRepository.findBySubmissionId(submissionId);
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
                        ans.setSubmission(submission);
                        ans.setQuestion(q);
                        ans.setQuestionText(q.getText());
                        ans.setStudentAnswer("");
                        ans.setMaxMarks(q.getMarks() != null ? q.getMarks() : 0.0);
                        ans.setMarksObtained(0.0);
                        ans.setUpdatedAt(LocalDateTime.now());
                        answerRepository.save(ans);
                    }
                }
            }

            submission.setStatus("Submitted");
            submission.setSubmittedAt(LocalDateTime.now());
            submissionRepository.save(submission);

            studentActiveSessionRepository.findByStudentIdAndStatus(enrollmentNo, "ACTIVE").ifPresent(activeSession -> {
                activeSession.setStatus("COMPLETED");
                activeSession.setLogoutTime(LocalDateTime.now());
                studentActiveSessionRepository.save(activeSession);
            });

            try {
                studentExamActivityService.updateActivity(enrollmentNo, submission.getPaper().getId(), null, null, "00:00:00", "Submitted");
            } catch (Exception e) {
                logger.error("Failed to update activity to Submitted: {}", e.getMessage());
            }

            return ResponseEntity.ok(Collections.singletonMap("status", "submitted"));
        }
    }
}
