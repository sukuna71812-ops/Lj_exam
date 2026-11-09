package University.exam.dto;

import University.exam.Entity.Exam;
import java.time.LocalDateTime;

public class ExamDTO {
    private Long id;
    private String examName;
    private String course;
    private String semester;
    private String subject;
    private Double totalMarks;
    private Integer examDuration;
    private String examStatus;
    private LocalDateTime activationTime;
    private LocalDateTime publishedTime;
    private LocalDateTime activatedTime;
    private LocalDateTime endTime;
    private String academicYear;
    private String division;

    public ExamDTO() {}

    public static ExamDTO fromEntity(Exam exam) {
        if (exam == null) return null;
        ExamDTO dto = new ExamDTO();
        dto.setId(exam.getId());
        dto.setExamName(exam.getExamName());
        dto.setCourse(exam.getCourse());
        dto.setSemester(exam.getSemester());
        dto.setSubject(exam.getSubject());
        dto.setTotalMarks(exam.getTotalMarks());
        dto.setExamDuration(exam.getExamDuration());
        dto.setExamStatus(exam.getExamStatus());
        dto.setActivationTime(exam.getActivationTime());
        dto.setPublishedTime(exam.getPublishedTime());
        dto.setActivatedTime(exam.getActivatedTime());
        dto.setEndTime(exam.getEndTime());
        dto.setAcademicYear(exam.getAcademicYear());
        dto.setDivision(exam.getDivision());
        return dto;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExamName() { return examName; }
    public void setExamName(String examName) { this.examName = examName; }

    public String getCourse() { return course; }
    public void setCourse(String course) { this.course = course; }

    public String getSemester() { return semester; }
    public void setSemester(String semester) { this.semester = semester; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public Double getTotalMarks() { return totalMarks; }
    public void setTotalMarks(Double totalMarks) { this.totalMarks = totalMarks; }

    public Integer getExamDuration() { return examDuration; }
    public void setExamDuration(Integer examDuration) { this.examDuration = examDuration; }

    public String getExamStatus() { return examStatus; }
    public void setExamStatus(String examStatus) { this.examStatus = examStatus; }

    public LocalDateTime getActivationTime() { return activationTime; }
    public void setActivationTime(LocalDateTime activationTime) { this.activationTime = activationTime; }

    public LocalDateTime getPublishedTime() { return publishedTime; }
    public void setPublishedTime(LocalDateTime publishedTime) { this.publishedTime = publishedTime; }

    public LocalDateTime getActivatedTime() { return activatedTime; }
    public void setActivatedTime(LocalDateTime activatedTime) { this.activatedTime = activatedTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public String getAcademicYear() { return academicYear; }
    public void setAcademicYear(String academicYear) { this.academicYear = academicYear; }

    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = division; }
}

