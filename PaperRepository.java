package University.exam.repository;

import University.exam.Entity.Paper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperRepository extends JpaRepository<Paper, Long> {
    List<Paper> findByAdminId(Long adminId);
    long countByAdminId(Long adminId);

    List<Paper> findByExamStatusNot(String examStatus);
    List<Paper> findBySemesterAndExamStatusNot(String semester, String examStatus);

    @Query("SELECT p FROM Paper p WHERE (p.examStatus IS NULL OR p.examStatus <> :status) AND (p.semester = :semester OR (:digit <> '' AND p.semester LIKE CONCAT('%', :digit, '%')))")
    List<Paper> findActiveBySemester(@Param("status") String status, @Param("semester") String semester, @Param("digit") String digit);
}
