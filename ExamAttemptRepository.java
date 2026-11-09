package University.exam.repository;

import University.exam.Entity.ExamAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {
    Optional<ExamAttempt> findByStudentEnrollmentNoAndExamId(String enrollmentNo, Long examId);
    List<ExamAttempt> findByStudentEnrollmentNo(String enrollmentNo);
    List<ExamAttempt> findByExamId(Long examId);
    long countByExamId(Long examId);
}
