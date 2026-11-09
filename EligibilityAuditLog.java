package University.exam.Entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "eligibility_audit_logs")
public class EligibilityAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id")
    private Long examId;

    @Column(name = "exam_type", length = 20)
    private String examType = "EXAM"; // "EXAM" or "PAPER"

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "upload_date")
    private LocalDateTime uploadDate;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "total_imported")
    private Integer totalImported;

    @Column(name = "invalid_count")
    private Integer invalidCount;

    @Column(name = "last_modified_date")
    private LocalDateTime lastModifiedDate;

    public EligibilityAuditLog() {}

    public EligibilityAuditLog(Long examId, String examType, String fileName,
                                LocalDateTime uploadDate, String uploadedBy,
                                Integer totalImported, Integer invalidCount) {
        this.examId = examId;
        this.examType = examType;
        this.fileName = fileName;
        this.uploadDate = uploadDate;
        this.uploadedBy = uploadedBy;
        this.totalImported = totalImported;
        this.invalidCount = invalidCount;
        this.lastModifiedDate = uploadDate;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }

    public String getExamType() { return examType; }
    public void setExamType(String examType) { this.examType = examType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public LocalDateTime getUploadDate() { return uploadDate; }
    public void setUploadDate(LocalDateTime uploadDate) { this.uploadDate = uploadDate; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public Integer getTotalImported() { return totalImported; }
    public void setTotalImported(Integer totalImported) { this.totalImported = totalImported; }

    public Integer getInvalidCount() { return invalidCount; }
    public void setInvalidCount(Integer invalidCount) { this.invalidCount = invalidCount; }

    public LocalDateTime getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(LocalDateTime lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
