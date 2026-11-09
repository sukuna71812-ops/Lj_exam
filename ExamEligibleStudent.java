package University.exam.Entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "exam_eligible_students")
public class ExamEligibleStudent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id")
    private Long examId;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "semester", length = 50)
    private String semester;

    @Column(name = "exam_type", length = 20)
    private String examType = "EXAM"; // "EXAM" or "PAPER"

    @Column(name = "enrollment_no", length = 50)
    private String enrollmentNo;

    @Column(name = "student_name", length = 200)
    private String studentName;

    @Column(name = "division", length = 20)
    private String division;

    @Column(name = "roll_no", length = 20)
    private String rollNo;

    @Column(name = "imported_by", length = 100)
    private String importedBy;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public ExamEligibleStudent() {}

    public ExamEligibleStudent(Long examId, Long studentId, String examType, String enrollmentNo,
                                String studentName, String division, String semester, String rollNo,
                                String importedBy, LocalDateTime importedAt) {
        this.examId = examId;
        this.studentId = studentId;
        this.examType = examType;
        this.enrollmentNo = enrollmentNo;
        this.studentName = studentName;
        this.division = division;
        this.semester = semester;
        this.rollNo = rollNo;
        this.importedBy = importedBy;
        this.importedAt = importedAt;
    }

    // Constructor without roll_no for backward compatibility
    public ExamEligibleStudent(Long examId, Long studentId, String examType, String enrollmentNo,
                                String studentName, String division, String semester,
                                String importedBy, LocalDateTime importedAt) {
        this.examId = examId;
        this.studentId = studentId;
        this.examType = examType;
        this.enrollmentNo = enrollmentNo;
        this.studentName = studentName;
        this.division = division;
        this.semester = semester;
        this.rollNo = "";
        this.importedBy = importedBy;
        this.importedAt = importedAt;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }

    public String getExamType() { return examType; }
    public void setExamType(String examType) { this.examType = examType; }

    public String getEnrollmentNo() { return enrollmentNo; }
    public void setEnrollmentNo(String enrollmentNo) { this.enrollmentNo = enrollmentNo; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = division; }

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public String getImportedBy() { return importedBy; }
    public void setImportedBy(String importedBy) { this.importedBy = importedBy; }

    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }

    public String getSemester() { return semester; }
    public void setSemester(String semester) { this.semester = semester; }
}
