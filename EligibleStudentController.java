package University.exam.controller;

import University.exam.Entity.*;
import University.exam.repository.*;
import University.exam.service.StudentFileParserService;
import University.exam.service.StudentFileParserService.ParsedStudent;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/eligible-students")
public class EligibleStudentController {

    @Autowired
    private ExamEligibleStudentRepository eligibleStudentRepository;

    @Autowired
    private EligibilityAuditLogRepository auditLogRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private ExamAttemptRepository examAttemptRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private StudentFileParserService fileParserService;

    @Autowired
    private StudentRepository studentRepository;

    // ──────────────────────────────────────────────────────────
    //  Auth helper
    // ──────────────────────────────────────────────────────────
    private Admin getLoggedInAdmin(HttpSession session) {
        if (session == null) return null;
        String adminName = (String) session.getAttribute("loggedInAdmin");
        if (adminName == null) return null;
        List<Admin> admins = adminRepository.findByAdminNameIgnoreCase(adminName.trim());
        return (admins != null && !admins.isEmpty()) ? admins.get(0) : null;
    }

    private void addAdminAttributes(HttpSession session, Model model) {
        String adminName = (String) session.getAttribute("loggedInAdmin");
        model.addAttribute("adminName", adminName != null ? adminName : "Super Admin");
        model.addAttribute("logoUrl", "/images/logo.png");
    }

    // ──────────────────────────────────────────────────────────
    //  Shared: build exam names map (examId → display name)
    // ──────────────────────────────────────────────────────────
    private Map<Long, String> buildExamNamesMap() {
        Map<Long, String> map = new LinkedHashMap<>();
        List<Exam> exams = examRepository.findAll();
        for (Exam e : exams) {
            String label = (e.getExamName() != null ? e.getExamName() : "Exam") +
                           " (Sem " + (e.getSemester() != null ? e.getSemester() : "?") + ")";
            map.put(e.getId(), label);
        }
        List<Paper> papers = paperRepository.findAll();
        for (Paper p : papers) {
            String label = (p.getSubject() != null ? p.getSubject() : "Paper") +
                           " (Sem " + (p.getSemester() != null ? p.getSemester() : "?") + ")";
            // Use negative IDs for papers to avoid collision: store as positive in map,
            // but paper IDs in eligible_students are stored with examType=PAPER distinguishing them.
            // For the map we use ID directly since examType differentiates them in the entity.
            // We use a composite key trick: paperIdAsLong → label (papers are separate examType=PAPER records)
            map.put(p.getId(), label);
        }
        return map;
    }

    // ──────────────────────────────────────────────────────────
    //  GET /admin/eligible-students  — Dashboard page
    // ──────────────────────────────────────────────────────────
    @GetMapping
    public String dashboard(
            @RequestParam(value = "success", required = false) String success,
            @RequestParam(value = "error", required = false) String error,
            HttpSession session, Model model) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return "redirect:/admin-login";
        addAdminAttributes(session, model);

        // All exams and papers for the upload form dropdowns
        List<Exam> exams = examRepository.findAll();
        exams.sort((a, b) -> b.getId().compareTo(a.getId()));
        List<Paper> papers = paperRepository.findAll();
        papers.sort((a, b) -> b.getId().compareTo(a.getId()));

        model.addAttribute("exams", exams);
        model.addAttribute("papers", papers);

        // Build examNamesMap for display
        Map<Long, String> examNamesMap = new LinkedHashMap<>();
        for (Exam e : exams) {
            String label = (e.getExamName() != null ? e.getExamName() : "Exam") +
                           " (Sem " + (e.getSemester() != null ? e.getSemester() : "?") + ")";
            examNamesMap.put(e.getId(), label);
        }
        for (Paper p : papers) {
            String label = (p.getSubject() != null ? p.getSubject() : "Paper") +
                           " (Sem " + (p.getSemester() != null ? p.getSemester() : "?") + ")";
            examNamesMap.put(p.getId(), label);
        }
        model.addAttribute("examNamesMap", examNamesMap);

        // Build exam summary cards: group eligible students by examId + examType
        List<ExamEligibleStudent> allEligible = eligibleStudentRepository.findAllByOrderByImportedAtDesc();

        // Collect unique (examId, examType) pairs
        List<Map<String, Object>> examSummaries = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        for (ExamEligibleStudent rec : allEligible) {
            String key = rec.getExamType() + "_" + rec.getExamId();
            if (seenKeys.add(key)) {
                long eligibleCount = eligibleStudentRepository.countByExamId(rec.getExamId());

                // Count attempted: for EXAM type use exam attempts, for PAPER use submissions
                long attemptedCount = 0;
                if ("PAPER".equals(rec.getExamType())) {
                    attemptedCount = submissionRepository.findByPaperId(rec.getExamId()).size();
                } else {
                    attemptedCount = examAttemptRepository.countByExamId(rec.getExamId());
                }
                long pendingCount = Math.max(0, eligibleCount - attemptedCount);

                String examDisplayName = examNamesMap.getOrDefault(rec.getExamId(),
                    "Exam/Paper #" + rec.getExamId());

                // Determine exam status for lock check
                String examStatus = "UNKNOWN";
                if ("PAPER".equals(rec.getExamType())) {
                    Paper p = paperRepository.findById(rec.getExamId()).orElse(null);
                    if (p != null) examStatus = p.getExamStatus() != null ? p.getExamStatus() : "DRAFT";
                } else {
                    Exam e = examRepository.findById(rec.getExamId()).orElse(null);
                    if (e != null) examStatus = e.getExamStatus() != null ? e.getExamStatus() : "DRAFT";
                }

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("examId", rec.getExamId());
                summary.put("examType", rec.getExamType());
                summary.put("examName", examDisplayName);
                summary.put("eligibleCount", eligibleCount);
                summary.put("attemptedCount", attemptedCount);
                summary.put("pendingCount", pendingCount);
                summary.put("examStatus", examStatus);
                summary.put("isLocked", "ACTIVE".equals(examStatus) || "ENDED".equals(examStatus));
                examSummaries.add(summary);
            }
        }
        model.addAttribute("examSummaries", examSummaries);

        // All eligible student records (flat) for the main table
        model.addAttribute("eligibilityList", allEligible);

        // Audit logs (latest first)
        List<EligibilityAuditLog> auditLogs = auditLogRepository.findAllByOrderByUploadDateDesc();
        model.addAttribute("auditLogs", auditLogs);

        // Flash messages
        if (success != null) model.addAttribute("successMessage", success);
        if (error != null) model.addAttribute("errorMessage", error);

        model.addAttribute("reviewMode", false);

        return "admin/eligible_students";
    }

    // ──────────────────────────────────────────────────────────
    //  POST /admin/eligible-students/import — Parse file → preview
    // ──────────────────────────────────────────────────────────
    @PostMapping("/import")
    public String importFile(
            @RequestParam("examTarget") String examTarget,
            @RequestParam("file") MultipartFile file,
            HttpSession session, Model model, RedirectAttributes redirectAttributes) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return "redirect:/admin-login";
        addAdminAttributes(session, model);

        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute("uploadError", "Please select a file to upload.");
            return "redirect:/admin/eligible-students";
        }

        // Parse examTarget: "EXAM_5" or "PAPER_3"
        String examType = "EXAM";
        Long examId = null;
        try {
            if (examTarget.startsWith("PAPER_")) {
                examType = "PAPER";
                examId = Long.parseLong(examTarget.substring(6));
            } else if (examTarget.startsWith("EXAM_")) {
                examType = "EXAM";
                examId = Long.parseLong(examTarget.substring(5));
            } else {
                throw new IllegalArgumentException("Invalid exam target format");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("uploadError", "Invalid exam selection. Please try again.");
            return "redirect:/admin/eligible-students";
        }

        // Parse the uploaded file
        List<ParsedStudent> parsedStudents;
        try {
            parsedStudents = fileParserService.parseFile(file);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("uploadError",
                "Failed to parse file: " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
            return "redirect:/admin/eligible-students";
        }

        if (parsedStudents == null || parsedStudents.isEmpty()) {
            redirectAttributes.addFlashAttribute("uploadError",
                "No student records found in the uploaded file. Please check the file format.");
            return "redirect:/admin/eligible-students";
        }

        // Determine exam display name
        String targetExamName = "Unknown Exam";
        if ("PAPER".equals(examType)) {
            Paper p = paperRepository.findById(examId).orElse(null);
            if (p != null) targetExamName = p.getSubject() + " (Sem " + p.getSemester() + ")";
        } else {
            Exam e = examRepository.findById(examId).orElse(null);
            if (e != null) targetExamName = e.getExamName() + " (Sem " + e.getSemester() + ")";
        }

        // Store in session for confirmation step
        session.setAttribute("previewParsedStudents", parsedStudents);
        session.setAttribute("previewExamId", examId);
        session.setAttribute("previewExamType", examType);
        session.setAttribute("previewExamName", targetExamName);
        session.setAttribute("previewFileName", file.getOriginalFilename());

        // Query DB to separate valid and invalid records
        List<ParsedStudent> matchedStudents = new ArrayList<>();
        List<Map<String, String>> invalidRecords = new ArrayList<>();

        for (ParsedStudent ps : parsedStudents) {
            String enrollNo = ps.getEnrollmentNo();
            Student dbStudent = studentRepository.findByEnrollmentNo(enrollNo).orElse(null);
            if (dbStudent != null) {
                // Keep the division/roll from the uploaded file, but override name/semester from DB to ensure consistency
                ps.setStudentName(dbStudent.getStudentName());
                ps.setSemester(dbStudent.getSemester());
                matchedStudents.add(ps);
            } else {
                Map<String, String> invalid = new LinkedHashMap<>();
                invalid.put("enrollmentNo", enrollNo);
                invalid.put("studentName", ps.getStudentName());
                invalid.put("reason", "Student Not Found");
                invalidRecords.add(invalid);
            }
        }

        // Render preview mode
        addAdminAttributes(session, model);

        List<Exam> exams = examRepository.findAll();
        exams.sort((a, b) -> b.getId().compareTo(a.getId()));
        List<Paper> papers = paperRepository.findAll();
        papers.sort((a, b) -> b.getId().compareTo(a.getId()));
        model.addAttribute("exams", exams);
        model.addAttribute("papers", papers);

        model.addAttribute("reviewMode", true);
        model.addAttribute("matchedStudents", matchedStudents);
        model.addAttribute("invalidRecords", invalidRecords);
        model.addAttribute("totalRecords", parsedStudents.size());
        model.addAttribute("targetExamId", examId);
        model.addAttribute("targetExamType", examType);
        model.addAttribute("targetExamName", targetExamName);
        model.addAttribute("fileName", file.getOriginalFilename());

        Map<Long, String> examNamesMap = new LinkedHashMap<>();
        for (Exam e : exams) {
            examNamesMap.put(e.getId(),
                (e.getExamName() != null ? e.getExamName() : "Exam") +
                " (Sem " + (e.getSemester() != null ? e.getSemester() : "?") + ")");
        }
        for (Paper p : papers) {
            examNamesMap.put(p.getId(),
                (p.getSubject() != null ? p.getSubject() : "Paper") +
                " (Sem " + (p.getSemester() != null ? p.getSemester() : "?") + ")");
        }
        model.addAttribute("examNamesMap", examNamesMap);
        model.addAttribute("auditLogs", auditLogRepository.findAllByOrderByUploadDateDesc());
        model.addAttribute("eligibilityList", Collections.emptyList());
        model.addAttribute("examSummaries", Collections.emptyList());

        return "admin/eligible_students";
    }

    // ──────────────────────────────────────────────────────────
    //  POST /admin/eligible-students/save — Confirm & persist list (AJAX JSON)
    // ──────────────────────────────────────────────────────────
    @PostMapping("/save")
    @ResponseBody
    public ResponseEntity<String> saveEligibilityList(
            @RequestBody Map<String, Object> payload,
            HttpSession session) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return ResponseEntity.status(401).body("Unauthorized");

        try {
            Long examId = Long.valueOf(payload.get("examId").toString());
            String examType = payload.get("examType") != null ? payload.get("examType").toString() : "EXAM";
            int totalRecords = payload.get("totalRecords") != null ? Integer.parseInt(payload.get("totalRecords").toString()) : 0;
            int invalidCount = payload.get("invalidCount") != null ? Integer.parseInt(payload.get("invalidCount").toString()) : 0;

            @SuppressWarnings("unchecked")
            List<Map<String, String>> studentRecords = (List<Map<String, String>>) payload.get("students");
            if (studentRecords == null) studentRecords = new ArrayList<>();

            // Lock check: if exam is ACTIVE or ENDED, disallow modification
            String examStatus = getExamStatus(examId, examType);
            if ("ACTIVE".equals(examStatus) || "ENDED".equals(examStatus)) {
                return ResponseEntity.badRequest()
                    .body("Student eligibility list is locked because the examination has already started.");
            }

            // Replace existing records for this exam
            eligibleStudentRepository.deleteByExamId(examId);

            LocalDateTime now = LocalDateTime.now();
            List<ExamEligibleStudent> toSave = new ArrayList<>();
            for (Map<String, String> rec : studentRecords) {
                String enrollmentNo = rec.getOrDefault("enrollmentNo", "").trim();
                if (enrollmentNo.isEmpty()) continue;
                String div = rec.getOrDefault("division", "A").trim();
                String roll = rec.getOrDefault("rollNo", "").trim();

                Student dbStudent = studentRepository.findByEnrollmentNo(enrollmentNo).orElse(null);
                if (dbStudent == null) continue;

                String formattedRollNo = StudentFileParserService.formatRollNo(div, roll);
                ExamEligibleStudent es = new ExamEligibleStudent(
                    examId, dbStudent.getId(), examType, enrollmentNo,
                    dbStudent.getStudentName(),
                    div, dbStudent.getSemester(),
                    formattedRollNo,
                    admin.getAdminName(), now
                );
                toSave.add(es);
            }
            eligibleStudentRepository.saveAll(toSave);

            // Retrieve file name from session
            String fileName = (String) session.getAttribute("previewFileName");
            if (fileName == null) fileName = "manual_upload.csv";

            // Save audit log
            EligibilityAuditLog log = new EligibilityAuditLog(
                examId, examType, fileName, now, admin.getAdminName(),
                toSave.size(), invalidCount
            );
            auditLogRepository.save(log);

            // Clear session preview
            session.removeAttribute("previewParsedStudents");
            session.removeAttribute("previewExamId");
            session.removeAttribute("previewExamType");
            session.removeAttribute("previewExamName");
            session.removeAttribute("previewFileName");

            return ResponseEntity.ok("success");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                .body("Error saving eligibility list: " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    // ──────────────────────────────────────────────────────────
    //  POST /admin/eligible-students/delete — Remove single record
    // ──────────────────────────────────────────────────────────
    @PostMapping("/delete")
    public String deleteRecord(
            @RequestParam("examId") Long examId,
            @RequestParam("enrollmentNo") String enrollmentNo,
            HttpSession session, RedirectAttributes redirectAttributes) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return "redirect:/admin-login";

        String examType = getExamTypeForExamId(examId);
        String examStatus = getExamStatus(examId, examType);

        if ("ACTIVE".equals(examStatus) || "ENDED".equals(examStatus)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                "Student eligibility list is locked because the examination has already started.");
            return "redirect:/admin/eligible-students";
        }

        eligibleStudentRepository.deleteByExamIdAndEnrollmentNo(examId, enrollmentNo);

        // Update audit log last modified
        updateAuditLogModified(examId);

        redirectAttributes.addFlashAttribute("successMessage",
            "Student " + enrollmentNo + " removed from eligibility list.");
        return "redirect:/admin/eligible-students";
    }

    // ──────────────────────────────────────────────────────────
    //  POST /admin/eligible-students/edit — Edit single record (AJAX)
    // ──────────────────────────────────────────────────────────
    @PostMapping("/edit")
    @ResponseBody
    public ResponseEntity<String> editRecord(
            @RequestParam("id") Long id,
            @RequestParam("studentName") String studentName,
            @RequestParam("division") String division,
            @RequestParam(value = "rollNo", defaultValue = "") String rollNo,
            HttpSession session) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return ResponseEntity.status(401).body("Unauthorized");

        ExamEligibleStudent rec = eligibleStudentRepository.findById(id).orElse(null);
        if (rec == null) return ResponseEntity.notFound().build();

        String examStatus = getExamStatus(rec.getExamId(), rec.getExamType());
        if ("ACTIVE".equals(examStatus) || "ENDED".equals(examStatus)) {
            return ResponseEntity.badRequest()
                .body("Student eligibility list is locked because the examination has already started.");
        }

        String formattedRollNo = StudentFileParserService.formatRollNo(division, rollNo);
        rec.setStudentName(studentName.trim());
        rec.setDivision(division.trim());
        rec.setRollNo(formattedRollNo);
        eligibleStudentRepository.save(rec);

        updateAuditLogModified(rec.getExamId());

        return ResponseEntity.ok("updated");
    }

    // ──────────────────────────────────────────────────────────
    //  POST /admin/eligible-students/add-student — Add single student manually
    // ──────────────────────────────────────────────────────────
    @PostMapping("/add-student")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addStudent(
            @RequestParam("examId") Long examId,
            @RequestParam("examType") String examType,
            @RequestParam("enrollmentNo") String enrollmentNo,
            @RequestParam(value = "studentName", defaultValue = "Unknown") String studentName,
            @RequestParam(value = "division", defaultValue = "A") String division,
            @RequestParam(value = "rollNo", defaultValue = "") String rollNo,
            HttpSession session) {

        Admin admin = getLoggedInAdmin(session);
        Map<String, Object> response = new LinkedHashMap<>();
        if (admin == null) {
            response.put("error", "Unauthorized");
            return ResponseEntity.status(401).body(response);
        }

        String examStatus = getExamStatus(examId, examType);
        if ("ACTIVE".equals(examStatus) || "ENDED".equals(examStatus)) {
            response.put("error", "Student eligibility list is locked because the examination has already started.");
            return ResponseEntity.badRequest().body(response);
        }

        String trimmedEnrollment = enrollmentNo.trim();
        Student dbStudent = studentRepository.findByEnrollmentNo(trimmedEnrollment).orElse(null);
        if (dbStudent == null) {
            response.put("error", "Student Not Found in the main database.");
            return ResponseEntity.badRequest().body(response);
        }

        if (eligibleStudentRepository.existsByExamIdAndEnrollmentNo(examId, trimmedEnrollment)) {
            response.put("error", "Student with enrollment number " + trimmedEnrollment + " is already in the eligibility list.");
            return ResponseEntity.badRequest().body(response);
        }

        String formattedRollNo = StudentFileParserService.formatRollNo(division, rollNo);
        ExamEligibleStudent rec = new ExamEligibleStudent(
            examId, dbStudent.getId(), examType, trimmedEnrollment,
            dbStudent.getStudentName(), division.trim(), dbStudent.getSemester(), formattedRollNo,
            admin.getAdminName(), LocalDateTime.now()
        );
        ExamEligibleStudent saved = eligibleStudentRepository.save(rec);

        updateAuditLogModified(examId);

        response.put("id", saved.getId());
        response.put("enrollmentNo", saved.getEnrollmentNo());
        response.put("studentName", saved.getStudentName());
        response.put("division", saved.getDivision());
        response.put("rollNo", saved.getRollNo());
        return ResponseEntity.ok(response);
    }

    // ──────────────────────────────────────────────────────────
    //  POST /admin/eligible-students/clear-exam — Clear all records for an exam
    // ──────────────────────────────────────────────────────────
    @PostMapping("/clear-exam")
    public String clearExamList(
            @RequestParam("examId") Long examId,
            HttpSession session, RedirectAttributes redirectAttributes) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return "redirect:/admin-login";

        String examType = getExamTypeForExamId(examId);
        String examStatus = getExamStatus(examId, examType);

        if ("ACTIVE".equals(examStatus) || "ENDED".equals(examStatus)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                "Student eligibility list is locked because the examination has already started.");
            return "redirect:/admin/eligible-students";
        }

        eligibleStudentRepository.deleteByExamId(examId);
        redirectAttributes.addFlashAttribute("successMessage",
            "All eligible students cleared for Exam #" + examId + ".");
        return "redirect:/admin/eligible-students";
    }

    // ──────────────────────────────────────────────────────────
    //  GET /admin/eligible-students/download/{examId} — Download CSV
    // ──────────────────────────────────────────────────────────
    @GetMapping("/download/{examId}")
    public ResponseEntity<byte[]> downloadCsv(
            @PathVariable("examId") Long examId,
            HttpSession session) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return ResponseEntity.status(302).build();

        List<ExamEligibleStudent> records = eligibleStudentRepository.findByExamIdOrderByStudentNameAsc(examId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            writer.println("Enrollment No,Student Name,Division,Roll No,Imported By,Imported At");
            for (ExamEligibleStudent rec : records) {
                String date = rec.getImportedAt() != null ? rec.getImportedAt().toString() : "";
                writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                    safe(rec.getEnrollmentNo()),
                    safe(rec.getStudentName()),
                    safe(rec.getDivision()),
                    safe(rec.getRollNo()),
                    safe(rec.getImportedBy()),
                    date);
            }
        }

        byte[] csvBytes = baos.toByteArray();
        String filename = "eligibility_list_exam_" + examId + ".csv";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .contentLength(csvBytes.length)
            .body(csvBytes);
    }

    // ──────────────────────────────────────────────────────────
    //  GET /admin/eligible-students/list/{examId} — AJAX: get students for an exam
    // ──────────────────────────────────────────────────────────
    @GetMapping("/list/{examId}")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getStudentsForExam(
            @PathVariable("examId") Long examId,
            HttpSession session) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return ResponseEntity.status(401).build();

        List<ExamEligibleStudent> records = eligibleStudentRepository.findByExamIdOrderByStudentNameAsc(examId);
        List<Map<String, Object>> result = records.stream().map(rec -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.getId());
            m.put("enrollmentNo", rec.getEnrollmentNo());
            m.put("studentName", rec.getStudentName() != null ? rec.getStudentName() : "");
            m.put("division", rec.getDivision() != null ? rec.getDivision() : "");
            m.put("rollNo", rec.getRollNo() != null ? rec.getRollNo() : "");
            m.put("importedBy", rec.getImportedBy() != null ? rec.getImportedBy() : "");
            m.put("importedAt", rec.getImportedAt() != null ? rec.getImportedAt().toString() : "");
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────
    //  Utility helpers
    // ──────────────────────────────────────────────────────────
    private String safe(String val) {
        return val != null ? val.replace("\"", "\"\"") : "";
    }

    private String getExamTypeForExamId(Long examId) {
        List<ExamEligibleStudent> recs = eligibleStudentRepository.findByExamId(examId);
        if (!recs.isEmpty()) return recs.get(0).getExamType();
        // Try to determine from repos
        if (examRepository.existsById(examId)) return "EXAM";
        if (paperRepository.existsById(examId)) return "PAPER";
        return "EXAM";
    }

    private String getExamStatus(Long examId, String examType) {
        if ("PAPER".equals(examType)) {
            Paper p = paperRepository.findById(examId).orElse(null);
            return p != null && p.getExamStatus() != null ? p.getExamStatus() : "DRAFT";
        } else {
            Exam e = examRepository.findById(examId).orElse(null);
            return e != null && e.getExamStatus() != null ? e.getExamStatus() : "DRAFT";
        }
    }

    private void updateAuditLogModified(Long examId) {
        List<EligibilityAuditLog> logs = auditLogRepository.findByExamIdOrderByUploadDateDesc(examId);
        if (!logs.isEmpty()) {
            EligibilityAuditLog latest = logs.get(0);
            latest.setLastModifiedDate(LocalDateTime.now());
            latest.setTotalImported((int) eligibleStudentRepository.countByExamId(examId));
            auditLogRepository.save(latest);
        }
    }
}
