package University.exam.repository;

import University.exam.Entity.ExamEligibleStudent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamEligibleStudentRepository extends JpaRepository<ExamEligibleStudent, Long> {

    boolean existsByExamIdAndEnrollmentNo(Long examId, String enrollmentNo);

    List<ExamEligibleStudent> findByExamId(Long examId);

    List<ExamEligibleStudent> findByExamIdOrderByStudentNameAsc(Long examId);

    long countByExamId(Long examId);

    Optional<ExamEligibleStudent> findByExamIdAndEnrollmentNo(Long examId, String enrollmentNo);

    @Transactional
    void deleteByExamIdAndEnrollmentNo(Long examId, String enrollmentNo);

    @Transactional
    void deleteByExamId(Long examId);

    List<ExamEligibleStudent> findAllByOrderByImportedAtDesc();

    boolean existsByEnrollmentNo(String enrollmentNo);
}
