package University.exam.controller;

import University.exam.Entity.*;
import University.exam.repository.*;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Element;
import com.lowagie.text.Phrase;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/history-records")
public class HistoryRecordsController {

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private AnswerRepository answerRepository;

    @Autowired
    private ResultRepository resultRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private ExamAttemptRepository examAttemptRepository;

    private Admin getLoggedInAdmin(HttpSession session) {
        if (session == null) return null;
        String username = (String) session.getAttribute("admin");
        if (username == null) {
            username = (String) session.getAttribute("loggedInAdmin");
        }
        if (username == null) return null;
        List<Admin> admins = adminRepository.findByAdminNameIgnoreCase(username.trim());
        return (admins != null && !admins.isEmpty()) ? admins.get(0) : null;
    }

    @GetMapping
    public String viewHistoryDashboard(HttpSession session, Model model) {
        Admin admin = getLoggedInAdmin(session);
        if (admin == null) {
            return "redirect:/admin-login";
        }

        model.addAttribute("adminName", admin.getAdminName());
        model.addAttribute("activeMenu", "history-records");

        // Load all papers, submissions, and exam attempts to compute statistics
        List<Paper> papers = paperRepository.findByAdminId(admin.getId());
        List<Submission> submissions = submissionRepository.findByPaperAdminId(admin.getId());
        List<ExamAttempt> attempts = examAttemptRepository.findAll();

        long totalExamsConducted = papers.stream().filter(p -> "ENDED".equalsIgnoreCase(p.getExamStatus()) || "COMPLETED".equalsIgnoreCase(p.getExamStatus())).count();
        long endedExams = examRepository.findAll().stream().filter(e -> "ENDED".equalsIgnoreCase(e.getExamStatus())).count();
        totalExamsConducted += endedExams;

        long totalUploadedPapers = papers.size();
        long totalStudentsAppeared = submissions.size() + attempts.size();

        // Submissions Results
        List<Result> results = resultRepository.findAll().stream()
                .filter(r -> r.getSubmission() != null && r.getSubmission().getPaper() != null && 
                             r.getSubmission().getPaper().getAdmin() != null &&
                             r.getSubmission().getPaper().getAdmin().getId().equals(admin.getId()))
                .collect(Collectors.toList());

        long totalStudentsPassed = results.stream().filter(r -> "PASSED".equalsIgnoreCase(r.getResultStatus()) || "PASS".equalsIgnoreCase(r.getResultStatus())).count();
        long totalStudentsFailed = results.stream().filter(r -> "FAILED".equalsIgnoreCase(r.getResultStatus()) || "FAIL".equalsIgnoreCase(r.getResultStatus())).count();

        double highestMarks = results.stream().mapToDouble(r -> r.getObtainedMarks() != null ? r.getObtainedMarks() : 0.0).max().orElse(0.0);
        double totalObtainedSum = results.stream().mapToDouble(r -> r.getObtainedMarks() != null ? r.getObtainedMarks() : 0.0).sum();
        long evaluatedCount = results.size();

        // Merge ExamAttempt scores into dashboard statistics
        for (ExamAttempt att : attempts) {
            Exam exam = att.getExam();
            if (exam == null) continue;
            List<Answer> attAnswers = answerRepository.findByExamAttemptId(att.getId());
            double obtained = 0.0;
            double total = exam.getTotalMarks() != null ? exam.getTotalMarks() : 100.0;
            boolean hasMarks = false;
            for (Answer a : attAnswers) {
                if (a.getMarksObtained() != null) {
                    obtained += a.getMarksObtained();
                    hasMarks = true;
                }
            }
            if (hasMarks) {
                if (obtained > highestMarks) highestMarks = obtained;
                totalObtainedSum += obtained;
                evaluatedCount++;
                if (obtained >= (total * 0.4)) {
                    totalStudentsPassed++;
                } else {
                    totalStudentsFailed++;
                }
            }
        }

        double averageMarks = evaluatedCount > 0 ? (totalObtainedSum / evaluatedCount) : 0.0;
        double passPercentage = totalStudentsAppeared > 0 ? ((double) totalStudentsPassed / totalStudentsAppeared) * 100 : 0.0;

        model.addAttribute("totalExamsConducted", totalExamsConducted);
        model.addAttribute("totalUploadedPapers", totalUploadedPapers);
        model.addAttribute("totalStudentsAppeared", totalStudentsAppeared);
        model.addAttribute("totalStudentsPassed", totalStudentsPassed);
        model.addAttribute("totalStudentsFailed", totalStudentsFailed);
        model.addAttribute("highestMarks", highestMarks);
        model.addAttribute("averageMarks", Math.round(averageMarks * 100.0) / 100.0);
        model.addAttribute("passPercentage", Math.round(passPercentage * 100.0) / 100.0);

        // Populate dropdown filter options dynamically
        Set<String> academicYears = new TreeSet<>(Comparator.reverseOrder());
        Set<String> batches = new TreeSet<>();
        Set<String> semesters = new TreeSet<>();
        Set<String> subjects = new TreeSet<>();
        Set<String> divisions = new TreeSet<>();
        Set<String> examNames = new TreeSet<>();

        for (Paper p : papers) {
            if (p.getAcademicYear() != null) academicYears.add(p.getAcademicYear());
            if (p.getCourse() != null) batches.add(p.getCourse());
            if (p.getSemester() != null) semesters.add(p.getSemester());
            if (p.getSubject() != null) subjects.add(p.getSubject());
            if (p.getDivision() != null) divisions.add(p.getDivision());
            examNames.add(p.getCourse() + " - " + p.getSubject());
        }

        for (ExamAttempt att : attempts) {
            Student s = att.getStudent();
            Exam e = att.getExam();
            if (s != null) {
                if (s.getAcademicYear() != null) academicYears.add(s.getAcademicYear());
                if (s.getBatch() != null) batches.add(s.getBatch());
                if (s.getSemester() != null) semesters.add(s.getSemester());
                if (s.getDivision() != null) divisions.add(s.getDivision());
            }
            if (e != null) {
                if (e.getSubject() != null) subjects.add(e.getSubject());
                if (e.getExamName() != null) examNames.add(e.getExamName());
            }
        }

        // Add defaults if empty to avoid blank selects
        if (academicYears.isEmpty()) academicYears.addAll(Arrays.asList("2025-26", "2024-25", "2023-24"));
        if (batches.isEmpty()) batches.addAll(Arrays.asList("BCA", "MCA", "B.Tech", "BBA"));
        if (semesters.isEmpty()) semesters.addAll(Arrays.asList("Semester 1", "Semester 2", "Semester 3", "Semester 4", "Semester 5", "Semester 6"));
        if (divisions.isEmpty()) divisions.addAll(Arrays.asList("A", "B", "C", "D"));

        model.addAttribute("academicYears", academicYears);
        model.addAttribute("batches", batches);
        model.addAttribute("semesters", semesters);
        model.addAttribute("subjects", subjects);
        model.addAttribute("divisions", divisions);
        model.addAttribute("examNames", examNames);

        return "admin/history_records";
    }

    @GetMapping("/search-papers")
    @ResponseBody
    public ResponseEntity<?> searchPapers(
            @RequestParam(value = "academicYear", required = false) String academicYear,
            @RequestParam(value = "batch", required = false) String batch,
            @RequestParam(value = "semester", required = false) String semester,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "division", required = false) String division,
            @RequestParam(value = "uploadDate", required = false) String uploadDate,
            @RequestParam(value = "searchQuery", required = false) String searchQuery,
            HttpSession session) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");

        List<Paper> papers = paperRepository.findByAdminId(admin.getId());

        // Apply filters in-memory
        List<Map<String, Object>> filtered = papers.stream()
                .filter(p -> academicYear == null || academicYear.isEmpty() || academicYear.equalsIgnoreCase(p.getAcademicYear()))
                .filter(p -> batch == null || batch.isEmpty() || batch.equalsIgnoreCase(p.getCourse()))
                .filter(p -> semester == null || semester.isEmpty() || semester.equalsIgnoreCase(p.getSemester()))
                .filter(p -> subject == null || subject.isEmpty() || subject.equalsIgnoreCase(p.getSubject()))
                .filter(p -> division == null || division.isEmpty() || division.equalsIgnoreCase(p.getDivision()))
                .filter(p -> {
                    if (uploadDate == null || uploadDate.isEmpty()) return true;
                    if (p.getUploadedAt() == null) return false;
                    String formattedDate = p.getUploadedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    return uploadDate.equals(formattedDate);
                })
                .filter(p -> {
                    if (searchQuery == null || searchQuery.trim().isEmpty()) return true;
                    String q = searchQuery.toLowerCase();
                    return (p.getSubject() != null && p.getSubject().toLowerCase().contains(q)) ||
                           (p.getCourse() != null && p.getCourse().toLowerCase().contains(q)) ||
                           (p.getSemester() != null && p.getSemester().toLowerCase().contains(q)) ||
                           (p.getManualContent() != null && p.getManualContent().toLowerCase().contains(q));
                })
                .map(p -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", p.getId());
                    map.put("subject", p.getSubject());
                    map.put("semester", p.getSemester());
                    map.put("batch", p.getCourse());
                    map.put("division", p.getDivision() != null ? p.getDivision() : "A");
                    map.put("uploadDate", p.getUploadedAt() != null ? p.getUploadedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")) : "-");
                    map.put("uploadedBy", admin.getAdminName());
                    
                    // Fetch total questions count
                    long questionCount = questionRepository.findByPaperId(p.getId()).size();
                    map.put("totalQuestions", questionCount);
                    map.put("totalMarks", p.getTotalMarks());
                    map.put("duration", p.getExamDuration());
                    
                    // Map Status
                    String status = p.getExamStatus();
                    if ("DRAFT".equalsIgnoreCase(status) || "PUBLISHED".equalsIgnoreCase(status)) {
                        map.put("status", "Scheduled");
                    } else if ("ACTIVE".equalsIgnoreCase(status)) {
                        map.put("status", "Active");
                    } else {
                        map.put("status", "Completed");
                    }
                    map.put("filePath", p.getFilePath());
                    map.put("isManual", p.getManualContent() != null && !p.getManualContent().trim().isEmpty());
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/search-students")
    @ResponseBody
    public ResponseEntity<?> searchStudents(
            @RequestParam(value = "academicYear", required = false) String academicYear,
            @RequestParam(value = "semester", required = false) String semester,
            @RequestParam(value = "division", required = false) String division,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "examName", required = false) String examName,
            @RequestParam(value = "batch", required = false) String batch,
            @RequestParam(value = "searchQuery", required = false) String searchQuery,
            @RequestParam(value = "sortBy", defaultValue = "studentName") String sortBy,
            HttpSession session) {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");

        List<StudentRecordDTO> records = getStudentRecords(admin.getId());
        List<StudentRecordDTO> filtered = filterStudentRecords(records, academicYear, semester, division, subject, examName, batch, searchQuery);

        // Apply Sorting
        if ("highestMarks".equalsIgnoreCase(sortBy)) {
            filtered.sort((a, b) -> Double.compare(b.getObtainedMarks(), a.getObtainedMarks()));
        } else if ("lowestMarks".equalsIgnoreCase(sortBy)) {
            filtered.sort((a, b) -> Double.compare(a.getObtainedMarks(), b.getObtainedMarks()));
        } else if ("studentName".equalsIgnoreCase(sortBy)) {
            filtered.sort(Comparator.comparing(StudentRecordDTO::getStudentName, Comparator.nullsLast(String::compareToIgnoreCase)));
        } else if ("rollNo".equalsIgnoreCase(sortBy)) {
            filtered.sort(Comparator.comparing(r -> {
                try {
                    return Integer.parseInt(r.getRollNo());
                } catch (Exception e) {
                    return 999;
                }
            }));
        } else if ("percentage".equalsIgnoreCase(sortBy)) {
            filtered.sort((a, b) -> Double.compare(b.getPercentage(), a.getPercentage()));
        } else if ("uploadDate".equalsIgnoreCase(sortBy)) {
            filtered.sort(Comparator.comparing(StudentRecordDTO::getSubmissionTime, Comparator.nullsLast(String::compareTo)).reversed());
        }

        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/student-details")
    @ResponseBody
    public ResponseEntity<?> getStudentRecordDetails(
            @RequestParam("submissionId") Long submissionId,
            @RequestParam(value = "type", defaultValue = "paper") String type,
            HttpSession session) {
        Admin admin = getLoggedInAdmin(session);
        if (admin == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");

        if ("exam".equalsIgnoreCase(type)) {
            Optional<ExamAttempt> optAttempt = examAttemptRepository.findById(submissionId);
            if (optAttempt.isEmpty()) return ResponseEntity.notFound().build();

            ExamAttempt att = optAttempt.get();
            Student student = att.getStudent();
            Exam exam = att.getExam();

            Map<String, Object> details = new HashMap<>();

            // Student Profile
            Map<String, Object> profile = new HashMap<>();
            profile.put("name", student != null ? student.getStudentName() : "Unknown");
            profile.put("rollNo", student != null ? student.getRollNo() : 0);
            profile.put("enrollmentNo", student != null ? student.getEnrollmentNo() : "-");
            profile.put("grNo", student != null ? student.getGrNo() : "-");
            profile.put("division", student != null ? student.getDivision() : "A");
            profile.put("semester", student != null ? student.getSemester() : "Semester 3");
            profile.put("batch", student != null ? student.getBatch() : "BCA");
            profile.put("phoneNo", student != null ? student.getPhoneNo() : "-");
            details.put("profile", profile);

            // Examination Details
            Map<String, Object> examMap = new HashMap<>();
            examMap.put("subject", exam != null ? exam.getSubject() : "Unknown");
            examMap.put("examName", exam != null ? exam.getExamName() : "Theory Exam");
            examMap.put("duration", exam != null ? exam.getExamDuration() : 120);
            examMap.put("submissionTime", att.getEndTime() != null ? att.getEndTime().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")) : "-");
            examMap.put("evaluationDate", "Checked");

            List<Answer> answers = answerRepository.findByExamAttemptId(submissionId);
            double totalObtained = 0.0;
            double totalMax = exam != null && exam.getTotalMarks() != null ? exam.getTotalMarks() : 100.0;
            boolean hasMarks = false;
            for (Answer a : answers) {
                if (a.getMarksObtained() != null) {
                    totalObtained += a.getMarksObtained();
                    hasMarks = true;
                }
            }

            examMap.put("totalMarks", totalMax);
            examMap.put("obtainedMarks", totalObtained);
            examMap.put("percentage", totalMax > 0 ? Math.round((totalObtained / totalMax) * 100.0 * 100.0) / 100.0 : 0.0);
            examMap.put("status", att.getStatus());
            examMap.put("resultStatus", (totalObtained >= (totalMax * 0.4)) ? "PASSED" : "FAILED");
            details.put("exam", examMap);

            // Question-wise Marks
            List<Map<String, Object>> qMarks = answers.stream().map(a -> {
                Map<String, Object> amap = new HashMap<>();
                amap.put("questionText", a.getQuestionText());
                amap.put("studentAnswer", a.getStudentAnswer());
                amap.put("maxMarks", a.getMaxMarks());
                amap.put("marksObtained", a.getMarksObtained() != null ? a.getMarksObtained() : 0.0);
                amap.put("feedback", a.getFeedback() != null ? a.getFeedback() : "-");
                return amap;
            }).collect(Collectors.toList());
            details.put("questions", qMarks);

            return ResponseEntity.ok(details);
        }

        Optional<Submission> opt = submissionRepository.findById(submissionId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission sub = opt.get();
        Student student = sub.getStudent();
        Paper paper = sub.getPaper();

        Optional<Result> optResult = resultRepository.findBySubmissionId(submissionId);
        Result result = optResult.orElse(null);

        Map<String, Object> details = new HashMap<>();
        
        // Student Profile
        Map<String, Object> profile = new HashMap<>();
        profile.put("name", student.getStudentName());
        profile.put("rollNo", student.getRollNo());
        profile.put("enrollmentNo", student.getEnrollmentNo());
        profile.put("grNo", student.getGrNo());
        profile.put("division", student.getDivision());
        profile.put("semester", student.getSemester());
        profile.put("batch", student.getBatch());
        profile.put("phoneNo", student.getPhoneNo());
        details.put("profile", profile);

        // Examination Details
        Map<String, Object> exam = new HashMap<>();
        exam.put("subject", paper.getSubject());
        exam.put("examName", paper.getCourse() + " - " + paper.getSubject());
        exam.put("duration", paper.getExamDuration());
        exam.put("submissionTime", sub.getSubmittedAt() != null ? sub.getSubmittedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")) : "-");
        exam.put("evaluationDate", result != null && result.getEvaluatedAt() != null ? result.getEvaluatedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")) : "Pending");
        exam.put("totalMarks", paper.getTotalMarks());
        exam.put("obtainedMarks", result != null && result.getObtainedMarks() != null ? result.getObtainedMarks() : 0.0);
        exam.put("percentage", result != null && result.getPercentage() != null ? result.getPercentage() : 0.0);
        exam.put("status", sub.getStatus());
        exam.put("resultStatus", result != null && result.getResultStatus() != null ? result.getResultStatus() : "PENDING");
        details.put("exam", exam);

        // Question-wise Marks
        List<Answer> answers = answerRepository.findBySubmissionId(submissionId);
        List<Map<String, Object>> qMarks = answers.stream().map(a -> {
            Map<String, Object> amap = new HashMap<>();
            amap.put("questionText", a.getQuestionText());
            amap.put("studentAnswer", a.getStudentAnswer());
            amap.put("maxMarks", a.getMaxMarks());
            amap.put("marksObtained", a.getMarksObtained() != null ? a.getMarksObtained() : 0.0);
            amap.put("feedback", a.getFeedback() != null ? a.getFeedback() : "-");
            return amap;
        }).collect(Collectors.toList());
        details.put("questions", qMarks);

        return ResponseEntity.ok(details);
    }

    @GetMapping("/paper/{id}/download")
    public void downloadPaper(@PathVariable("id") Long paperId, HttpSession session, HttpServletResponse response) throws IOException {
        Admin admin = getLoggedInAdmin(session);
        if (admin == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        Optional<Paper> opt = paperRepository.findById(paperId);
        if (opt.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Paper not found");
            return;
        }

        Paper paper = opt.get();
        if (paper.getFilePath() != null && !paper.getFilePath().trim().isEmpty()) {
            // Forward directly to static resource path or stream file
            response.sendRedirect(paper.getFilePath());
        } else {
            // Generate a PDF of manual questions
            response.setContentType("application/pdf");
            response.setHeader("Content-Disposition", "attachment; filename=Paper_" + paper.getSubject().replaceAll("\\s+", "_") + ".pdf");
            
            Document document = new Document(PageSize.A4, 54, 54, 54, 54);
            PdfWriter.getInstance(document, response.getOutputStream());
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font metaFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font textFont = FontFactory.getFont(FontFactory.HELVETICA, 11);

            document.add(new Paragraph("UNIVERSITY THEORY EXAMINATION PAPER", titleFont));
            document.add(new Paragraph("\n"));
            document.add(new Paragraph("Subject: " + paper.getSubject() + " | Course: " + paper.getCourse() + " | Semester: " + paper.getSemester(), metaFont));
            document.add(new Paragraph("Duration: " + paper.getExamDuration() + " minutes | Total Marks: " + paper.getTotalMarks(), metaFont));
            document.add(new Paragraph("-------------------------------------------------------------------------------------------------------------", metaFont));
            document.add(new Paragraph("\nQUESTIONS:\n\n"));

            List<Question> questions = questionRepository.findByPaperId(paperId);
            int idx = 1;
            for (Question q : questions) {
                Paragraph p = new Paragraph("Q" + idx + ". " + q.getText() + " [" + q.getMarks() + " Marks]", textFont);
                p.setSpacingAfter(10f);
                document.add(p);
                idx++;
            }

            document.close();
        }
    }

    @GetMapping("/student/{id}/answer-sheet")
    public void downloadAnswerSheet(
            @PathVariable("id") Long submissionId,
            @RequestParam(value = "type", defaultValue = "paper") String type,
            HttpSession session,
            HttpServletResponse response) throws IOException {
        
        Admin admin = getLoggedInAdmin(session);
        if (admin == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        Student student = null;
        String subject = "";
        String batch = "";
        String semester = "";
        double obtainedMarks = 0.0;
        double totalMarks = 100.0;
        String resultStatus = "PENDING";
        List<Answer> answers = null;

        if ("exam".equalsIgnoreCase(type)) {
            Optional<ExamAttempt> optAttempt = examAttemptRepository.findById(submissionId);
            if (optAttempt.isEmpty()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Exam attempt not found");
                return;
            }
            ExamAttempt att = optAttempt.get();
            student = att.getStudent();
            Exam exam = att.getExam();
            subject = exam != null ? exam.getSubject() : "Unknown";
            batch = student != null ? student.getBatch() : "BCA";
            semester = student != null ? student.getSemester() : "Semester 3";
            totalMarks = exam != null && exam.getTotalMarks() != null ? exam.getTotalMarks() : 100.0;

            answers = answerRepository.findByExamAttemptId(submissionId);
            boolean hasMarks = false;
            for (Answer a : answers) {
                if (a.getMarksObtained() != null) {
                    obtainedMarks += a.getMarksObtained();
                    hasMarks = true;
                }
            }
            if ("Submitted".equalsIgnoreCase(att.getStatus()) || "Terminated".equalsIgnoreCase(att.getStatus())) {
                resultStatus = (obtainedMarks >= (totalMarks * 0.4)) ? "PASSED" : "FAILED";
            }
        } else {
            Optional<Submission> opt = submissionRepository.findById(submissionId);
            if (opt.isEmpty()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Submission not found");
                return;
            }
            Submission sub = opt.get();
            student = sub.getStudent();
            Paper paper = sub.getPaper();
            subject = paper != null ? paper.getSubject() : "Unknown";
            batch = student != null ? student.getBatch() : "BCA";
            semester = student != null ? student.getSemester() : "Semester 3";
            totalMarks = paper != null && paper.getTotalMarks() != null ? paper.getTotalMarks() : 100.0;

            Optional<Result> optResult = resultRepository.findBySubmissionId(submissionId);
            Result res = optResult.orElse(null);
            obtainedMarks = res != null ? res.getObtainedMarks() : 0.0;
            resultStatus = res != null ? res.getResultStatus() : "PENDING";
            answers = answerRepository.findBySubmissionId(submissionId);
        }

        if (student == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Student not found");
            return;
        }

        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=AnswerSheet_" + student.getStudentName().replaceAll("\\s+", "_") + ".pdf");

        Document document = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(document, response.getOutputStream());
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font subTitleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

        // Header
        Paragraph title = new Paragraph("ONLINE EXAMINATION ANSWER SHEET", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);
        document.add(new Paragraph("\n"));

        // Student & Exam details table
        PdfPTable detailsTable = new PdfPTable(4);
        detailsTable.setWidthPercentage(100);
        detailsTable.setSpacingAfter(15);
        detailsTable.addCell(new PdfPCell(new Phrase("Student Name", labelFont)));
        detailsTable.addCell(new PdfPCell(new Phrase(student.getStudentName(), bodyFont)));
        detailsTable.addCell(new PdfPCell(new Phrase("Roll / Div", labelFont)));
        detailsTable.addCell(new PdfPCell(new Phrase(student.getRollNo() + " / " + student.getDivision(), bodyFont)));

        detailsTable.addCell(new PdfPCell(new Phrase("Enrollment No", labelFont)));
        detailsTable.addCell(new PdfPCell(new Phrase(student.getEnrollmentNo(), bodyFont)));
        detailsTable.addCell(new PdfPCell(new Phrase("GR Number", labelFont)));
        detailsTable.addCell(new PdfPCell(new Phrase(student.getGrNo(), bodyFont)));

        detailsTable.addCell(new PdfPCell(new Phrase("Subject", labelFont)));
        detailsTable.addCell(new PdfPCell(new Phrase(subject, bodyFont)));
        detailsTable.addCell(new PdfPCell(new Phrase("Batch / Sem", labelFont)));
        detailsTable.addCell(new PdfPCell(new Phrase(batch + " / " + semester, bodyFont)));

        detailsTable.addCell(new PdfPCell(new Phrase("Score Obtained", labelFont)));
        detailsTable.addCell(new PdfPCell(new Phrase(obtainedMarks + " / " + totalMarks, bodyFont)));
        detailsTable.addCell(new PdfPCell(new Phrase("Result Status", labelFont)));
        detailsTable.addCell(new PdfPCell(new Phrase(resultStatus, bodyFont)));

        document.add(detailsTable);
        document.add(new Paragraph("ANSWERS & EVALUATION DETAILS", subTitleFont));
        document.add(new Paragraph("-------------------------------------------------------------------------------------------------------------", labelFont));
        document.add(new Paragraph("\n"));

        int idx = 1;
        for (Answer a : answers) {
            Paragraph qPara = new Paragraph("Question " + idx + ": " + a.getQuestionText(), labelFont);
            qPara.setSpacingAfter(4f);
            document.add(qPara);

            Paragraph ansLabel = new Paragraph("Student's Answer:", labelFont);
            ansLabel.setIndentationLeft(15f);
            document.add(ansLabel);

            Paragraph ansPara = new Paragraph(a.getStudentAnswer() != null ? a.getStudentAnswer() : "[No Answer Submitted]", bodyFont);
            ansPara.setIndentationLeft(25f);
            ansPara.setSpacingAfter(8f);
            document.add(ansPara);

            Paragraph scorePara = new Paragraph("Marks Obtained: " + (a.getMarksObtained() != null ? a.getMarksObtained() : "0.0") + " / " + a.getMaxMarks() + "  |  Feedback: " + (a.getFeedback() != null ? a.getFeedback() : "-"), labelFont);
            scorePara.setIndentationLeft(15f);
            scorePara.setSpacingAfter(15f);
            document.add(scorePara);

            idx++;
        }

        document.close();
    }

    @GetMapping("/student/{id}/result-pdf")
    public void downloadResultCard(
            @PathVariable("id") Long submissionId,
            @RequestParam(value = "type", defaultValue = "paper") String type,
            HttpSession session,
            HttpServletResponse response) throws IOException {
        
        Admin admin = getLoggedInAdmin(session);
        if (admin == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        Student student = null;
        String subject = "";
        double obtainedMarks = 0.0;
        double totalMarks = 100.0;
        double percentage = 0.0;
        String resultStatus = "PENDING";
        String academicYear = "2025-26";

        if ("exam".equalsIgnoreCase(type)) {
            Optional<ExamAttempt> optAttempt = examAttemptRepository.findById(submissionId);
            if (optAttempt.isEmpty()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Exam attempt not found");
                return;
            }
            ExamAttempt att = optAttempt.get();
            student = att.getStudent();
            Exam exam = att.getExam();
            subject = exam != null ? exam.getSubject() : "Unknown";
            totalMarks = exam != null && exam.getTotalMarks() != null ? exam.getTotalMarks() : 100.0;

            List<Answer> answers = answerRepository.findByExamAttemptId(submissionId);
            for (Answer a : answers) {
                if (a.getMarksObtained() != null) {
                    obtainedMarks += a.getMarksObtained();
                }
            }
            percentage = totalMarks > 0 ? Math.round((obtainedMarks / totalMarks) * 100.0 * 100.0) / 100.0 : 0.0;
            if ("Submitted".equalsIgnoreCase(att.getStatus()) || "Terminated".equalsIgnoreCase(att.getStatus())) {
                resultStatus = (obtainedMarks >= (totalMarks * 0.4)) ? "PASSED" : "FAILED";
            }
            if (student != null) {
                academicYear = student.getAcademicYear();
            }
        } else {
            Optional<Submission> opt = submissionRepository.findById(submissionId);
            if (opt.isEmpty()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Submission not found");
                return;
            }
            Submission sub = opt.get();
            student = sub.getStudent();
            Paper paper = sub.getPaper();
            subject = paper != null ? paper.getSubject() : "Unknown";
            totalMarks = paper != null && paper.getTotalMarks() != null ? paper.getTotalMarks() : 100.0;

            Optional<Result> optResult = resultRepository.findBySubmissionId(submissionId);
            Result res = optResult.orElse(null);
            obtainedMarks = res != null ? res.getObtainedMarks() : 0.0;
            percentage = res != null ? res.getPercentage() : 0.0;
            resultStatus = res != null ? res.getResultStatus() : "PENDING";
            if (student != null) {
                academicYear = student.getAcademicYear();
            }
        }

        if (student == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Student not found");
            return;
        }

        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=Marksheet_" + student.getStudentName().replaceAll("\\s+", "_") + ".pdf");

        Document document = new Document(PageSize.A4.rotate(), 54, 54, 54, 54);
        PdfWriter.getInstance(document, response.getOutputStream());
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20);
        Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA, 12);
        Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font tableBodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

        // Header
        Paragraph title = new Paragraph("UNIVERSITY THEORY EXAMINATION MARKSHEET", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        Paragraph subtitle = new Paragraph("ACADEMIC YEAR: " + academicYear, subtitleFont);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        document.add(subtitle);
        document.add(new Paragraph("\n\n"));

        // Details Table
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingAfter(20);

        table.addCell(new PdfPCell(new Phrase("Student Name: " + student.getStudentName(), tableBodyFont)));
        table.addCell(new PdfPCell(new Phrase("Roll Number: " + student.getRollNo(), tableBodyFont)));
        table.addCell(new PdfPCell(new Phrase("Enrollment No: " + student.getEnrollmentNo(), tableBodyFont)));
        table.addCell(new PdfPCell(new Phrase("GR Number: " + student.getGrNo(), tableBodyFont)));
        table.addCell(new PdfPCell(new Phrase("Semester: " + student.getSemester(), tableBodyFont)));
        table.addCell(new PdfPCell(new Phrase("Batch & Division: " + student.getBatch() + " (Div " + student.getDivision() + ")", tableBodyFont)));

        document.add(table);

        // Score Table
        PdfPTable scoreTable = new PdfPTable(4);
        scoreTable.setWidthPercentage(100);
        scoreTable.setSpacingAfter(40);
        scoreTable.addCell(new PdfPCell(new Phrase("Subject Name", tableHeaderFont)));
        scoreTable.addCell(new PdfPCell(new Phrase("Marks Obtained", tableHeaderFont)));
        scoreTable.addCell(new PdfPCell(new Phrase("Total Marks", tableHeaderFont)));
        scoreTable.addCell(new PdfPCell(new Phrase("Percentage / Result", tableHeaderFont)));

        scoreTable.addCell(new PdfPCell(new Phrase(subject, tableBodyFont)));
        scoreTable.addCell(new PdfPCell(new Phrase(String.valueOf(obtainedMarks), tableBodyFont)));
        scoreTable.addCell(new PdfPCell(new Phrase(String.valueOf(totalMarks), tableBodyFont)));
        scoreTable.addCell(new PdfPCell(new Phrase(percentage + "% (" + resultStatus + ")", tableBodyFont)));

        document.add(scoreTable);

        // Signatures
        Paragraph sig = new Paragraph("_____________________\nController of Examinations", subtitleFont);
        sig.setAlignment(Element.ALIGN_RIGHT);
        document.add(sig);

        document.close();
    }

    @GetMapping("/export/csv")
    public void exportToCsv(
            @RequestParam(value = "academicYear", required = false) String academicYear,
            @RequestParam(value = "semester", required = false) String semester,
            @RequestParam(value = "division", required = false) String division,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "examName", required = false) String examName,
            @RequestParam(value = "batch", required = false) String batch,
            @RequestParam(value = "searchQuery", required = false) String searchQuery,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        List<StudentRecordDTO> records = filterStudentRecords(
                getStudentRecords(admin.getId()),
                academicYear, semester, division, subject, examName, batch, searchQuery
        );

        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=student_examination_records.csv");

        StringBuilder sb = new StringBuilder();
        sb.append("Roll Number,Enrollment Number,GR Number,Student Name,Division,Semester,Batch,Subject,Exam Name,Marks Obtained,Total Marks,Percentage,Submission Time,Evaluation Status,Result Status\n");

        for (StudentRecordDTO r : records) {
            sb.append(escapeCsvField(r.getRollNo())).append(",")
              .append(escapeCsvField(r.getEnrollmentNo())).append(",")
              .append(escapeCsvField(r.getGrNo())).append(",")
              .append(escapeCsvField(r.getStudentName())).append(",")
              .append(escapeCsvField(r.getDivision())).append(",")
              .append(escapeCsvField(r.getSemester())).append(",")
              .append(escapeCsvField(r.getBatch())).append(",")
              .append(escapeCsvField(r.getSubject())).append(",")
              .append(escapeCsvField(r.getExamName())).append(",")
              .append(r.getObtainedMarks()).append(",")
              .append(r.getTotalMarks()).append(",")
              .append(r.getPercentage()).append(",")
              .append(escapeCsvField(r.getSubmissionTime())).append(",")
              .append(escapeCsvField(r.getEvaluationStatus())).append(",")
              .append(escapeCsvField(r.getResultStatus())).append("\n");
        }

        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String escapeCsvField(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    @GetMapping("/export/excel")
    public void exportToExcel(
            @RequestParam(value = "academicYear", required = false) String academicYear,
            @RequestParam(value = "semester", required = false) String semester,
            @RequestParam(value = "division", required = false) String division,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "examName", required = false) String examName,
            @RequestParam(value = "batch", required = false) String batch,
            @RequestParam(value = "searchQuery", required = false) String searchQuery,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        List<StudentRecordDTO> records = filterStudentRecords(
                getStudentRecords(admin.getId()),
                academicYear, semester, division, subject, examName, batch, searchQuery
        );

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Records");

        Row headerRow = sheet.createRow(0);
        String[] headers = {"Roll No", "Enrollment No", "GR No", "Student Name", "Division", "Semester", "Batch", "Subject", "Exam Name", "Obtained Marks", "Total Marks", "Percentage", "Submission Time", "Evaluation Status", "Result Status"};
        
        CellStyle headerStyle = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;
        for (StudentRecordDTO r : records) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(r.getRollNo());
            row.createCell(1).setCellValue(r.getEnrollmentNo());
            row.createCell(2).setCellValue(r.getGrNo());
            row.createCell(3).setCellValue(r.getStudentName());
            row.createCell(4).setCellValue(r.getDivision());
            row.createCell(5).setCellValue(r.getSemester());
            row.createCell(6).setCellValue(r.getBatch());
            row.createCell(7).setCellValue(r.getSubject());
            row.createCell(8).setCellValue(r.getExamName());
            row.createCell(9).setCellValue(r.getObtainedMarks());
            row.createCell(10).setCellValue(r.getTotalMarks());
            row.createCell(11).setCellValue(r.getPercentage());
            row.createCell(12).setCellValue(r.getSubmissionTime());
            row.createCell(13).setCellValue(r.getEvaluationStatus());
            row.createCell(14).setCellValue(r.getResultStatus());
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=student_examination_records.xlsx");
        workbook.write(response.getOutputStream());
        workbook.close();
    }

    @GetMapping("/export/pdf")
    public void exportToPdf(
            @RequestParam(value = "academicYear", required = false) String academicYear,
            @RequestParam(value = "semester", required = false) String semester,
            @RequestParam(value = "division", required = false) String division,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "examName", required = false) String examName,
            @RequestParam(value = "batch", required = false) String batch,
            @RequestParam(value = "searchQuery", required = false) String searchQuery,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        Admin admin = getLoggedInAdmin(session);
        if (admin == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        List<StudentRecordDTO> records = filterStudentRecords(
                getStudentRecords(admin.getId()),
                academicYear, semester, division, subject, examName, batch, searchQuery
        );

        response.setContentType("application/pdf");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=student_examination_records.pdf");

        Document document = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        PdfWriter.getInstance(document, response.getOutputStream());
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font tableBodyFont = FontFactory.getFont(FontFactory.HELVETICA, 8);

        Paragraph title = new Paragraph("STUDENT EXAMINATION RECORDS REPORT", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);
        document.add(new Paragraph("\n"));

        PdfPTable table = new PdfPTable(11);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1.0f, 1.8f, 1.8f, 2.8f, 1.0f, 1.5f, 1.2f, 2.0f, 1.2f, 1.2f, 1.5f});

        String[] headers = {"Roll No", "Enrollment No", "GR No", "Student Name", "Div", "Sem", "Batch", "Subject", "Score", "Pct%", "Result"};
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, tableHeaderFont));
            cell.setBackgroundColor(new java.awt.Color(230, 230, 230));
            cell.setPadding(5);
            table.addCell(cell);
        }

        for (StudentRecordDTO r : records) {
            table.addCell(new PdfPCell(new Phrase(r.getRollNo(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getEnrollmentNo(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getGrNo(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getStudentName(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getDivision(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getSemester(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getBatch(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getSubject(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getObtainedMarks() + "/" + r.getTotalMarks(), tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getPercentage() + "%", tableBodyFont)));
            table.addCell(new PdfPCell(new Phrase(r.getResultStatus(), tableBodyFont)));
        }

        document.add(table);
        document.close();
    }

    private boolean matchSemester(String recordSem, String filterSem) {
        if (filterSem == null || filterSem.isEmpty()) return true;
        if (recordSem == null || recordSem.isEmpty()) return false;
        String s1 = recordSem.trim().toLowerCase().replaceAll("\\s+", "");
        String s2 = filterSem.trim().toLowerCase().replaceAll("\\s+", "");
        if (s1.equals(s2)) return true;
        String d1 = s1.replaceAll("\\D+", "");
        String d2 = s2.replaceAll("\\D+", "");
        if (!d1.isEmpty() && !d2.isEmpty() && d1.equals(d2)) return true;
        return s1.contains(s2) || s2.contains(s1);
    }

    private boolean matchDivision(String recordDiv, String filterDiv) {
        if (filterDiv == null || filterDiv.isEmpty()) return true;
        if (recordDiv == null || recordDiv.isEmpty()) {
            return "A".equalsIgnoreCase(filterDiv);
        }
        return recordDiv.equalsIgnoreCase(filterDiv);
    }

    private boolean matchExamName(String recordExam, String filterExam) {
        if (filterExam == null || filterExam.isEmpty()) return true;
        if (recordExam == null || recordExam.isEmpty()) return false;
        return recordExam.toLowerCase().contains(filterExam.toLowerCase());
    }

    private List<StudentRecordDTO> filterStudentRecords(
            List<StudentRecordDTO> records,
            String academicYear,
            String semester,
            String division,
            String subject,
            String examName,
            String batch,
            String searchQuery) {
        
        return records.stream()
                .filter(r -> academicYear == null || academicYear.isEmpty() || academicYear.equalsIgnoreCase(r.getAcademicYear()))
                .filter(r -> matchSemester(r.getSemester(), semester))
                .filter(r -> matchDivision(r.getDivision(), division))
                .filter(r -> subject == null || subject.isEmpty() || subject.equalsIgnoreCase(r.getSubject()))
                .filter(r -> matchExamName(r.getExamName(), examName))
                .filter(r -> batch == null || batch.isEmpty() || batch.equalsIgnoreCase(r.getBatch()))
                .filter(r -> {
                    if (searchQuery == null || searchQuery.trim().isEmpty()) return true;
                    String q = searchQuery.toLowerCase();
                    return (r.getStudentName() != null && r.getStudentName().toLowerCase().contains(q)) ||
                           (r.getEnrollmentNo() != null && r.getEnrollmentNo().toLowerCase().contains(q)) ||
                           (r.getGrNo() != null && r.getGrNo().toLowerCase().contains(q)) ||
                           (r.getRollNo() != null && r.getRollNo().toLowerCase().contains(q));
                })
                .collect(Collectors.toList());
    }

    private List<StudentRecordDTO> getStudentRecords(Long adminId) {
        List<Submission> submissions = submissionRepository.findByPaperAdminId(adminId);
        List<Result> results = resultRepository.findAll();
        Map<Long, Result> resultMap = new HashMap<>();
        for (Result r : results) {
            if (r.getSubmission() != null) {
                resultMap.put(r.getSubmission().getId(), r);
            }
        }

        List<StudentRecordDTO> dtos = new ArrayList<>();

        // 1. Submissions (Paper attempts)
        for (Submission sub : submissions) {
            Student student = sub.getStudent();
            Paper paper = sub.getPaper();
            if (student == null || paper == null) continue;

            Result result = resultMap.get(sub.getId());

            StudentRecordDTO dto = new StudentRecordDTO();
            dto.setSubmissionId(sub.getId());
            dto.setRollNo(student.getRollNo() != null ? String.valueOf(student.getRollNo()) : "-");
            dto.setEnrollmentNo(student.getEnrollmentNo());
            dto.setGrNo(student.getGrNo());
            dto.setStudentName(student.getStudentName());
            dto.setDivision(student.getDivision());
            dto.setSemester(student.getSemester());
            dto.setBatch(student.getBatch());
            dto.setSubject(paper.getSubject());
            dto.setExamName(paper.getCourse() + " - " + paper.getSubject());
            dto.setObtainedMarks(result != null && result.getObtainedMarks() != null ? result.getObtainedMarks() : 0.0);
            dto.setTotalMarks(paper.getTotalMarks() != null ? paper.getTotalMarks() : 0.0);
            dto.setPercentage(result != null && result.getPercentage() != null ? result.getPercentage() : 0.0);
            dto.setSubmissionTime(sub.getSubmittedAt() != null ? sub.getSubmittedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")) : "-");
            dto.setEvaluationStatus("Checked".equalsIgnoreCase(sub.getStatus()) ? "Checked" : "Pending");
            dto.setResultStatus(result != null && result.getResultStatus() != null ? result.getResultStatus() : "PENDING");
            dto.setAcademicYear(student.getAcademicYear());
            dto.setType("paper");

            dtos.add(dto);
        }

        // 2. ExamAttempts (Scheduled exam attempts)
        List<ExamAttempt> attempts = examAttemptRepository.findAll();
        for (ExamAttempt att : attempts) {
            Student student = att.getStudent();
            Exam exam = att.getExam();
            if (student == null || exam == null) continue;

            StudentRecordDTO dto = new StudentRecordDTO();
            dto.setSubmissionId(att.getId());
            dto.setRollNo(student.getRollNo() != null ? String.valueOf(student.getRollNo()) : "-");
            dto.setEnrollmentNo(student.getEnrollmentNo());
            dto.setGrNo(student.getGrNo());
            dto.setStudentName(student.getStudentName());
            dto.setDivision(student.getDivision());
            dto.setSemester(student.getSemester());
            dto.setBatch(student.getBatch());
            dto.setSubject(exam.getSubject());
            dto.setExamName(exam.getExamName());

            List<Answer> answers = answerRepository.findByExamAttemptId(att.getId());
            double obtained = 0.0;
            double total = exam.getTotalMarks() != null ? exam.getTotalMarks() : 100.0;
            boolean hasMarks = false;
            for (Answer a : answers) {
                if (a.getMarksObtained() != null) {
                    obtained += a.getMarksObtained();
                    hasMarks = true;
                }
            }

            dto.setObtainedMarks(obtained);
            dto.setTotalMarks(total);
            dto.setPercentage(total > 0 ? Math.round((obtained / total) * 100.0 * 100.0) / 100.0 : 0.0);
            dto.setSubmissionTime(att.getEndTime() != null ? att.getEndTime().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")) : "-");
            dto.setEvaluationStatus(hasMarks ? "Checked" : "Pending");

            String resStatus = "PENDING";
            if ("Submitted".equalsIgnoreCase(att.getStatus()) || "Terminated".equalsIgnoreCase(att.getStatus())) {
                resStatus = (obtained >= (total * 0.4)) ? "PASSED" : "FAILED";
            }
            dto.setResultStatus(resStatus);
            dto.setAcademicYear(student.getAcademicYear());
            dto.setType("exam");

            dtos.add(dto);
        }

        return dtos;
    }

    // Student Record DTO class
    public static class StudentRecordDTO {
        private Long submissionId;
        private String rollNo;
        private String enrollmentNo;
        private String grNo;
        private String studentName;
        private String division;
        private String semester;
        private String batch;
        private String subject;
        private String examName;
        private Double obtainedMarks;
        private Double totalMarks;
        private Double percentage;
        private String submissionTime;
        private String evaluationStatus;
        private String resultStatus;
        private String academicYear;
        private String type;

        public Long getSubmissionId() { return submissionId; }
        public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
        public String getRollNo() { return rollNo; }
        public void setRollNo(String rollNo) { this.rollNo = rollNo; }
        public String getEnrollmentNo() { return enrollmentNo; }
        public void setEnrollmentNo(String enrollmentNo) { this.enrollmentNo = enrollmentNo; }
        public String getGrNo() { return grNo; }
        public void setGrNo(String grNo) { this.grNo = grNo; }
        public String getStudentName() { return studentName; }
        public void setStudentName(String studentName) { this.studentName = studentName; }
        public String getDivision() { return division; }
        public void setDivision(String division) { this.division = division; }
        public String getSemester() { return semester; }
        public void setSemester(String semester) { this.semester = semester; }
        public String getBatch() { return batch; }
        public void setBatch(String batch) { this.batch = batch; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getExamName() { return examName; }
        public void setExamName(String examName) { this.examName = examName; }
        public Double getObtainedMarks() { return obtainedMarks; }
        public void setObtainedMarks(Double obtainedMarks) { this.obtainedMarks = obtainedMarks; }
        public Double getTotalMarks() { return totalMarks; }
        public void setTotalMarks(Double totalMarks) { this.totalMarks = totalMarks; }
        public Double getPercentage() { return percentage; }
        public void setPercentage(Double percentage) { this.percentage = percentage; }
        public String getSubmissionTime() { return submissionTime; }
        public void setSubmissionTime(String submissionTime) { this.submissionTime = submissionTime; }
        public String getEvaluationStatus() { return evaluationStatus; }
        public void setEvaluationStatus(String evaluationStatus) { this.evaluationStatus = evaluationStatus; }
        public String getResultStatus() { return resultStatus; }
        public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
        public String getAcademicYear() { return academicYear; }
        public void setAcademicYear(String academicYear) { this.academicYear = academicYear; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
