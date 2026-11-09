package University.exam.service;

import University.exam.Entity.Student;
import University.exam.Entity.Submission;
import University.exam.Entity.ExamAttempt;
import University.exam.Entity.Feedback;
import University.exam.Entity.StudentActiveSession;
import University.exam.Entity.StudentLoginAttempt;
import University.exam.Entity.ExamEligibleStudent;
import University.exam.Entity.ExamViolation;
import University.exam.Entity.ExamPasteLog;
import University.exam.Entity.Answer;
import University.exam.Entity.Result;

import University.exam.repository.StudentRepository;
import University.exam.repository.FeedbackRepository;
import University.exam.repository.StudentExamActivityRepository;
import University.exam.repository.SubmissionRepository;
import University.exam.repository.ExamAttemptRepository;
import University.exam.repository.AnswerRepository;
import University.exam.repository.ResultRepository;
import University.exam.repository.StudentActiveSessionRepository;
import University.exam.repository.StudentLoginAttemptRepository;
import University.exam.repository.ExamEligibleStudentRepository;
import University.exam.repository.ExamViolationRepository;
import University.exam.repository.ExamPasteLogRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class StudentService {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private StudentExamActivityRepository studentExamActivityRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private ExamAttemptRepository examAttemptRepository;

    @Autowired
    private AnswerRepository answerRepository;

    @Autowired
    private ResultRepository resultRepository;

    @Autowired
    private StudentActiveSessionRepository studentActiveSessionRepository;

    @Autowired
    private StudentLoginAttemptRepository studentLoginAttemptRepository;

    @Autowired
    private ExamEligibleStudentRepository examEligibleStudentRepository;

    @Autowired
    private ExamViolationRepository examViolationRepository;

    @Autowired
    private ExamPasteLogRepository examPasteLogRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /**
     * Fetch a student by their unique enrollment number.
     * @param enrollmentNo The enrollment number of the student.
     * @return An Optional containing the Student if found, or empty otherwise.
     */
    public Optional<Student> getStudentByEnrollmentNo(String enrollmentNo) {
        return studentRepository.findByEnrollmentNo(enrollmentNo);
    }

    /**
     * Save a student to the database.
     * @param student The student object to save.
     * @return The persisted student entity.
     */
    public Student saveStudent(Student student) {
        if (student != null && student.getPassword() != null && !University.exam.util.PasswordUtil.isBCrypt(student.getPassword())) {
            student.setPassword(passwordEncoder.encode(student.getPassword().trim()));
        }
        return studentRepository.save(student);
    }

    /**
     * Delete a student and all associated records across the system in a cascade fashion.
     * @param id The ID of the student to delete.
     */
    @Transactional
    public void deleteStudentCascade(Long id) {
        Optional<Student> studentOpt = studentRepository.findById(id);
        if (studentOpt.isEmpty()) {
            return;
        }
        Student student = studentOpt.get();
        String enrollmentNo = student.getEnrollmentNo();

        // 1. Delete Answer and Result records associated with student's submissions, then delete submissions
        if (enrollmentNo != null) {
            List<Submission> submissions = submissionRepository.findByStudentEnrollmentNo(enrollmentNo);
            if (submissions != null) {
                for (Submission submission : submissions) {
                    List<Answer> submissionAnswers = answerRepository.findBySubmissionId(submission.getId());
                    if (submissionAnswers != null) {
                        answerRepository.deleteAll(submissionAnswers);
                    }
                    resultRepository.findBySubmissionId(submission.getId()).ifPresent(res -> resultRepository.delete(res));
                    submissionRepository.delete(submission);
                }
            }
        }

        // 2. Delete Answer records associated with student's exam attempts, then delete attempts
        if (enrollmentNo != null) {
            List<ExamAttempt> attempts = examAttemptRepository.findByStudentEnrollmentNo(enrollmentNo);
            if (attempts != null) {
                for (ExamAttempt attempt : attempts) {
                    List<Answer> attemptAnswers = answerRepository.findByExamAttemptId(attempt.getId());
                    if (attemptAnswers != null) {
                        answerRepository.deleteAll(attemptAnswers);
                    }
                    examAttemptRepository.delete(attempt);
                }
            }
        }

        // 3. Delete Feedbacks
        if (enrollmentNo != null) {
            List<Feedback> feedbacks = feedbackRepository.findAll().stream()
                .filter(f -> f.getStudent() != null && enrollmentNo.equals(f.getStudent().getEnrollmentNo()))
                .toList();
            feedbackRepository.deleteAll(feedbacks);
        }

        // 4. Delete Student Exam Activity
        if (enrollmentNo != null) {
            studentExamActivityRepository.findByStudentEnrollmentNo(enrollmentNo).ifPresent(act -> studentExamActivityRepository.delete(act));
        }
        studentExamActivityRepository.findByStudentId(student.getId()).ifPresent(act -> studentExamActivityRepository.delete(act));

        // 5. Delete Active Sessions
        if (enrollmentNo != null) {
            List<StudentActiveSession> sessions = studentActiveSessionRepository.findByStudentId(enrollmentNo);
            if (sessions != null) {
                studentActiveSessionRepository.deleteAll(sessions);
            }
        }

        // 6. Delete Login Attempts
        if (enrollmentNo != null) {
            List<StudentLoginAttempt> loginAttempts = studentLoginAttemptRepository.findByStudentId(enrollmentNo);
            if (loginAttempts != null) {
                studentLoginAttemptRepository.deleteAll(loginAttempts);
            }
        }

        // 7. Delete Eligible Student entries
        if (enrollmentNo != null) {
            List<ExamEligibleStudent> eligibleEntries = examEligibleStudentRepository.findAll().stream()
                .filter(e -> enrollmentNo.equals(e.getEnrollmentNo()) || (e.getStudentId() != null && e.getStudentId().equals(student.getId())))
                .toList();
            examEligibleStudentRepository.deleteAll(eligibleEntries);
        }

        // 8. Delete Violations
        if (enrollmentNo != null) {
            List<ExamViolation> violations = examViolationRepository.findByStudentId(enrollmentNo);
            if (violations != null) {
                examViolationRepository.deleteAll(violations);
            }
        }

        // 9. Delete Paste Logs
        if (enrollmentNo != null) {
            List<ExamPasteLog> pasteLogs = examPasteLogRepository.findAll().stream()
                .filter(p -> enrollmentNo.equals(p.getStudentId()))
                .toList();
            examPasteLogRepository.deleteAll(pasteLogs);
        }

        // 10. Delete the student
        studentRepository.delete(student);
    }
}
