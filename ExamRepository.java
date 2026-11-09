package University.exam.repository;

import University.exam.Entity.Exam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamRepository extends JpaRepository<Exam, Long> {
    List<Exam> findByExamStatusNot(String examStatus);
    List<Exam> findBySemesterAndExamStatusNot(String semester, String examStatus);

    @Query("SELECT e FROM Exam e WHERE (e.examStatus IS NULL OR e.examStatus <> :status) AND (e.semester = :semester OR (:digit <> '' AND e.semester LIKE CONCAT('%', :digit, '%')))")
    List<Exam> findActiveBySemester(@Param("status") String status, @Param("semester") String semester, @Param("digit") String digit);
}
